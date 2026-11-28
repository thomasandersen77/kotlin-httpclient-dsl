#!/usr/bin/env bash
# Releases a version of httpkt to Maven Central.
#
#   scripts/release.sh 1.0.1             release
#   scripts/release.sh 1.0.1 --dry-run   build and test everything, change nothing
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
restore() { git restore pom.xml httpkt-dsl/pom.xml httpkt-functions/pom.xml README.md; }
trap restore EXIT

set_version() {
    mvn -B -ntp -q versions:set -DnewVersion="$1" -DgenerateBackupPoms=false -DprocessAllModules=true
}

step "Setting version $version (README: $previous -> $version)"
set_version "$version"
# Only the install coordinates, never a sentence such as "from 1.0.1".
p="${previous//./\\.}" perl -pi -e '
    s{(Current version: \*\*`)$ENV{p}(`)}{$1'"$version"'$2};
    s{(<version>)$ENV{p}(</version>)}{$1'"$version"'$2};
    s{(io\.github\.httpkt:httpkt-(?:dsl|functions):)$ENV{p}(")}{$1'"$version"'$2};
' README.md

if [[ -n "$dry_run" ]]; then
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
gh release create "$tag" --title "httpkt $version" --generate-notes

cat <<EOF

==> Uploaded and validated, but NOT published yet.
    Open https://central.sonatype.com/publishing/deployments
    check that the deployment says VALIDATED, then click Publish.
EOF
