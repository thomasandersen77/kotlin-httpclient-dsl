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
 * @param baseUrl scheme and host, optionally with a port, a path prefix and a query string that
 * is sent with every request (`https://api.example.com/v1?api-version=2`).
 * @param objectMapper serializes [RequestDsl.json] bodies and deserializes [bodyAs] results for
 * responses from this client. Each client gets its own copy of the default unless you pass one,
 * so reconfiguring it never affects another client.
 * @param defaultHeaders sent with every request. A request replaces one by calling
 * [RequestDsl.header] with the same name. `Content-Type` and `Authorization` are rejected.
 * @param requestTimeout the timeout of each request unless the request sets its own.
 */
class HttpClientDsl(
    val baseUrl: String,
    internal val httpClient: HttpClient,
    internal val authorization: Authorization? = null,
    val objectMapper: ObjectMapper = defaultObjectMapper(),
    defaultHeaders: Map<String, String> = emptyMap(),
    val requestTimeout: Duration = Duration.ofSeconds(30),
) {
    /** A copy, so later changes to the map that was passed in cannot slip past the header check. */
    val defaultHeaders: Map<String, String> = defaultHeaders.toMap()

    private val baseUri: URI = URI.create(baseUrl)

    init {
        require(!baseUri.scheme.isNullOrBlank() && !baseUri.host.isNullOrBlank()) {
            "baseUrl must include scheme and host, got '$baseUrl'"
        }
        this.defaultHeaders.keys.forEach { requireOrdinaryHeader(it, setWith = "defaultHeaders") }
    }

    constructor(
        baseUrl: String,
        connectTimeout: Duration = Duration.ofSeconds(30),
        authorization: Authorization? = null,
        objectMapper: ObjectMapper = defaultObjectMapper(),
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
        val uri = joinUrl(baseUri, UriExpander.expand(relativePath(path, "baseUrl"), *uriVariables))
        return RequestDsl(method, httpClient, authorization, objectMapper, defaultHeaders, requestTimeout)
            .apply(configure)
            .exchange(uri)
    }
}

private val absoluteUrl = Regex("^([A-Za-z][A-Za-z0-9+.-]*:)?//")

/**
 * A path is always relative to the base URL. Written without a leading slash, a first segment
 * with a colon (`orders:cancel`) would be read as a URI scheme, so the slash is added here.
 */
private fun relativePath(path: String, base: String): String {
    require(!absoluteUrl.containsMatchIn(path)) { "path must be relative to $base, got '$path'" }
    return if (path.isEmpty() || path[0] in "/?#") path else "/$path"
}

/**
 * Appends [path] to [base] part by part: the paths are joined with exactly one slash, the query
 * strings are combined, and the fragment of [path] wins. Joining the two as text would put the
 * path inside the base URL's query or fragment.
 */
private fun joinUrl(base: URI, path: URI): URI {
    require(path.scheme == null && path.rawAuthority == null) { "path must be relative to baseUrl, got '$path'" }
    val basePath = base.rawPath.orEmpty()
    val subPath = path.rawPath.orEmpty()
    val joinedPath = when {
        subPath.isEmpty() -> basePath
        subPath.startsWith("/") -> basePath.trimEnd('/') + subPath
        else -> basePath.trimEnd('/') + "/" + subPath
    }
    val query = listOfNotNull(base.rawQuery, path.rawQuery).filter { it.isNotEmpty() }.joinToString("&")
    val fragment = path.rawFragment ?: base.rawFragment
    return URI.create(buildString {
        append(base.scheme).append("://").append(base.rawAuthority)
        append(joinedPath)
        if (query.isNotEmpty()) append('?').append(query)
        if (fragment != null) append('#').append(fragment)
    })
}

/**
 * A response with a status outside `200..299`. [method] and [uri] name the request when the
 * exception comes from [bodyAs]. The message names the method, host and path, but never the
 * query string, the user info or the response [body], so it is safe to log; [uri] has the rest.
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

    /**
     * The error [body] as [T], with the same rules as [HttpResponse.bodyAs]: `String` is the raw
     * text, an empty body is `null` when [T] is nullable, anything else is deserialized from JSON,
     * for example into the API's error type.
     */
    inline fun <reified T> bodyAs(): T = convertBody(body, status, objectMapper)
}

private fun statusMessage(status: Int, method: String?, uri: URI?): String =
    if (method == null || uri == null) "HTTP status $status" else "HTTP status $status for $method ${uri.withoutSecrets()}"

/**
 * What [HttpClientDsl] returns: the JDK response, plus the client's `ObjectMapper` so that
 * [bodyAs] deserializes with the same configuration that serialized the request.
 */
@PublishedApi
internal class MappedHttpResponse(
    private val delegate: HttpResponse<String>,
    val objectMapper: ObjectMapper,
) : HttpResponse<String> by delegate {
    // Interface delegation does not cover these three, and the JDK's toString is the useful one.
    // Equal only to another wrapper of the same response: claiming equality with the raw JDK
    // response could not be symmetric.
    override fun toString(): String = delegate.toString()
    override fun equals(other: Any?): Boolean = other is MappedHttpResponse && delegate == other.delegate
    override fun hashCode(): Int = delegate.hashCode()
}

@PublishedApi
internal fun HttpResponse<String>.objectMapper(): ObjectMapper =
    (this as? MappedHttpResponse)?.objectMapper ?: dslObjectMapper

/**
 * Returns the body of a `2xx` response as [T]: `Unit` ignores the body, `String` returns the raw
 * text unparsed (also when it is empty or blank), and any other type is deserialized from JSON.
 * An empty body gives `null` when [T] is nullable. Throws [HttpStatusException] for any status
 * outside `200..299`.
 */
inline fun <reified T> HttpResponse<String>.bodyAs(): T {
    val status = statusCode()
    val content = body().orEmpty()
    val objectMapper = objectMapper()
    if (status !in 200..299) {
        throw HttpStatusException(status, content, request().method(), request().uri(), objectMapper)
    }
    return convertBody(content, status, objectMapper)
}

@PublishedApi
internal inline fun <reified T> convertBody(content: String, status: Int, objectMapper: ObjectMapper): T {
    if (T::class == Unit::class) {
        return Unit as T
    }
    if (T::class == String::class) {
        return (if (content.isEmpty() && null is T) null else content) as T
    }
    if (content.isBlank()) {
        if (null is T) return null as T
        throw IllegalStateException("Cannot deserialize empty response body (HTTP $status) to ${T::class.simpleName}")
    }
    return objectMapper.readValue(content)
}

/**
 * Like [bodyAs], but a `404` gives `null` instead of an [HttpStatusException], as does an empty
 * `2xx` body. Every other status outside `200..299` still throws.
 */
inline fun <reified T : Any> HttpResponse<String>.bodyAsOrNull(): T? =
    if (statusCode() == 404) null else bodyAs<T?>()
