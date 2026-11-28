package io.github.httpkt.functions

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Base exception for HTTP error responses (4xx and 5xx).
 */
open class HttpException(
    val status: Int,
    val body: String,
    message: String,
) : RuntimeException(message)

/**
 * Client faults - HTTP 400..499
 */
class HttpClientException(
    status: Int,
    body: String,
) : HttpException(status, body, "HTTP client error $status")

/**
 * Server faults - HTTP 500..599
 */
class HttpServerException(
    status: Int,
    body: String,
) : HttpException(status, body, "HTTP server error $status")

/** Shared by every [RequestConfig] that does not bring its own client, and therefore not public. */
internal val defaultHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(30))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

@PublishedApi
internal val mapper: ObjectMapper = jacksonObjectMapper()
    .registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

/**
 * A new `ObjectMapper` configured like the one [RequestConfig] uses by default (Kotlin module,
 * Java time, ISO dates). Adjust the copy and pass it as `RequestConfig(objectMapper = ...)`;
 * the shared default is never changed.
 */
fun defaultObjectMapper(): ObjectMapper = mapper.copy()

/**
 * Everything the requests to one API share. The calls are extension functions on it: [get],
 * [getOrNull], [post], [put], [patch], [delete], [exchange] and [downloadFile]. It is immutable
 * and safe to share between threads; use `copy()` for a variant, such as a longer timeout.
 *
 * ```
 * val api = RequestConfig("https://api.example.com/v1", authorization = Authorization.bearer(token))
 * val created: Order = api.post("/orders", body = NewOrder(customerId = 7))
 * ```
 *
 * @param url scheme, host and optionally port, path prefix and query (`https://api.example.com:8443/v1`).
 * @param authorization asked for the `Authorization` header value each time a request is sent;
 * see [Authorization]. A `headers` entry named `Authorization` takes its place.
 * @param headers sent with every request; a request's own `headers` replace entries with the same name.
 * @param httpClient defaults to a client shared with other configs. Do not `close()` the default;
 * pass your own client if you need to control its lifetime.
 * @param objectMapper each config gets its own copy of the default unless you pass one, so
 * reconfiguring it never affects another config. Configs made with `copy()` share their mapper.
 */
data class RequestConfig(
    val url: String,
    val authorization: Authorization? = null,
    val headers: Map<String, String> = emptyMap(),
    val requestTimeout: Duration = Duration.ofSeconds(30),
    val httpClient: HttpClient = defaultHttpClient,
    val objectMapper: ObjectMapper = defaultObjectMapper(),
) {
    init {
        require(!requestTimeout.isZero && !requestTimeout.isNegative) {
            "requestTimeout must be positive, got $requestTimeout"
        }
    }

    private val baseUri: URI by lazy {
        val parsed = URI.create(url)
        require(!parsed.scheme.isNullOrBlank() && !parsed.host.isNullOrBlank()) {
            "RequestConfig.url must include scheme and host, got '$url'"
        }
        parsed
    }

    /** Header values, credentials and the url's user info and query are left out, so the config is safe to log. */
    override fun toString(): String =
        "RequestConfig(url=${urlWithoutSecrets()}, authorization=${if (authorization == null) "none" else "set"}, " +
            "headers=${headers.keys}, requestTimeout=$requestTimeout)"

    private fun urlWithoutSecrets(): String = runCatching {
        val uri = URI.create(url)
        buildString {
            if (uri.scheme != null) append(uri.scheme).append("://")
            if (uri.host != null) append(uri.host)
            if (uri.port != -1) append(':').append(uri.port)
            append(uri.rawPath.orEmpty())
        }
    }.getOrDefault("<invalid url>")

    internal fun resolveUri(path: String, queryParams: Map<String, Any?> = emptyMap()): URI {
        val baseUri = this.baseUri
        val pathUri = URI.create(relativePath(path, "RequestConfig.url"))
        val basePath = baseUri.rawPath.orEmpty()
        val subPath = pathUri.rawPath.orEmpty()
        val combinedPath = when {
            subPath.isEmpty() -> basePath
            subPath.startsWith("/") -> basePath.trimEnd('/') + subPath
            else -> basePath.trimEnd('/') + "/" + subPath
        }

        val encodedParams = queryParams.entries
            .flatMap { (key, value) -> queryValuesOf(value).map { key to it } }
            .joinToString("&") { (key, value) -> "${encodeQueryComponent(key)}=${encodeQueryComponent(value)}" }
        val rawQueries = listOfNotNull(
            baseUri.rawQuery?.takeIf { it.isNotEmpty() },
            pathUri.rawQuery?.takeIf { it.isNotEmpty() },
            encodedParams.takeIf { it.isNotEmpty() },
        )
        val combinedQuery = if (rawQueries.isEmpty()) "" else "?${rawQueries.joinToString("&")}"
        val rawFragment = pathUri.rawFragment ?: baseUri.rawFragment
        val fragmentSuffix = rawFragment?.let { "#$it" }.orEmpty()

        val portSuffix = if (baseUri.port != -1) ":${baseUri.port}" else ""
        val userInfoPrefix = baseUri.rawUserInfo?.let { "$it@" }.orEmpty()
        return URI.create("${baseUri.scheme}://$userInfoPrefix${baseUri.host}$portSuffix$combinedPath$combinedQuery$fragmentSuffix")
    }
}

private val absoluteUrl = Regex("^(([A-Za-z][A-Za-z0-9+.-]*:)?//|https?:)", RegexOption.IGNORE_CASE)

/**
 * A path is always relative to the base URL. Written without a leading slash, a first segment
 * with a colon (`orders:cancel`) would be read as a URI scheme, so the slash is added here.
 * Rejected as absolute: `scheme://...`, `//host...`, and anything starting with `http:` or
 * `https:`. Any other `word:rest` is taken as a path segment.
 */
private fun relativePath(path: String, base: String): String {
    require(!absoluteUrl.containsMatchIn(path)) { "path must be relative to $base, got '$path'" }
    return if (path.isEmpty() || path[0] in "/?#") path else "/$path"
}

/** Null is skipped; an `Iterable` or any array gives one value per non-null element (`id=1&id=2`). */
private fun queryValuesOf(value: Any?): List<String> = when (value) {
    null -> emptyList()
    is Iterable<*> -> value.filterNotNull().map { it.toString() }
    is Array<*> -> value.filterNotNull().map { it.toString() }
    else -> if (value.javaClass.isArray) {
        (0 until java.lang.reflect.Array.getLength(value)).map { java.lang.reflect.Array.get(value, it).toString() }
    } else {
        listOf(value.toString())
    }
}

private val pathPlaceholder = Regex("\\{([^{}/]+)\\}")

/**
 * Replaces each `{name}` in [path] with the URL-encoded value from [pathParams]. Every
 * placeholder needs a value and every value needs a placeholder, so a typo fails here
 * instead of producing a wrong URL.
 */
internal fun expandPath(path: String, pathParams: Map<String, Any>): String {
    val names = pathPlaceholder.findAll(path).map { it.groupValues[1] }.toSet()
    require(names == pathParams.keys) {
        "Path '$path' has placeholders $names but pathParams has ${pathParams.keys}"
    }
    val expanded = pathPlaceholder.replace(path) { encodePathSegment(pathParams.getValue(it.groupValues[1]).toString()) }
    require('{' !in expanded && '}' !in expanded) { "Invalid path template '$path'" }
    return expanded
}

/**
 * URL-encodes [value] for use as one path segment: a space becomes `%20` and a slash `%2F`.
 * `pathParams` already does this; it is for a path you assemble yourself.
 */
fun encodePathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")
        .replace("%7E", "~")

internal fun encodeQueryComponent(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

