package io.httpdsl.functions

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

val defaultHttpClient: HttpClient = HttpClient.newBuilder()
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
 * @param url scheme, host and optionally port, path prefix and query (`https://api.example.com:8443/v1`).
 * @param authorization asked for the `Authorization` header value each time a request is sent;
 * see [Authorization]. A `headers` entry named `Authorization` takes its place.
 * @param headers sent with every request; a request's own `headers` replace entries with the same name.
 */
data class RequestConfig(
    val url: String,
    val authorization: Authorization? = null,
    val headers: Map<String, String> = emptyMap(),
    val requestTimeout: Duration = Duration.ofSeconds(30),
    val httpClient: HttpClient = defaultHttpClient,
    val objectMapper: ObjectMapper = mapper,
) {
    internal fun resolveUri(path: String, queryParams: Map<String, Any?> = emptyMap()): URI {
        val baseUri = URI.create(url)
        require(!baseUri.scheme.isNullOrBlank() && !baseUri.host.isNullOrBlank()) {
            "RequestConfig.url must include scheme and host, got '$url'"
        }
        val pathUri = URI.create(path)
        val basePath = baseUri.rawPath.orEmpty().trimEnd('/')
        val subPath = pathUri.rawPath.orEmpty().let {
            if (it.isEmpty() || it.startsWith("/")) it else "/$it"
        }
        val combinedPath = "$basePath$subPath"

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
    return pathPlaceholder.replace(path) { encodePathSegment(pathParams.getValue(it.groupValues[1]).toString()) }
}

fun encodePathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
        .replace("+", "%20")
        .replace("%7E", "~")

internal fun encodeQueryComponent(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

