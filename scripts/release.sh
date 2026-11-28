#!/usr/bin/env bash
# Releases a version of httpkt to Maven Central.
#
#   scripts/release.sh 1.0.1             release
#   scripts/release.sh 1.0.1 --dry-run   build and test everything, change nothing
#
# It needs a section "## <version> (not released yet)" in CHANGELOG.md: that section gets today's
# date and becomes the GitHub release notes.
#
# It uploads and validates the release but does not publish it: the last step is one click on
# "Publish" at https://central.sonatype.com/publishing/deployments. A published version can
# never be changed or removed, so that click stays with a human.
#
# Needs, once per machine: a Central Portal token under server id "central" in
# ~/.m2/settings.xml, a GPG key, and the gh CLI logged in.
set -euo pipefail

version="${1:-}"
dry_run="${2:-}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || [[ -n "$dry_run" && "$dry_run" != "--dry-run" ]]; then
    echo "Usage: scripts/release.sh <major.minor.patch> [--dry-run]" >&2
    exit 2
fi
cd "$(git rev-parse --show-toplevel)"

tag="v$version"
next="${version%.*}.$(( ${version##*.} + 1 ))-SNAPSHOT"
previous="$(git describe --tags --abbrev=0 --match 'v*' | sed 's/^v//')"
step() { printf '\n==> %s\n' "$*"; }
fail() { echo "ERROR: $*" >&2; exit 1; }

step "Checking the starting point"
[[ "$(git branch --show-current)" == "master" ]] || fail "not on master"
[[ -z "$(git status --porcelain)" ]] || fail "the working tree has uncommitted changes"
git fetch --quiet origin master --tags
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/master)" ]] || fail "master differs from origin/master; push or pull first"
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && fail "tag $tag already exists"
if [[ -z "$dry_run" ]]; then
    grep -q '<id>central</id>' "$HOME/.m2/settings.xml" 2>/dev/null || fail "no server 'central' in ~/.m2/settings.xml"
    gh auth status >/dev/null 2>&1 || fail "gh is not logged in"
    step "Unlocking the GPG key (a passphrase dialog may appear)"
    echo test | gpg --clearsign >/dev/null || fail "gpg could not sign"
fi

# Until the release commit exists, any failure puts the files back as they were.
restore() { git restore pom.xml httpkt-dsl/pom.xml httpkt-functions/pom.xml README.md CHANGELOG.md; }
trap restore EXIT

set_version() {
    mvn -B -ntp -q versions:set -DnewVersion="$1" -DgenerateBackupPoms=false -DprocessAllModules=true
}

step "Setting version $version (README: $previous -> $version)"
set_version "$version"
# Only the install coordinates, never a sentence such as "from 1.0.1". The versions go in through
# the environment: pasted into the Perl source, "$1" followed by "1.0.1" would read as group 11.
old="${previous//./\\.}" new="$version" perl -pi -e '
    s{(Current version: \*\*`)$ENV{old}(`)}{$1$ENV{new}$2};
    s{(<version>)$ENV{old}(</version>)}{$1$ENV{new}$2};
    s{(io\.github\.httpkt:httpkt-(?:dsl|functions):)$ENV{old}(")}{$1$ENV{new}$2};
' README.md
[[ "$(grep -c "httpkt-\(dsl\|functions\):$version\"" README.md)" -ge 2 ]] || fail "README install coordinates were not updated to $version"

step "Dating the changelog and taking the release notes from it"
grep -q "^## ${version//./\\.} (" CHANGELOG.md || fail "CHANGELOG.md has no section '## $version (...)'"
new="$version" today="$(date +%Y-%m-%d)" perl -pi -e 's{^## \Q$ENV{new}\E \(not released yet\)}{## $ENV{new} ($ENV{today})}' CHANGELOG.md
# The README may carry a note that it describes this version before it is out. Now it is.
new="$version" perl -0pi -e 's{^> \*\*This README describes \Q$ENV{new}\E, which is not released yet\.\*\*[^\n]*\n\n}{}m' README.md
grep -q "not released yet" README.md && fail "README.md still says something is not released yet"
notes="$(mktemp)"
awk -v v="$version" '$1 == "##" { on = ($2 == v); next } on' CHANGELOG.md > "$notes"
[[ -n "$(tr -d '[:space:]' < "$notes")" ]] || fail "the CHANGELOG.md section for $version is empty"
printf '\n**Full changelog**: https://github.com/httpkt/httpkt/compare/v%s...%s\n' "$previous" "$tag" >> "$notes"

if [[ -n "$dry_run" ]]; then
    echo "Release notes: $(wc -l < "$notes" | tr -d ' ') lines from CHANGELOG.md, starting with:"
    grep -m3 -v '^$' "$notes" | sed 's/^/    /'
    step "Dry run: building, testing and packaging without signing or upload"
    mvn -B -ntp -Prelease -Dgpg.skip clean verify
    step "Dry run OK. Nothing was committed, tagged or uploaded; the files are restored."
    exit 0
fi

step "Building, testing, signing and uploading to Maven Central"
mvn -B -ntp -Prelease clean deploy

step "Committing and tagging $tag"
git commit -q -am "Release $version"
trap - EXIT
git tag -a "$tag" -m "httpkt $version"

step "Moving master on to $next"
set_version "$next"
git commit -q -am "Start $next"

step "Pushing master and $tag"
git push --quiet origin master "$tag"
gh release create "$tag" --title "httpkt $version" --notes-file "$notes"

cat <<EOF

==> Uploaded and validated, but NOT published yet.
    Open https://central.sonatype.com/publishing/deployments
    check that the deployment says VALIDATED, then click Publish.
EOF
