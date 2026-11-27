# kotlin-httpclient-dsl — Agent guide

Repository facts below were checked against the source and Maven configuration on
2026-10-23. Read this file before working here. This repository is independent of
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
| `kotlin-httpclient-dsl` | Receiver-lambda DSL for requests, headers and JSON/raw bodies | `io.httpdsl` |
| `kotlin-httpclient-functions` | Function-based HTTP helpers on `RequestConfig` | `io.httpdsl.functions` |

`kotlin-func-test` is a separate experimental directory with its own POM. It is
**not** listed in the root reactor and is not covered by a root build. It contains
test-description helpers and Norwegian domain examples, not the HTTP DSL's tests.
Do not add it to the reactor as incidental cleanup.

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
- GET and DELETE reject a body (`json` or `body`) with `IllegalStateException` before sending;
  POST, PUT and PATCH allow a body.
- `Authorization` is a `fun interface` (`Authorization.kt`): `headerValue()` returns the
  full header value and is called in `exchange`, once per sent request, never when the
  request fails before sending. `onUnauthorized(rejectedHeaderValue)` defaults to `false`; returning `true`
  makes `exchange` resend the request exactly once after a 401. `CachedAuthorization`
  caches until a 401 and guards against recursive token fetches. `Authorization.Companion`
  provides `basic(username, password)`, `bearer(token)` and `cachedBearer(fetchToken)`.
  Set on `HttpClientDsl`, replaced with `RequestDsl.authorization` / `basicAuth` / `bearerToken`,
  dropped with `RequestDsl.withoutAuthorization`.
- `HttpClientDsl` has two constructors: `(baseUrl, connectTimeout = 30s, authorization = null, ...)`
  which builds the shared `HttpClient`, and `(baseUrl, httpClient, authorization = null, ...)` which
  uses a caller-supplied `HttpClient`. Both end with `objectMapper = dslObjectMapper`,
  `defaultHeaders = emptyMap()` and `requestTimeout = 30s`. Every request uses the client's
  shared `HttpClient`.
- Connection and request timeouts default to 30 seconds. `RequestDsl.requestTimeout` starts as
  the client's `requestTimeout` and can be reassigned per request.
- Results are `HttpResponse<String>`; callers read raw strings with `body()` or deserialize
  JSON with the `bodyAs<T>()` extension. The object actually returned is the internal
  `MappedHttpResponse`, which delegates to the JDK response and carries the client's
  `ObjectMapper`; `bodyAs` falls back to `dslObjectMapper` for any other `HttpResponse<String>`.
- `HttpStatusException` has `status`, `body`, `method` and `uri`. Its message is
  `HTTP status <n> for <METHOD> <uri>` when thrown by `bodyAs` and `HTTP status <n>` when
  constructed with only status and body; it never contains the response body.
  `HttpStatusException.bodyAs<T>()` deserializes the error body with the client's `ObjectMapper`.
- `bodyAsOrNull<T>()` returns `null` for a `404` and otherwise behaves as `bodyAs<T?>()`.
- `get/post/put/patch/delete` return the `HttpResponse<String>` for every HTTP status without
  throwing. `bodyAs<T>()` throws `HttpStatusException` when the status is
  outside `200..299`, returns `Unit` when `T` is `Unit`, returns the raw text unparsed when
  `T` is `String` (as `String.asType` does in the functions module), returns `null` for an empty body
  when `T` is nullable, and throws `IllegalStateException` for an empty body when `T` is non-nullable.

### Functions module

`FooHttpClient` (example typed client, in `src/test`)
→ `RequestConfig.get/getOrNull/post/put/patch/delete` (all delegate to `exchange`) or `downloadFile`
  (same `send`, with a file body handler, so it also resends once after a 401)
→ `RequestConfig.send` (`buildRequest`, `HttpClient.send` with a byte-array body, one resend after a 401)
→ `HttpResponse<ByteArray>.bodyAs` → `String.asType` for Jackson deserialization (with `ByteArray`, `String`, `Unit` and nullable support).

- `RequestConfig(url, authorization, headers, requestTimeout, httpClient, objectMapper)`. The port
  is part of `url`. `defaultHttpClient` is the shared default client. The shared default
  `mapper` is `internal`; callers get a copy from `defaultObjectMapper()`.
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
- `Accept: application/json` is always set. `Content-Type` is set only when the request has a
  body (`MultipartBody` supplies its own).
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
`DslTest` has 35 tests, `DslUsageExamplesTest` has 11, `FooHttpClientTest` has 23,
`FunctionsUsageExamplesTest` has 10, and `RequestConfigTest` has 8. The two `*UsageExamplesTest`
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
- `kotlin-func-test` includes JVM and JavaScript compiler goals. Its example test
  checks for chapter-20 information while `Ytelse.kap20Info` always returns an empty
  list. Treat it as an experiment, not a verified passing suite.

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

The checked-in workflow runs Qodana, not an explicit Maven test job. Its push
filter names `main` and `releases/*`, while the cloned default branch is `master`.
Do not assume every push to `master` runs CI. `qodana.yaml` selects the JVM 2025.1
linter. `.github/dependabot.yml` is an unfinished configuration with an empty
ecosystem and an unterminated interval string. Keep CI repairs separate from
unrelated library work and verify them before claiming automation works.
