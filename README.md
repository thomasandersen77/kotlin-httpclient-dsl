# kotlin-httpclient-dsl

A small Kotlin library for calling HTTP APIs. It is built on the JDK's own `java.net.http.HttpClient`
and uses Jackson for JSON. Requires **Java 11** or newer.

The aim is that an outgoing call reads like what it does, and that adding the library costs you nothing else:

- **One dependency to add.** Pick one of the two modules below. They do not depend on each other.
- **Jackson is the only thing it brings along**, besides the Kotlin standard library. There is no
  third-party HTTP client, no logging framework, no annotation processing and no framework integration.
- **It keeps to itself.** No service loading, no logging binding, no framework hooks, and no shared
  configuration you can change by accident: each client or config carries its own settings,
  its own `ObjectMapper` included.

| Module | Style | Package |
| --- | --- | --- |
| `kotlin-httpclient-dsl` | A receiver-lambda DSL: `http.post("/orders") { json(order) }` | `io.httpdsl` |
| `kotlin-httpclient-functions` | Typed functions on a config: `config.get<Order>("/orders/{id}", ...)` | `io.httpdsl.functions` |

Use the DSL when you want to shape each request and look at the response; use the functions when you
mostly want a typed result back in one line.

## Installing

The library is not published to a public repository yet. Build it into your local repository with
`mvn install`, then add **one** of:

```xml
<dependency>
    <groupId>io.httpdsl</groupId>
    <artifactId>kotlin-httpclient-dsl</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

```xml
<dependency>
    <groupId>io.httpdsl</groupId>
    <artifactId>kotlin-httpclient-functions</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### What it adds to your classpath

Both modules have the same runtime dependencies (`mvn dependency:tree -Dscope=runtime`):

| Dependency | Why |
| --- | --- |
| `kotlin-stdlib` (with `org.jetbrains:annotations`) | The library is written in Kotlin |
| `jackson-databind` (with `jackson-core`, `jackson-annotations`) | JSON |
| `jackson-module-kotlin` (with `kotlin-reflect`) | Kotlin data classes as JSON |
| `jackson-datatype-jsr310` | `java.time` types as ISO strings |

If your build already manages Jackson or Kotlin versions (Spring Boot's BOM does), your versions win
and the library follows them; it is built against Jackson 2.18 and Kotlin 2.1.

JSON is the only format with built-in support. Anything else, XML included, goes through as text:
send it with `body(xml, "application/xml")` and read it with `response.body()` (DSL), or pass a
`String` body with `contentType = "application/xml"`, `headers = mapOf("Accept" to "application/xml")`
and ask for a `String` back (functions).
That keeps an XML library off your classpath unless you add one yourself.

---

## 1. DSL Module (`kotlin-httpclient-dsl`)

```kotlin
val http = HttpClientDsl("http://localhost:8080")

val response = http.post("/persons/{id}", 42) {
    requestTimeout = Duration.ofSeconds(20)
    bearerToken(login("user", "password"))
    header("Accept", "application/json")
    queryParam("notify", true)
    json(PersonDto(firstname = "Bill", lastname = "Anderson"))
}

val person = response.bodyAs<PersonDto>()
```

Authorization is a function you supply, so the library does not lock you into one scheme.
Give it once to the client; it is asked for the header value every time a request is sent.
`CachedAuthorization` (or `Authorization.cachedBearer`) fetches once, and after a 401 it fetches again and resends the request once:

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

http.get("/orders")                                       // cached token, refreshed on 401
http.get("/admin") { basicAuth("admin", "secret") }       // replaces it for this request
http.get("/public") { withoutAuthorization() }            // no Authorization header
```

A plain lambda (`authorization = { "Basic ..." }` or `Authorization.basic(...)`) never retries; implement `Authorization`
yourself if you need other refresh rules, for example expiry-based.

Settings that apply to every request go on the client; a request can still replace them:

```kotlin
val http = HttpClientDsl(
    baseUrl = "https://api.example.com",
    defaultHeaders = mapOf("Accept" to "application/json", "X-Tenant" to "acme"),
    requestTimeout = Duration.ofSeconds(5),                                  // default is 30 s
    objectMapper = defaultObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
)

http.get("/orders/export") {
    header("Accept", "text/csv")                 // replaces the default Accept for this request
    requestTimeout = Duration.ofSeconds(60)
}
```

`objectMapper` serializes `json(...)` bodies and deserializes `bodyAs` results for that client. Every client has its own: without the argument it gets a fresh copy of the library's default (Kotlin module, Java time, ISO dates), which is also what `defaultObjectMapper()` returns.

`baseUrl` may carry a path prefix and a query string that is sent with every request: `HttpClientDsl("https://api.example.com/v1?api-version=2").get("/orders")` calls `/v1/orders?api-version=2`.

`json(...)` sets `Content-Type: application/json`, `form("name" to value, ...)` sends an encoded `application/x-www-form-urlencoded` body, and `body(content, contentType)` sets a raw request body; an explicit `contentType(...)` overrides any of these defaults.
`queryParam` and `form` skip `null`, and repeat the name for each element of an `Iterable` or array (`queryParam("id", listOf(1, 2))` gives `?id=1&id=2`).
`header(...)` and `defaultHeaders` reject `Content-Type` and `Authorization` with an `IllegalArgumentException`, so a request never carries two values; use `contentType(...)` and `authorization { }`, `bearerToken(...)` or `basicAuth(...)`.
GET and DELETE requests reject a body with an `IllegalStateException` before anything is sent.

`get`, `post`, `put`, `patch` and `delete` always return `HttpResponse<String>` without throwing on HTTP error statuses.
Calling `response.bodyAs<T>()` deserializes `2xx` responses, returns the raw text for `bodyAs<String>()` (empty or blank text included), returns `Unit` for `bodyAs<Unit>()`, returns `null` for an empty body when `T` is nullable, and throws `HttpStatusException` when the status is outside `200..299`.
`response.bodyAsOrNull<T>()` does the same, except that a `404` gives `null`.
`HttpStatusException` carries `status`, `body`, `method` and `uri`; its message names the request (`HTTP status 422 for POST https://api.example.com/orders`) but never contains the query string or the body, so it is safe to log. `e.bodyAs<ApiError>()` reads the error body with the client's `ObjectMapper`, by the same rules as `response.bodyAs`.

---

## 2. Functions Module (`kotlin-httpclient-functions`)

Create a `RequestConfig` and build typed domain clients (such as the `FooHttpClient` example in the tests) using `get`, `getOrNull`, `post`, `put`, `patch`, `delete`, `exchange`, and `downloadFile`:

```kotlin
val config = RequestConfig(
    url = "https://api.example.com",
    authorization = Authorization.cachedBearer { tokenService.getToken() },
)

class FooHttpClient(private val config: RequestConfig) {
    fun getFoo(name: String): Foo =
        config.get("/api/foo", queryParams = mapOf("name" to name))

    fun getFooOrNull(name: String): Foo? =
        config.getOrNull("/api/foo", queryParams = mapOf("name" to name))

    fun updateFoo(id: String, body: Foo): Foo =
        config.put("/api/foo/{id}", body = body, pathParams = mapOf("id" to id))
}
```

- **Authorization:** one `authorization` on the config, asked for the header value on every request. `Authorization.basic(user, password)` and `Authorization.bearer(token)` are static, `Authorization.bearer { token() }` asks for a token each time, and `Authorization.cachedBearer { fetchToken() }` fetches once and, after a `401`, fetches again and resends the request once. Any other scheme is a lambda: `authorization = { "ApiKey $key" }`. An `Authorization` entry in `headers` takes its place.
- **Path placeholders:** `{name}` in a path is replaced with the URL-encoded value from `pathParams`. A placeholder without a value, or a value without a placeholder, is an `IllegalArgumentException`.
- **Status and headers:** `config.exchange<T>(Method.POST, path, body)` returns `HttpResult(status, headers, body)`; `result.header("Location")` reads a response header.
- **Headers sent:** `Accept: application/json` (`*/*` for `ByteArray` results and `downloadFile`), and `Content-Type` only when the request has a body (a `Content-Type` in `headers` is left out for a request without one): `application/octet-stream` for a `ByteArray`, the multipart type for `multipart { }`, otherwise `application/json`. A `Content-Type` in the config's `headers` replaces that default, a call's `contentType` replaces both, and the call's own `headers` come last. A multipart body always keeps its own type and boundary.
- **Downloads:** `downloadFile` writes to the destination only after a `2xx`; a failed download leaves an existing file as it was. The destination directory must exist.
- **Empty responses:** `getOrNull` gives `null` for `404`, and for an empty body. For `ByteArray` only a `204` counts as empty; an empty `200` is an empty file.
- The top-level `httpGet`, `httpPost`, ... helpers are deprecated in favour of the extension functions.

- **Raw text, binary, and multipart uploads:** Pass `contentType = "text/plain"` or `"application/x-www-form-urlencoded"` for raw `String` bodies, `ByteArray` for binary payloads/downloads (`config.get<ByteArray>(...)` / `config.downloadFile(path, destination)`), or `multipart { field(...); file(...) }` for `multipart/form-data`.
- **Error handling:** Responses outside `200..299` throw `HttpException` (`HttpClientException` for `4xx`, `HttpServerException` for `5xx`), retaining `status` and `body`.

---

## 3. More usage examples

The snippets below come from runnable tests, which also show what goes over the wire:
[`DslUsageExamplesTest.kt`](kotlin-httpclient-dsl/src/test/kotlin/io/httpdsl/DslUsageExamplesTest.kt) and
[`FunctionsUsageExamplesTest.kt`](kotlin-httpclient-functions/src/test/kotlin/io/httpdsl/functions/FunctionsUsageExamplesTest.kt).

### DSL: a small typed client

Keep `HttpResponse` inside one class per remote API and give callers domain types:

```kotlin
class OrderClient(private val http: HttpClientDsl) {

    fun find(id: Long): OrderDto? =
        http.get("/orders/{id}", id).bodyAsOrNull()                 // {id} is URL-encoded, 404 gives null

    fun search(status: String, page: Int = 0): List<OrderDto> =
        http.get("/orders") {
            queryParam("status", status)
            queryParam("page", page)
        }.bodyAs()

    fun create(order: NewOrderDto): URI {
        val response = http.post("/orders") { json(order) }
        response.bodyAs<Unit>()                                     // throws HttpStatusException unless 2xx
        return URI.create(response.headers().firstValue("Location").orElseThrow())
    }

    fun cancel(id: Long) {
        http.delete("/orders/{id}", id).bodyAs<Unit>()              // any 2xx is fine, otherwise throw
    }
}
```

A base URL may carry a path prefix: `HttpClientDsl("https://api.example.com/api/v1/").get("orders/{id}", 7)`
calls `/api/v1/orders/7`.

### DSL: your own DSL words

`RequestDsl` is an ordinary class, so headers you repeat become extension functions:

```kotlin
fun RequestDsl.tenant(id: String) = header("X-Tenant", id)
fun RequestDsl.acceptJson() = header("Accept", "application/json")

http.get("/orders") {
    tenant("acme")
    acceptJson()
    queryParam("status", "OPEN")
}
```

### DSL: text, JSON and error bodies

```kotlin
http.get("/health").body()                       // "UP" for any status
http.get("/health").bodyAs<String>()             // "UP", unparsed; throws HttpStatusException outside 2xx

// An error body as a domain error: read it from the exception ...
try {
    http.post("/orders") { json(order) }.bodyAs<OrderDto>()
} catch (e: HttpStatusException) {
    val error = e.bodyAs<ApiErrorDto>()          // e.message: "HTTP status 422 for POST https://api.example.com/orders"
}

// ... or branch on the status before deserializing, with the client's ObjectMapper
val response = http.post("/orders") { json(order) }
when (response.statusCode()) {
    201 -> response.bodyAs<OrderDto>()
    422 -> http.objectMapper.readValue<ApiErrorDto>(response.body())
    else -> throw HttpStatusException(response.statusCode(), response.body())
}
```

By default `bodyAs` fails on JSON fields your DTO does not have. Either annotate the DTO with
`@JsonIgnoreProperties(ignoreUnknown = true)`, or give the client an `ObjectMapper` that ignores them:

```kotlin
val http = HttpClientDsl(
    baseUrl = "https://api.example.com",
    objectMapper = defaultObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
)
```

### DSL: forms, repeated query parameters and timeouts

```kotlin
http.post("/oauth/token") {
    withoutAuthorization()
    form("grant_type" to "client_credentials", "scope" to "orders:read orders:write")
}                                     // body: grant_type=client_credentials&scope=orders%3Aread+orders%3Awrite

http.get("/orders") { queryParam("id", listOf(1, 2, 3)) }                     // ?id=1&id=2&id=3

http.get("/slow") { requestTimeout = Duration.ofMillis(100) }                 // throws HttpTimeoutException
```

### DSL: your own Authorization, for example expiry-based

```kotlin
class ExpiringTokenAuthorization(
    private val now: () -> Instant,
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
    override fun onUnauthorized(rejectedHeaderValue: String): Boolean {      // also refresh, and resend once, after a 401
        current = null
        return true
    }
}

val http = HttpClientDsl(
    baseUrl = "https://api.example.com",
    authorization = ExpiringTokenAuthorization(now = Instant::now) { tokenService.issue() },
)
```

### Functions: a typed client with paging and expected errors

```kotlin
sealed interface CreateCustomerResult {
    data class Created(val customer: Customer) : CreateCustomerResult
    data class AlreadyExists(val problem: Problem) : CreateCustomerResult
}

class CustomerClient(private val config: RequestConfig) {

    fun find(id: String): Customer? =
        config.getOrNull("/customers/{id}", pathParams = mapOf("id" to id))      // {id} is URL-encoded

    fun list(page: Int = 0, size: Int = 20, nameFilter: String? = null): Page<Customer> =
        config.get("/customers", queryParams = mapOf("page" to page, "size" to size, "name" to nameFilter))  // null is skipped

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
        val created = config.exchange<Customer>(Method.POST, "/customers", body = customer)   // status, headers, body
        return created.body to created.header("Location")
    }
}
```

### Functions: deriving configurations

`RequestConfig` is a data class, so variants are a `copy` away:

```kotlin
val base = RequestConfig(url = "https://api.example.com/api/v1", headers = mapOf("Accept-Language" to "nb"))

val acme = base.copy(headers = base.headers + ("X-Tenant" to "acme"))                 // per tenant
val tolerant = base.copy(                                                              // ignore unknown JSON fields
    objectMapper = defaultObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
)
val impatient = base.copy(requestTimeout = Duration.ofSeconds(2))

// The token request uses its own config without authorization.
val authServer = RequestConfig(url = "https://auth.example.com")
val authenticated = base.copy(
    authorization = Authorization.cachedBearer {                                       // fetched once, again after a 401
        authServer.post<String>("/oauth/token", body = "grant_type=client_credentials",
            contentType = "application/x-www-form-urlencoded")
    },
)
```

A list (or array) in `queryParams` repeats the parameter:
`config.get<Page<Customer>>("/customers", queryParams = mapOf("id" to listOf("c-1", "c-2"), "size" to 2))` calls `/customers?id=c-1&id=c-2&size=2`.

---

## 4. Good to know

Behaviour that is easy to trip over:

**DSL module**
- `bodyAs` fails on unknown JSON fields unless the DTO has `@JsonIgnoreProperties(ignoreUnknown = true)` or the client has an `ObjectMapper` that ignores them.
- No `Accept` header is sent unless you set one, with `defaultHeaders` or `header(...)`.
- `bodyAs` uses the client's `ObjectMapper` only for responses returned by `HttpClientDsl`; for any other `HttpResponse<String>` it uses the default.
- There is no multipart upload or file download in this module; the functions module has both.

**Functions module**
- `Accept: application/json` is sent unless you ask for a `ByteArray` or use `downloadFile`, which send `Accept: */*`. Override it in `headers`.
- A `String` body is sent as `application/json` unless you pass `contentType`.
- `Authorization.bearer { ... }` and a plain lambda never retry; only `cachedBearer` (or your own `onUnauthorized`) resends after a `401`.
- The default `HttpClient` is shared between configs. Do not `close()` `config.httpClient` unless you passed that client in yourself.
- `RequestConfig` keeps the `headers` map you give it; do not change that map afterwards.
- A `path` must be relative to the config's `url` (or the client's `baseUrl` in the DSL); an absolute URL is rejected. A leading slash is optional, so `orders:cancel` and `/orders:cancel` are the same path.
- A custom multipart `boundary` may only use letters, digits and `' + _ - .`
- There is no `form(...)` helper here; pass an encoded `String` body with `contentType = "application/x-www-form-urlencoded"`.

**Both modules**
- The token fetch inside `cachedBearer` runs while a lock is held, so concurrent requests wait for one token instead of each fetching one. Do not hand the fetch to another thread that uses the same authorization.
- The two modules have their own exception types: `HttpStatusException` (DSL) and `HttpException` with `HttpClientException` / `HttpServerException` (functions).
