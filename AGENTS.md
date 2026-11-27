# kotlin-httpclient-dsl — Agent guide

Repository facts below were checked against the source and Maven configuration on
2026-11-27. Read this file before working here. This repository is independent of
other projects in the parent directory; do not carry their architecture or tooling
requirements into this one.

## Start here

1. Inspect `git status --short`, `git diff --stat`, the current branch and recent history.
2. Read the root `pom.xml` and the POM of the module you will change.
3. Read the relevant production code and its tests. Source and effective build
   configuration take precedence over README examples.
4. Preserve existing changes. Do not reset, stash, overwrite or force-push work
   without the user's instruction.

## Purpose and layout

This is a small Kotlin library/sample repository around the JDK's
`java.net.http.HttpClient`. It has no Spring Boot application entry point, database,
frontend or deployment setup.

The root Maven reactor contains **two** modules:

| Module | Purpose | Main package |
| --- | --- | --- |
| `kotlin-httpclient-dsl` | Receiver-lambda DSL for requests, headers and JSON/raw bodies | `io.github.thomasandersen77.httpclient.dsl` |
| `kotlin-httpclient-functions` | Function-based HTTP helpers on `RequestConfig` | `io.github.thomasandersen77.httpclient.functions` |

Each module is meant to be used as one dependency. They must stay independent of each other.

## Request paths through the code

### DSL module

`HttpClientExtensions.kt`: `HttpClientDsl.get/post/put/patch/delete` (member functions)
→ `UriExpander.expand` (`internal`)
→ receiver configuration on `RequestDsl`
→ `RequestDsl.exchange`
→ synchronous JDK `HttpClient.send` with a string response body.

- The DSL is flat: `header`, `queryParam`, `authorization`, `basicAuth`, `bearerToken`,
  `withoutAuthorization`, `contentType`, `body`, `json` and `form` are called directly on
  `RequestDsl` (marked `@HttpDsl`). There are no nested header/body blocks.
- `header(name, value)` and the client's `defaultHeaders` reject `Content-Type` and
  `Authorization` (case-insensitive) with `IllegalArgumentException` (`requireOrdinaryHeader`),
  so a request never carries two values for them. A request header replaces a default header
  with the same name (case-insensitive); other repeated headers are appended.
- `json(value)` serializes with the client's `ObjectMapper` and sets
  `Content-Type: application/json`; `form(vararg pairs)` sends an encoded
  `application/x-www-form-urlencoded` body; `body(content, contentType)` sets a raw string
  body and its default `Content-Type`. An explicit `contentType(...)` overrides any of these
  defaults, in either order.
- `queryParam` and `form` skip `null` and add one entry per element of an `Iterable` or
  any array, primitive arrays included (`valuesOf`).
- The default `ObjectMapper` is the shared `dslObjectMapper` (Kotlin module and Java time
  registered). `defaultObjectMapper()` returns a copy of it for callers to adjust; never
  reconfigure the shared instance.
- GET and DELETE reject a body (`json`, `form` or `body`) with `IllegalStateException` before sending;
  POST, PUT and PATCH allow a body.
- `Authorization` is a `fun interface` (`Authorization.kt`): `headerValue()` returns the
  full header value and is called in `exchange`, once per sent request, never when the
  request fails before sending. `onUnauthorized(rejectedHeaderValue)` defaults to `false`; returning `true`
  makes `exchange` resend the request exactly once after a 401. `CachedAuthorization`
  caches until a 401 and guards against recursive token fetches. `Authorization.Companion`
  provides `basic(username, password)`, `bearer(token)`, `bearer(tokenProvider)` and `cachedBearer(fetchToken)`.
  Set on `HttpClientDsl`, replaced with `RequestDsl.authorization` / `basicAuth` / `bearerToken`,
  dropped with `RequestDsl.withoutAuthorization`.
- `HttpClientDsl` has two constructors: `(baseUrl, connectTimeout = 30s, authorization = null, ...)`
  which builds the shared `HttpClient`, and `(baseUrl, httpClient, authorization = null, ...)` which
  uses a caller-supplied `HttpClient`. Both end with `objectMapper = defaultObjectMapper()`,
  `defaultHeaders = emptyMap()` and `requestTimeout = 30s`. Every request uses the client's
  shared `HttpClient`.
- Connection and request timeouts default to 30 seconds. `RequestDsl.requestTimeout` starts as
  the client's `requestTimeout` and can be reassigned per request.
- Results are `HttpResponse<String>`; callers read raw strings with `body()` or deserialize
  JSON with the `bodyAs<T>()` extension. The object actually returned is the internal
  `MappedHttpResponse`, which delegates to the JDK response and carries the client's
  `ObjectMapper`; `bodyAs` falls back to `dslObjectMapper` for any other `HttpResponse<String>`.
  It delegates `toString` and `hashCode`, and equals only another wrapper of the same response.
- `HttpClientDsl.objectMapper` defaults to `defaultObjectMapper()`, a fresh copy per client. The
  shared `dslObjectMapper` must never be reachable through a public property.
- `baseUrl` is parsed once; `joinUrl` joins it with the expanded path structurally (paths with one
  slash, queries combined, the path's fragment first). It must include scheme and host, and
  the path must not be an absolute URL (`relativePath` rejects `scheme://` and `//host`, and adds
  the leading slash so a first segment with a colon is not read as a scheme).
- `defaultHeaders` is copied in the constructor.
- `HttpStatusException` has `status`, `body`, `method` and `uri`. Its message is
  `HTTP status <n> for <METHOD> <scheme://host[:port]/path>` when thrown by `bodyAs` and
  `HTTP status <n>` when constructed with only status and body; it never contains the query
  string, user info or response body. `HttpStatusException.bodyAs<T>()` and
  `HttpResponse.bodyAs<T>()` share `convertBody`.
- `bodyAsOrNull<T>()` returns `null` for a `404` and otherwise behaves as `bodyAs<T?>()`.
- `get/post/put/patch/delete` return the `HttpResponse<String>` for every HTTP status without
  throwing. `bodyAs<T>()` throws `HttpStatusException` when the status is
  outside `200..299`, returns `Unit` when `T` is `Unit`, returns the raw text unparsed when
  `T` is `String` (empty and blank text included; `String?` gives `null` only for an empty body),
  as `String.asType` does in the functions module, returns `null` for an empty body
  when `T` is nullable, and throws `IllegalStateException` for an empty body when `T` is non-nullable.

### Functions module

`FooHttpClient` (example typed client, in `src/test`)
→ `RequestConfig.get/getOrNull/post/put/patch/delete` (all delegate to `exchange`) or `downloadFile`
  (same `send`, with a file body handler, so it also resends once after a 401)
→ `RequestConfig.send` (`buildRequest`, `HttpClient.send` with a byte-array body, one resend after a 401)
→ `HttpResponse<ByteArray>.bodyAs` → `String.asType` for Jackson deserialization (with `ByteArray`, `String`, `Unit` and nullable support).

- `RequestConfig(url, authorization, headers, requestTimeout, httpClient, objectMapper)`. The port
  is part of `url`. `defaultHttpClient` (shared) and `mapper` are `internal`; `objectMapper`
  defaults to `defaultObjectMapper()`, a fresh copy per config. `toString()` prints header names
  only, never the authorization, and the url without user info and query.
- `resolveUri` rejects an absolute URL as `path` (same `relativePath` rule as the DSL module, with
  its own copy of the helper) and leaves the base path untouched for an empty path.
- `Authorization` (`Authorization.kt`) is this module's own `fun interface`, deliberately
  not shared with the DSL module: `headerValue()` is called once per sent request, and
  `onUnauthorized(rejectedHeaderValue)` (default `false`) decides whether `send` resends once
  after a 401. `Authorization.basic`, `bearer(token)`, `bearer { }` never retry;
  `cachedBearer { }` / `CachedAuthorization` caches until a 401 and guards against recursive fetches.
  An `Authorization` entry in the config's or the request's `headers` takes the place of
  `authorization`: it is then neither asked for a value nor told about a 401.
- `path` may contain `{name}` placeholders, filled from `pathParams` by `expandPath` with
  `encodePathSegment`. Placeholder names and `pathParams` keys must match exactly
  (`IllegalArgumentException` otherwise).
- A `queryParams` value that is an `Iterable` or an array gives one parameter per non-null element.
- `exchange<T>(method, path, ...)` returns `HttpResult(status, headers, body)`; `Method` is public.
- `Accept` is `application/json`, or `*/*` for `ByteArray` results and `downloadFile`.
  `Content-Type` is set only when the request has a body; without one, a `Content-Type` in either
  `headers` map is dropped. Precedence, weakest first: the
  body's default (`application/octet-stream` for a `ByteArray`, `application/json` otherwise),
  the config's `headers`, the call's `contentType`, the call's `headers`. A `MultipartBody`
  always supplies its own, set last so nothing can replace the boundary.
- `downloadFile` writes to a `.part` file beside the destination, opened with
  `TRUNCATE_EXISTING` because a 401 resend reuses it, and moves it into place only after a 2xx;
  the `.part` file is always removed. The destination directory is checked before the request.
- `MultipartBody` rejects CR/LF in names, filenames and content types, and requires an
  boundary of 1-70 characters from `A-Z a-z 0-9 ' + _ - .`, which is safe unquoted in the header.
- For `ByteArray` results only a 204 is "no content" (`getOrNull` gives `null`); an empty 200
  is an empty array.
- `httpGet/httpGetOrNull/httpPost/httpPut/httpPatch/httpDelete` are `@Deprecated` wrappers around
  the extensions; `FooHttpClientTest` still covers them under `@Suppress("DEPRECATION")`.
- Statuses `200..299` succeed; `400..499` throw `HttpClientException`; `500..599` throw
  `HttpServerException`; other non-2xx statuses (including `3xx` when not followed) throw
  `HttpException`. All inherit from `HttpException(status, body, message)`.
- `Foo` and `FooHttpClient` are examples and live in `src/test`, so they are not part of the jar.
- Both modules are published as one dependency each and must stay independent of each other.
  Runtime dependencies are limited to the Kotlin standard library and Jackson; do not add others
  (logging, HTTP clients, XML) without the owner's decision.
- The public functions are `inline`: after changing a signature in `HttpFunctions.kt`, run
  `mvn clean test`, otherwise stale test classes fail with `NoSuchMethodError`.

### Logging (both modules)

Each module has an internal `Logging.kt` with the same content apart from the package and the
logger name (`io.github.thomasandersen77.httpclient.dsl`, `io.github.thomasandersen77.httpclient.functions`). It uses `System.Logger`, never SLF4J or any
other logging dependency, and logs only at `DEBUG` with lazy `Supplier` messages:

- one line per response: `<METHOD> <url> -> <status> in <n> ms`
- one line per transport failure: `<METHOD> <url> failed after <n> ms: <ExceptionSimpleName>`
- one line before a resend after 401

The URL is always `withoutSecrets()` (scheme, host, port, path). Never log the query string, user
info, header values or bodies. The calls sit in the non-inline send path (`RequestDsl.send`,
`RequestConfig.send`), not in the inline public functions. Tests capture the output by attaching a
`java.util.logging.Handler`, which is where `System.Logger` ends up on the test classpath.

Keep the two client approaches distinct unless consolidation is explicitly in scope.

## Build and tests

Use an installed Maven (`mvn`); no Maven wrapper or repository toolchain file is
checked in. Inspect `java -version` and `mvn -version` before diagnosing build errors.

```bash
# Both modules in the root reactor
mvn clean test

# A single module and its reactor prerequisites
mvn -pl kotlin-httpclient-dsl -am test
mvn -pl kotlin-httpclient-functions -am test

# Full Maven lifecycle verification
mvn clean verify
```

The tests are JUnit Jupiter tests executed by Surefire. Both HTTP modules start
WireMock on dynamic local ports; they require no external API credentials or Docker.
`DslTest` has 43 tests, `DslUsageExamplesTest` has 11, `FooHttpClientTest` has 28,
`FunctionsUsageExamplesTest` has 10, and `RequestConfigTest` has 12. The two `*UsageExamplesTest`
classes are the runnable source of the README's usage examples.
There is no separate Failsafe integration-test setup.

The root POM targets Java 11 with Kotlin `2.1.10` and Jackson `2.18.2`. Keep `failIfNoTests=true`
so an empty test run cannot pass silently.

Read Surefire reports under each module's `target/surefire-reports`. A successful
command that ran zero tests is not evidence that the suite passed. Report the JDK,
command, actual test counts and any build blocker when verifying changes.

## Existing behavior and pitfalls

These are observations, not approval to change unrelated behavior:

- `UriExpander` (`internal`) validates that placeholder count matches supplied `uriVariables`
  and URL-encodes each variable (with `%20` for spaces).
- `RequestDsl.exchange` sends `BodyPublishers.noBody()` when no body is set, so bodyless
  `POST`, `PUT` and `PATCH` requests preserve their HTTP method.
- Both modules register Jackson's `KotlinModule` and `JavaTimeModule` with
  `WRITE_DATES_AS_TIMESTAMPS` disabled.
## Change discipline

Prefer small Kotlin functions and receiver lambdas consistent with the existing
API. Keep HTTP methods, URI construction, headers, body serialization, timeout
handling and response/error behavior explicit. Avoid introducing Spring services,
repository layers, retries or coroutine abstractions without a concrete need.

For a behavior change, add a regression test that checks the actual HTTP request
or response through WireMock. Cover the relevant method, path, headers, body and
status rather than implementation details. Keep tests local and deterministic;
never call a real HTTP service as part of the normal suite. Use dummy credentials
and do not log real authorization values.

## CI and configuration caveats

`.github/workflows/maven-test.yml` runs `mvn -B -ntp clean test` on Java 11 and 21 for pushes to
`master`, `main` and `releases/*` and for pull requests. Local runs are usually on a newer JDK,
and the Kotlin compiler runs with `-Xjdk-release=11`, so a JDK 12+ API (`Stream.toList()` is a
typical slip) fails to compile in main and test code alike; the workflow then verifies the run. The Qodana workflow only triggers on `main` and
`releases/*`. `.github/dependabot.yml` covers Maven and GitHub Actions weekly. Keep CI repairs separate from
unrelated library work and verify them before claiming automation works.
