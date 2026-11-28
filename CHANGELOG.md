# Changelog

All notable changes to httpkt. Versions follow [semantic versioning](https://semver.org/): a
patch release (the last number) never changes a public signature.

## 1.1.0 (not released yet)

Additions only. Code written against 1.0.x compiles and runs unchanged.

### Added

- **`HttpKt`** is the new name for `HttpClientDsl` in `httpkt-dsl`: `val shop = HttpKt("https://api.shop.example")`.
  It is a type alias, so both names work and can be mixed.
- **`Authorization.expiringBearer { ... }`** in both modules, for tokens with a known lifetime such
  as OAuth access tokens with `expires_in`. The token is fetched again shortly before it expires
  and after a 401. This is what a Maskinporten or Entra ID integration needs.
- **`formBody(...)`** in `httpkt-functions`: a URL-encoded form body, as token endpoints expect.
  `httpkt-dsl` already had `form(...)`.
- **`requireSuccess()`** in `httpkt-dsl`: throws unless the status is `2xx` and returns the
  response, so `shop.post("/orders") { json(order) }.requireSuccess().header("Location")` reads in
  one line.
- **`bodyAs<T>()` on `HttpException`** in `httpkt-functions`, to read an error body as your own
  error type. `httpkt-dsl` already had it on `HttpStatusException`.
- **`defaultObjectMapper(ignoreUnknownProperties = true)`** in both modules, for APIs that may add
  JSON fields at any time.

### Documentation

- The README is rewritten around one example, a web shop, written once per module, and has a
  complete Maskinporten example. Every example runs as a test in the `examples` test packages.

## 1.0.1 (2026-11-28)

No public signature changed, so 1.0.1 is a drop-in replacement for 1.0.0. Four things behave
differently; check them if you rely on the old behaviour.

### Changed behaviour

- **`httpkt-functions`: response text follows the response's charset.** The `charset` of the
  response `Content-Type` is now used to decode bodies and error bodies, with UTF-8 when there is
  none or it is unknown. 1.0.0 always decoded as UTF-8, which garbled `æøå` from services that
  answer in ISO-8859-1.
- **`httpkt-dsl`: no `Content-Type` without a body.** `contentType(...)` on a request that has no
  body no longer sends the header. `httpkt-functions` already worked this way.
- **Both modules: `requestTimeout` must be positive.** Zero or a negative duration is rejected
  with `IllegalArgumentException` when the `HttpClientDsl` or `RequestConfig` is created. In 1.0.0
  the same exception came later, when the first request was sent.
- **`httpkt-functions`: `downloadFile` keeps at most 64 KB of an error body.** The exception for a
  failed download carries the first 64 KB of the response instead of all of it.

### Fixed

- `httpkt-dsl`: `~` in a path variable or query parameter is no longer sent as `%7E`. Both forms
  mean the same to a server; this matches `httpkt-functions` and RFC 3986.

### Added

- Both jars declare an `Automatic-Module-Name` (`io.github.httpkt.dsl`,
  `io.github.httpkt.functions`) for use on the Java module path.
- KDoc on every public declaration, and a `-javadoc` jar with real API documentation instead of
  only the README.
- The library is compiled against the Kotlin 1.9 standard-library API, so it runs when a build
  manages `kotlin-stdlib` down to 1.9, as Spring Boot 3 does. Tested with `kotlin-stdlib` 1.9.25.

### Documentation

- Debug logging under Spring Boot is checked on Boot 3.5 and 4.2:
  `logging.level.io.github.httpkt=DEBUG` works with the default starter, nothing to add.

### Internal

- `RequestConfig` parses its `url` once instead of on every call.

## 1.0.0 (2026-11-27)

First release on Maven Central: `httpkt-dsl` and `httpkt-functions`, two independent modules on
the JDK's `java.net.http.HttpClient` with Jackson as the only dependency.
