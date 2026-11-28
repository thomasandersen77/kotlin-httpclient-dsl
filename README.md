# httpkt

[![Maven Tests](https://github.com/httpkt/httpkt/actions/workflows/maven-test.yml/badge.svg)](https://github.com/httpkt/httpkt/actions/workflows/maven-test.yml)
[![Java 11+](https://img.shields.io/badge/Java-11%2B-blue)](#installation)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

A small, idiomatic Kotlin library for calling HTTP APIs — built directly on the JDK's `java.net.http.HttpClient` and Jackson.

- **One dependency to add.** Pick one of the two independent modules below.
- **Minimal classpath.** Only Kotlin stdlib and Jackson — no third-party HTTP client, no logging framework, no XML library, and no Spring or framework tie-in.
- **No global state.** No service loading, no logging binding, and no shared mutable configuration — every client or config carries its own settings and `ObjectMapper`.

## Quick start

```kotlin
import io.github.httpkt.dsl.*

val http = HttpClientDsl(
    baseUrl = "https://api.example.com",
    authorization = Authorization.cachedBearer { fetchToken() }, // fetched once, refreshed on 401
)

val created = http.post("/orders") {
    json(NewOrder(customer = "acme", items = listOf("book")))
}.bodyAs<Order>()

val order = http.get("/orders/{id}", 42).bodyAsOrNull<Order>()   // null on 404
```

**Jump to:** [Choosing a module](#choosing-a-module) · [Installation](#installation) · [DSL module](#1-dsl-module-httpkt-dsl) · [Functions module](#2-functions-module-httpkt-functions) · [Debug logging](#3-debug-logging) · [Good to know](#4-good-to-know)

---

## Choosing a module

Both modules are independent — pick **one** to add to your project:

| | `httpkt-dsl` | `httpkt-functions` |
| --- | --- | --- |
| **Package** | `io.github.httpkt.dsl` | `io.github.httpkt.functions` |
| **Style** | Receiver-lambda DSL: `http.post("/orders") { json(order) }` | Typed one-line calls: `config.post<Order>("/orders", body = order)` |
| **Return type** | `HttpResponse<String>` + `.bodyAs<T>()` / `.bodyAsOrNull<T>()` | Typed `T` directly, or `HttpResult<T>` via `exchange()` |
| **Error handling** | `HttpStatusException` (with `e.bodyAs<ApiError>()`) | `HttpException` (`HttpClientException` 4xx, `HttpServerException` 5xx) |
| **Extras** | `form(...)`, `withoutAuthorization()`, custom DSL extensions | `multipart { ... }`, `downloadFile(...)`, `ByteArray` payloads |
| **Auth & 401 retry** | `Authorization` (`basic`, `bearer`, `cachedBearer`, or lambda) | `Authorization` (`basic`, `bearer`, `cachedBearer`, or lambda) |

Use **`httpkt-dsl`** when you want to shape each request and inspect responses; use **`httpkt-functions`** when you want concise typed calls, multipart uploads, or file downloads.

---

## Installation

Requires **Java 11** or newer. .0.1`**, available on [Maven Central](https://central.sonatype.com/namespace/io.github.httpkt).

### Maven

Add **one** of the two modules:

```xml
<!-- DSL module -->
<dependency>
    <groupId>io.github.httpkt</groupId>
    <artifactId>httpkt-dsl</artifactId>
    .0.1</version>
</dependency>
```

```xml
<!-- Functions module -->
<dependency>
    <groupId>io.github.httpkt</groupId>
    <artifactId>httpkt-functions</artifactId>
    .0.1</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
// Pick one:
implementation(".0.1")
// implementation(".0.1")
```

<details>
<summary>What it adds to your classpath (runtime dependencies)</summary>

Both modules share the exact same runtime dependency tree (`mvn dependency:tree -Dscope=runtime`):

| Dependency | Why |
| --- | --- |
| `kotlin-stdlib` (with `org.jetbrains:annotations`) | Library written in Kotlin |
| `jackson-databind` (`jackson-core`, `jackson-annotations`) | JSON serialization |
| `jackson-module-kotlin` (with `kotlin-reflect`) | Kotlin data classes |
| `jackson-datatype-jsr310` | `java.time` types as ISO strings |

If your build already manages Jackson or Kotlin versions (for example via Spring Boot's BOM), your versions win; `httpkt` is built against Jackson 2.18 and Kotlin 2.1. From 1.0.1 it only uses Kotlin 1.9 standard-library API, so an older `kotlin-stdlib` down to 1.9 (as Spring Boot 3 manages it) works.

JSON is the only built-in format. Other formats (such as XML) go through as text so no XML library is pulled onto your classpath unless you add one yourself.

</details>

---

## 1. DSL Module (`httpkt-dsl`)

All examples below are backed by runnable WireMock tests in [`DslUsageExamplesTest.kt`](httpkt-dsl/src/test/kotlin/io/github/httpkt/dsl/DslUsageExamplesTest.kt).

### Building a typed API client

Keep `HttpResponse` inside one small client class per remote API and expose domain types to callers:

```kotlin
import io.github.httpkt.dsl.*
import java.net.URI

class OrderClient(private val http: HttpClientDsl) {

    fun find(id: Long): OrderDto? =
        http.get("/orders/{id}", id).bodyAsOrNull()            // {id} is URL-encoded; 404 gives null

    fun search(status: String, page: Int = 0): List<OrderDto> =
        http.get("/orders") {
            queryParam("status", status)
            queryParam("page", page)
        }.bodyAs()

    fun create(order: NewOrderDto): URI {
        val response = http.post("/orders") { json(order) }
        response.bodyAs<Unit>()                                // throws HttpStatusException unless 2xx
        return URI.create(response.header("Location") ?: error("Missing Location header"))
    }

    fun cancel(id: Long) {
        http.delete("/orders/{id}", id).bodyAs<Unit>()
    }
}
```

### Authentication & automatic 401 refresh

Supply an `Authorization` once on the client. `Authorization.cachedBearer` fetches a token once and, if the server answers `401`, fetches a fresh token and resends the request once:

```kotlin
val tokenClient = HttpClientDsl("https://auth.example.com")

val http = HttpClientDsl(
    baseUrl = "https://api.example.com",
    authorization = Authorization.cachedBearer {
        tokenClient.post("/oauth/token") {
            form("grant_type" to "client_credentials")
        }.body()
    },
)

http.get("/orders")                                       // uses cached token, refreshes on 401
http.get("/admin") { basicAuth("admin", "secret") }       // overrides auth for this call
http.get("/public") { withoutAuthorization() }            // omits Authorization header
```

Static or custom schemes never retry on `401`: `Authorization.basic(user, pass)`, `Authorization.bearer(token)`, `Authorization.bearer { getToken() }`, or any lambda `authorization = { "ApiKey $key" }`.

<details>
<summary>Example: custom expiry-based Authorization</summary>

Implement the `Authorization` interface when you want proactive refresh before expiry as well as retry after `401`:

```kotlin
class ExpiringTokenAuthorization(
    private val now: () -> Instant = Instant::now,
    private val margin: Duration = Duration.ofSeconds(30),
    private val fetch: () -> IssuedToken,
) : Authorization {
    private var current: IssuedToken? = null

    @Synchronized
    override fun headerValue(): String {
        val token = current?.takeIf { now() < it.expiresAt.minus(margin) } ?: fetch().also { current = it }
        return "Bearer ${token.value}"
    }

    @Synchronized
    override fun onUnauthorized(rejectedHeaderValue: String): Boolean {
        current = null
        return true
    }
}

val http = HttpClientDsl(
    baseUrl = "https://api.example.com",
    authorization = ExpiringTokenAuthorization { tokenService.issue() },
)
```

</details>

### Headers, query parameters, forms & timeouts

Put defaults on `HttpClientDsl` and override per request when needed:

```kotlin
val http = HttpClientDsl(
    baseUrl = "https://api.example.com/v1?api-version=2",      // path prefix & query are kept on every call
    defaultHeaders = mapOf("Accept" to "application/json", "X-Tenant" to "acme"),
    requestTimeout = Duration.ofSeconds(5),                    // default is 30 s
    objectMapper = defaultObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
)

// Override default headers and timeout per request
http.get("/orders/export") {
    header("Accept", "text/csv")                               // replaces default Accept
    requestTimeout = Duration.ofSeconds(60)
}

// Multi-value query parameters (null values are skipped)
http.get("/orders") {
    queryParam("id", listOf(1, 2, 3))                          // ?api-version=2&id=1&id=2&id=3
    queryParam("note", null)                                   // skipped
}

// URL-encoded form body (sets Content-Type: application/x-www-form-urlencoded)
http.post("/oauth/token") {
    withoutAuthorization()
    form("grant_type" to "client_credentials", "scope" to "orders:read orders:write")
}

// Raw text / XML body
http.post("/webhooks") {
    body("<event id=\"1\"/>", "application/xml")
}
```

- `header(...)` and `defaultHeaders` reject `Content-Type` and `Authorization` (`IllegalArgumentException`) so a request can never accidentally carry duplicate values — use `contentType(...)` and `authorization` / `bearerToken` / `basicAuth`.
- `GET` and `DELETE` reject a body (`IllegalStateException`) before sending.

### Reading responses & handling errors

`get`, `post`, `put`, `patch`, and `delete` always return `HttpResponse<String>` without throwing on HTTP error statuses. Choose how to read the response:

| Call | 2xx behavior | Non-2xx behavior |
| --- | --- | --- |
| `response.body()` | Raw response text | Raw response text (never throws) |
| `response.bodyAs<OrderDto>()` | Deserializes JSON | Throws `HttpStatusException` |
| `response.bodyAsOrNull<OrderDto>()` | Deserializes JSON (`null` if empty) | `null` on `404`, otherwise throws |
| `response.bodyAs<String>()` | Raw text unparsed (empty/blank included) | Throws `HttpStatusException` |
| `response.bodyAs<Unit>()` | Ignores body, returns `Unit` | Throws `HttpStatusException` |

Read structured error bodies directly from `HttpStatusException`, or branch on `statusCode()` yourself:

```kotlin
// Option 1: catch HttpStatusException and parse the error body with the client's ObjectMapper
try {
    http.post("/orders") { json(order) }.bodyAs<OrderDto>()
} catch (e: HttpStatusException) {
    // e.message: "HTTP status 422 for POST https://api.example.com/orders" (safe to log: no query/body)
    val error = e.bodyAs<ApiErrorDto>()
}

// Option 2: branch on the status code before deserializing
val response = http.post("/orders") { json(order) }
when (response.statusCode()) {
    201 -> response.bodyAs<OrderDto>()
    422 -> http.objectMapper.readValue<ApiErrorDto>(response.body())
    else -> throw response.toStatusException()
}
```

### Extending the DSL with your own words

`RequestDsl` is a regular class, so repeated headers or conventions become plain Kotlin extensions:

```kotlin
fun RequestDsl.tenant(id: String) = header("X-Tenant", id)
fun RequestDsl.acceptJson() = header("Accept", "application/json")

http.get("/orders") {
    tenant("acme")
    acceptJson()
    queryParam("status", "OPEN")
}
```

---

## 2. Functions Module (`httpkt-functions`)

All typed-client and config examples below come from runnable tests in [`FunctionsUsageExamplesTest.kt`](httpkt-functions/src/test/kotlin/io/github/httpkt/functions/FunctionsUsageExamplesTest.kt).

### Building a typed API client

Create a `RequestConfig` and call `get`, `getOrNull`, `post`, `put`, `patch`, `delete`, `exchange`, or `downloadFile`:

```kotlin
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.httpkt.functions.*

sealed interface CreateCustomerResult {
    data class Created(val customer: Customer) : CreateCustomerResult
    data class AlreadyExists(val problem: Problem) : CreateCustomerResult
}

class CustomerClient(private val config: RequestConfig) {

    fun find(id: String): Customer? =
        config.getOrNull("/customers/{id}", pathParams = mapOf("id" to id))

    fun list(page: Int = 0, size: Int = 20, nameFilter: String? = null): Page<Customer> =
        config.get("/customers", queryParams = mapOf("page" to page, "size" to size, "name" to nameFilter))

    fun create(customer: NewCustomer): CreateCustomerResult = try {
        CreateCustomerResult.Created(config.post("/customers", body = customer))
    } catch (e: HttpClientException) {
        if (e.status != 409) throw e
        CreateCustomerResult.AlreadyExists(config.objectMapper.readValue(e.body))
    }

    fun rename(id: String, name: String): Customer =
        config.patch("/customers/{id}", body = mapOf("name" to name), pathParams = mapOf("id" to id))

    fun remove(id: String): Unit =
        config.delete("/customers/{id}", pathParams = mapOf("id" to id))

    fun createAndLocate(customer: NewCustomer): Pair<Customer, String?> {
        val created = config.exchange<Customer>(Method.POST, "/customers", body = customer)
        return created.body to created.header("Location")
    }
}
```

- Placeholders in `path` (`{id}`) must match `pathParams` keys exactly (`IllegalArgumentException` otherwise) and are URL-encoded automatically.
- `queryParams` skips `null` values and repeats the key for `Iterable` or array values (`mapOf("id" to listOf("c-1", "c-2"))` → `?id=c-1&id=c-2`).
- Non-`2xx` responses throw `HttpClientException` (`4xx`), `HttpServerException` (`5xx`), or `HttpException` (other), all carrying `status` and `body`.

### Deriving configurations & authentication

Because `RequestConfig` is a `data class`, tenant variants, timeouts, and custom mappers are a `copy(...)` away:

```kotlin
val base = RequestConfig(
    url = "https://api.example.com/api/v1",
    headers = mapOf("Accept-Language" to "nb"),
)

val acme = base.copy(headers = base.headers + ("X-Tenant" to "acme"))
val impatient = base.copy(requestTimeout = Duration.ofSeconds(2))
val tolerant = base.copy(
    objectMapper = defaultObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
)

// Cached OAuth token with automatic retry on 401 (token call uses its own config without auth)
val authServer = RequestConfig(url = "https://auth.example.com")
val authenticated = base.copy(
    authorization = Authorization.cachedBearer {
        authServer.post<String>(
            "/oauth/token",
            body = "grant_type=client_credentials",
            contentType = "application/x-www-form-urlencoded",
        )
    },
)

// Any other scheme is a one-line lambda (does not retry on 401)
val apiKeyConfig = base.copy(authorization = { "ApiKey $key" })
```

### Multipart uploads, binary payloads & file downloads

```kotlin
import java.nio.file.Path

// Multipart/form-data upload
val body = multipart {
    field("description", "Monthly report")
    file("document", Path.of("report.pdf"), contentType = "application/pdf")
}
config.post<Unit>("/documents", body = body)

// Download to disk (streams to a temporary .part file and moves into place only on 2xx)
config.downloadFile(
    path = "/documents/{id}",
    destination = Path.of("downloads/report.pdf"),
    pathParams = mapOf("id" to "42"),
)

// In-memory binary download or raw XML/text
val png: ByteArray = config.get("/assets/logo.png")
val xml: String = config.post(
    "/webhooks",
    body = "<event id=\"1\"/>",
    contentType = "application/xml",
    headers = mapOf("Accept" to "application/xml"),
)
```

### Header defaults & precedence

- **`Accept`** defaults to `application/json`, or `*/*` for `ByteArray` results and `downloadFile`. Override it via `headers`.
- **`Content-Type`** is only sent when the request has a body (any `Content-Type` in `headers` is dropped for bodyless calls):
  - Default: `application/octet-stream` for `ByteArray`, `multipart/form-data; boundary=...` for `multipart { }`, otherwise `application/json` (including `String` bodies).
  - Precedence (weakest to strongest): body default → `config.headers` → call `contentType` → call `headers`. A `MultipartBody` always keeps its own type and boundary.
- An `Authorization` entry in `headers` takes precedence over `config.authorization`.

---

## 3. Debug logging

Both modules log each request at `DEBUG` via the JDK's `System.Logger`, keeping zero logging dependencies. Nothing is logged until you enable `DEBUG`:

```text
POST https://api.example.com/orders -> 201 in 48 ms
GET https://api.example.com/orders/42 -> 401 in 12 ms
GET https://api.example.com/orders/42 -> 401, sending again with a new Authorization
GET https://api.example.com/orders/42 -> 200 in 31 ms
GET https://api.example.com/slow failed after 5003 ms: HttpTimeoutException
```

Log lines include only method, URL (scheme/host/port/path), and outcome — **never** query strings, user info, headers, or bodies.

Logger names are `io.github.httpkt.dsl` and `io.github.httpkt.functions`, so their parent **`io.github.httpkt`** covers both:

| Your logging setup | What to do |
| --- | --- |
| **SLF4J 2 + Logback** | Add `org.slf4j:slf4j-jdk-platform-logging`, then set `io.github.httpkt` to `DEBUG` |
| **Log4j 2** | Add `org.apache.logging.log4j:log4j-jpl`, then set `io.github.httpkt` to `DEBUG` |
| **Spring Boot** | Set `logging.level.io.github.httpkt=DEBUG`. Nothing to add: Boot bridges `java.util.logging` to Logback *(checked with the default starter on Boot 3.5 and 4.2)* |
| **Plain JDK (`java.util.logging`)** | Set both `io.github.httpkt.level = FINE` and `java.util.logging.ConsoleHandler.level = FINE` |

Without a bridge, `<logger name="io.github.httpkt" level="DEBUG"/>` in `logback.xml` will not show output because `System.Logger` defaults to `java.util.logging`.

For low-level wire diagnostics from the JDK itself, pass `-Djdk.httpclient.HttpClient.log=requests,errors`.

---

## 4. Good to know

**DSL module**
- Unknown JSON fields fail by default — annotate DTOs with `@JsonIgnoreProperties(ignoreUnknown = true)` or pass a configured `defaultObjectMapper()`.
- No `Accept` header is sent unless you set one via `defaultHeaders` or `header(...)`.
- `contentType(...)` describes the body, so a request without a body sends no `Content-Type` (from 1.0.1).
- `bodyAs` uses the client's `ObjectMapper` for responses returned by `HttpClientDsl` and falls back to the library default for any other `HttpResponse<String>`.

**Functions module**
- A `String` body is sent with `Content-Type: application/json` unless you pass `contentType`.
- For `ByteArray` results, only `204` counts as empty (`getOrNull` returns `null`); an empty `200` returns an empty `ByteArray`.
- The destination directory for `downloadFile` must exist before calling; a failed download leaves any existing file untouched. The exception carries at most the first 64 KB of the error body.
- Response text is decoded with the `charset` of the response's `Content-Type`, and as UTF-8 when there is none (from 1.0.1; earlier always UTF-8).
- The default `HttpClient` is shared across configs — do not `close()` `config.httpClient` unless you supplied your own instance.
- `RequestConfig` keeps the `headers` map you pass in — do not mutate it afterwards.
- Custom multipart boundaries may only contain letters, digits, and `' + _ - .` (1–70 characters).

**Both modules**
- Request paths must be relative to `baseUrl` / `url`: `scheme://...`, `//host...`, and `http:`/`https:` are rejected. A leading `/` is optional (`orders:cancel` and `/orders:cancel` resolve identically).
- Inside `Authorization.cachedBearer`, the token fetch runs while holding a lock so concurrent requests share one refresh — do not dispatch the fetch to another thread that uses the same `Authorization`.
- Each client or config gets its own copy of the default `ObjectMapper`, so reconfiguring it never affects other clients.
- `requestTimeout` must be positive; zero or a negative duration is rejected when the client or config is created.
- On the Java module path the modules are named `io.github.httpkt.dsl` and `io.github.httpkt.functions` (`Automatic-Module-Name`, from 1.0.1).
- The API documentation (KDoc) ships in the `-javadoc` jar from 1.0.1, and your IDE shows it from the `-sources` jar.

---

## License

[Apache License 2.0](LICENSE).
