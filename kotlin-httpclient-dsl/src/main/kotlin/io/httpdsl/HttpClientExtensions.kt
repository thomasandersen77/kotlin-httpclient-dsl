package io.httpdsl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.Duration

/**
 * @param authorization asked for the full `Authorization` header value each time a request
 * is sent. A lambda works for static or always-fresh credentials; [CachedAuthorization]
 * fetches once and refreshes after a 401. A request can replace it with
 * [RequestDsl.authorization] or drop it with [RequestDsl.withoutAuthorization].
 * @param objectMapper serializes [RequestDsl.json] bodies and deserializes [bodyAs] results for
 * responses from this client. Start from [defaultObjectMapper] to adjust the default.
 * @param defaultHeaders sent with every request. A request replaces one by calling
 * [RequestDsl.header] with the same name. `Content-Type` and `Authorization` are rejected.
 * @param requestTimeout the timeout of each request unless the request sets its own.
 */
class HttpClientDsl(
    val baseUrl: String,
    internal val httpClient: HttpClient,
    internal val authorization: Authorization? = null,
    val objectMapper: ObjectMapper = dslObjectMapper,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val requestTimeout: Duration = Duration.ofSeconds(30),
) {
    init {
        defaultHeaders.keys.forEach { requireOrdinaryHeader(it, setWith = "defaultHeaders") }
    }

    constructor(
        baseUrl: String,
        connectTimeout: Duration = Duration.ofSeconds(30),
        authorization: Authorization? = null,
        objectMapper: ObjectMapper = dslObjectMapper,
        defaultHeaders: Map<String, String> = emptyMap(),
        requestTimeout: Duration = Duration.ofSeconds(30),
    ) : this(
        baseUrl = baseUrl,
        httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
        authorization = authorization,
        objectMapper = objectMapper,
        defaultHeaders = defaultHeaders,
        requestTimeout = requestTimeout,
    )

    fun get(path: String, vararg uriVariables: Any, configure: RequestDsl.() -> Unit = {}): HttpResponse<String> =
        send(HttpMethod.GET, path, uriVariables, configure)

    fun post(path: String, vararg uriVariables: Any, configure: RequestDsl.() -> Unit = {}): HttpResponse<String> =
        send(HttpMethod.POST, path, uriVariables, configure)

    fun put(path: String, vararg uriVariables: Any, configure: RequestDsl.() -> Unit = {}): HttpResponse<String> =
        send(HttpMethod.PUT, path, uriVariables, configure)

    fun patch(path: String, vararg uriVariables: Any, configure: RequestDsl.() -> Unit = {}): HttpResponse<String> =
        send(HttpMethod.PATCH, path, uriVariables, configure)

    fun delete(path: String, vararg uriVariables: Any, configure: RequestDsl.() -> Unit = {}): HttpResponse<String> =
        send(HttpMethod.DELETE, path, uriVariables, configure)

    private fun send(
        method: HttpMethod,
        path: String,
        uriVariables: Array<out Any>,
        configure: RequestDsl.() -> Unit,
    ): HttpResponse<String> {
        val expandedPath = UriExpander.expand(path, *uriVariables).toString()
        val uri = URI.create(joinUrl(baseUrl, expandedPath))
        return RequestDsl(method, httpClient, authorization, objectMapper, defaultHeaders, requestTimeout)
            .apply(configure)
            .exchange(uri)
    }
}

private fun joinUrl(baseUrl: String, expandedPath: String): String {
    if (expandedPath.isEmpty()) return baseUrl
    val normalizedBase = baseUrl.trimEnd('/')
    val normalizedPath = if (expandedPath.startsWith("/") || expandedPath.startsWith("?")) {
        expandedPath
    } else {
        "/$expandedPath"
    }
    return "$normalizedBase$normalizedPath"
}

/**
 * A response with a status outside `200..299`. [method] and [uri] name the request when the
 * exception comes from [bodyAs]; the message contains them but never the response [body].
 */
class HttpStatusException @PublishedApi internal constructor(
    val status: Int,
    val body: String,
    val method: String?,
    val uri: URI?,
    @PublishedApi internal val objectMapper: ObjectMapper,
) : RuntimeException(statusMessage(status, method, uri)) {

    constructor(status: Int, body: String, method: String? = null, uri: URI? = null) :
        this(status, body, method, uri, dslObjectMapper)

    /** Deserializes the error [body] from JSON, for example into the API's error type. */
    inline fun <reified T> bodyAs(): T = objectMapper.readValue(body)
}

private fun statusMessage(status: Int, method: String?, uri: URI?): String =
    if (method == null || uri == null) "HTTP status $status" else "HTTP status $status for $method $uri"

/**
 * What [HttpClientDsl] returns: the JDK response, plus the client's `ObjectMapper` so that
 * [bodyAs] deserializes with the same configuration that serialized the request.
 */
@PublishedApi
internal class MappedHttpResponse(
    delegate: HttpResponse<String>,
    val objectMapper: ObjectMapper,
) : HttpResponse<String> by delegate

@PublishedApi
internal fun HttpResponse<String>.objectMapper(): ObjectMapper =
    (this as? MappedHttpResponse)?.objectMapper ?: dslObjectMapper

/**
 * Returns the body of a `2xx` response as [T]: `Unit` ignores the body, `String` returns the raw
 * text unparsed, and any other type is deserialized from JSON. An empty body gives `null` when
 * [T] is nullable. Throws [HttpStatusException] for any status outside `200..299`.
 */
inline fun <reified T> HttpResponse<String>.bodyAs(): T {
    val status = statusCode()
    val content = body().orEmpty()
    val objectMapper = objectMapper()
    if (status !in 200..299) {
        throw HttpStatusException(status, content, request().method(), request().uri(), objectMapper)
    }
    if (T::class == Unit::class) {
        return Unit as T
    }
    if (content.isBlank()) {
        if (null is T) return null as T
        throw IllegalStateException("Cannot deserialize empty response body (HTTP $status) to ${T::class.simpleName}")
    }
    if (T::class == String::class) {
        return content as T
    }
    return objectMapper.readValue(content)
}

/**
 * Like [bodyAs], but a `404` gives `null` instead of an [HttpStatusException], as does an empty
 * `2xx` body. Every other status outside `200..299` still throws.
 */
inline fun <reified T : Any> HttpResponse<String>.bodyAsOrNull(): T? =
    if (statusCode() == 404) null else bodyAs<T?>()
