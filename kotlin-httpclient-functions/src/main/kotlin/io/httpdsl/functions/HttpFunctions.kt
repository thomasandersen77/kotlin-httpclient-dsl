package io.httpdsl.functions

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.httpdsl.functions.Method.DELETE
import io.httpdsl.functions.Method.GET
import io.httpdsl.functions.Method.PATCH
import io.httpdsl.functions.Method.POST
import io.httpdsl.functions.Method.PUT
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

enum class Method {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE,
}

/** A successful response with its metadata, for when the body alone is not enough. */
data class HttpResult<T>(
    val status: Int,
    val headers: HttpHeaders,
    val body: T,
) {
    /** The first value of the response header [name] (case-insensitive), or `null`. */
    fun header(name: String): String? = headers.firstValue(name).orElse(null)
}

/*
    Idiomatic extension functions on RequestConfig.

    `path` may contain `{name}` placeholders; their URL-encoded values come from `pathParams`.

    `contentType` defaults to the body: `application/octet-stream` for a ByteArray, the multipart
    type with its boundary for a MultipartBody, and `application/json` for everything else,
    a String included.
*/
inline fun <reified T> RequestConfig.get(
    path: String,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    pathParams: Map<String, Any> = emptyMap(),
): T = exchange<T>(GET, path, queryParams = queryParams, headers = headers, pathParams = pathParams).body

inline fun <reified T : Any> RequestConfig.getOrNull(
    path: String,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    pathParams: Map<String, Any> = emptyMap(),
): T? = try {
    get<T?>(path = path, queryParams = queryParams, headers = headers, pathParams = pathParams)
} catch (e: HttpClientException) {
    if (e.status == 404) null else throw e
}

inline fun <reified T> RequestConfig.post(
    path: String,
    body: Any? = null,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    contentType: String? = null,
    pathParams: Map<String, Any> = emptyMap(),
): T = exchange<T>(POST, path, body, queryParams, headers, contentType, pathParams).body

inline fun <reified T> RequestConfig.put(
    path: String,
    body: Any? = null,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    contentType: String? = null,
    pathParams: Map<String, Any> = emptyMap(),
): T = exchange<T>(PUT, path, body, queryParams, headers, contentType, pathParams).body

inline fun <reified T> RequestConfig.patch(
    path: String,
    body: Any? = null,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    contentType: String? = null,
    pathParams: Map<String, Any> = emptyMap(),
): T = exchange<T>(PATCH, path, body, queryParams, headers, contentType, pathParams).body

inline fun <reified T> RequestConfig.delete(
    path: String,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    pathParams: Map<String, Any> = emptyMap(),
): T = exchange<T>(DELETE, path, queryParams = queryParams, headers = headers, pathParams = pathParams).body

/**
 * Like [get], [post] and the others, but also returns the status and the response headers
 * (`Location`, `ETag`, ...). Statuses outside `200..299` throw as usual. A [body] on GET or
 * DELETE is ignored.
 */
inline fun <reified T> RequestConfig.exchange(
    method: Method,
    path: String,
    body: Any? = null,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    contentType: String? = null,
    pathParams: Map<String, Any> = emptyMap(),
): HttpResult<T> {
    val accept = if (T::class == ByteArray::class) "*/*" else "application/json"
    val response = send(method, body, path, queryParams, headers, contentType, pathParams, accept)
    return HttpResult(response.statusCode(), response.headers(), response.bodyAs(objectMapper))
}

fun RequestConfig.downloadFile(
    path: String,
    destination: Path,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    pathParams: Map<String, Any> = emptyMap(),
): Path {
    // Download next to the destination and move it into place only after a 2xx, so an error
    // response never replaces an existing file or is left behind as the "downloaded" file.
    val target = destination.toAbsolutePath()
    val partial = Files.createTempFile(target.parent, "${target.fileName}.", ".part")
    try {
        val response = send(
            GET, body = null, path, queryParams, headers, contentType = null, pathParams, accept = "*/*",
            HttpResponse.BodyHandlers.ofFile(partial),
        )
        val status = response.statusCode()
        if (status !in 200..299) {
            throw statusException(status, runCatching { Files.readString(partial, StandardCharsets.UTF_8) }.getOrDefault(""))
        }
        return Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(partial)
    }
}

/*
    Top-level helper functions. Deprecated: they duplicate the extensions above, and they take
    `body` before `path`, so swapped arguments compile when the body is a String.
*/
@Deprecated("Use the extension function.", ReplaceWith("config.get<T>(path, queryParams, headers)"))
inline fun <reified T> httpGet(
    config: RequestConfig,
    path: String,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
): T = config.get(path = path, queryParams = queryParams, headers = headers)

@Deprecated("Use the extension function.", ReplaceWith("config.getOrNull<T>(path, queryParams, headers)"))
inline fun <reified T : Any> httpGetOrNull(
    config: RequestConfig,
    path: String,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
): T? = config.getOrNull(path = path, queryParams = queryParams, headers = headers)

@Deprecated(
    "Use the extension function; note that it takes path before body.",
    ReplaceWith("config.post<T>(path, body, queryParams, headers, contentType)"),
)
inline fun <reified T> httpPost(
    config: RequestConfig,
    body: Any?,
    path: String,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    contentType: String = "application/json",
): T = config.post(path = path, body = body, queryParams = queryParams, headers = headers, contentType = contentType)

@Deprecated(
    "Use the extension function; note that it takes path before body.",
    ReplaceWith("config.put<T>(path, body, queryParams, headers, contentType)"),
)
inline fun <reified T> httpPut(
    config: RequestConfig,
    body: Any?,
    path: String,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    contentType: String = "application/json",
): T = config.put(path = path, body = body, queryParams = queryParams, headers = headers, contentType = contentType)

@Deprecated(
    "Use the extension function; note that it takes path before body.",
    ReplaceWith("config.patch<T>(path, body, queryParams, headers, contentType)"),
)
inline fun <reified T> httpPatch(
    config: RequestConfig,
    body: Any?,
    path: String,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    contentType: String = "application/json",
): T = config.patch(path = path, body = body, queryParams = queryParams, headers = headers, contentType = contentType)

@Deprecated("Use the extension function.", ReplaceWith("config.delete<T>(path, queryParams, headers)"))
inline fun <reified T> httpDelete(
    config: RequestConfig,
    path: String,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
): T = config.delete(path = path, queryParams = queryParams, headers = headers)

/*
    Build and Execute the Request
*/
internal fun RequestConfig.buildRequest(
    method: Method,
    body: Any?,
    path: String,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    contentType: String? = null,
    pathParams: Map<String, Any> = emptyMap(),
    accept: String = "application/json",
): HttpRequest {
    // The publisher and the Content-Type that describes it; null when the request has no body.
    val content: Pair<HttpRequest.BodyPublisher, String>? = when (method) {
        GET, DELETE -> null
        POST, PUT, PATCH -> when (body) {
            null -> null
            is MultipartBody -> HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()) to body.contentTypeHeader
            is ByteArray -> HttpRequest.BodyPublishers.ofByteArray(body) to (contentType ?: "application/octet-stream")
            is String -> HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8) to (contentType ?: "application/json")
            else -> HttpRequest.BodyPublishers.ofString(body.asJson(objectMapper), StandardCharsets.UTF_8) to
                (contentType ?: "application/json")
        }
    }

    val builder = HttpRequest
        .newBuilder(resolveUri(expandPath(path, pathParams), queryParams))
        .timeout(requestTimeout)
        .method(method.name, content?.first ?: HttpRequest.BodyPublishers.noBody())
        .setHeader("Accept", accept)

    if (content != null) builder.setHeader("Content-Type", content.second)
    if (!hasAuthorizationHeader(headers)) authorization?.let { builder.setHeader("Authorization", it.headerValue()) }
    this.headers.forEach { (name, value) -> builder.setHeader(name, value) }
    headers.forEach { (name, value) -> builder.setHeader(name, value) }
    // A multipart body is unreadable without its own boundary, so no header may replace this one.
    if (content != null && body is MultipartBody) builder.setHeader("Content-Type", body.contentTypeHeader)

    return builder.build()
}

/** An explicit `Authorization` header, on the config or on the request, takes the place of [RequestConfig.authorization]. */
private fun RequestConfig.hasAuthorizationHeader(requestHeaders: Map<String, String>): Boolean =
    (headers.keys + requestHeaders.keys).any { it.equals("Authorization", ignoreCase = true) }

/**
 * Sends the request. After a 401, [RequestConfig.authorization] is told which header value was
 * rejected, and if it answers `true` the request is rebuilt and sent exactly once more.
 */
@PublishedApi
internal fun RequestConfig.send(
    method: Method,
    body: Any?,
    path: String,
    queryParams: Map<String, Any?>,
    headers: Map<String, String>,
    contentType: String?,
    pathParams: Map<String, Any>,
    accept: String,
): HttpResponse<ByteArray> =
    send(method, body, path, queryParams, headers, contentType, pathParams, accept, HttpResponse.BodyHandlers.ofByteArray())

internal fun <B> RequestConfig.send(
    method: Method,
    body: Any?,
    path: String,
    queryParams: Map<String, Any?>,
    headers: Map<String, String>,
    contentType: String?,
    pathParams: Map<String, Any>,
    accept: String,
    bodyHandler: HttpResponse.BodyHandler<B>,
): HttpResponse<B> {
    fun attempt(): Pair<HttpRequest, HttpResponse<B>> {
        val request = buildRequest(method, body, path, queryParams, headers, contentType, pathParams, accept)
        return request to httpClient.send(request, bodyHandler)
    }

    val (request, response) = attempt()
    val auth = authorization?.takeUnless { hasAuthorizationHeader(headers) } ?: return response
    if (response.statusCode() != 401) return response
    val rejected = request.headers().firstValue("Authorization").orElse(null) ?: return response
    return if (auth.onUnauthorized(rejected)) attempt().second else response
}

@PublishedApi
internal inline fun <reified T> HttpResponse<ByteArray>.bodyAs(objectMapper: ObjectMapper): T {
    val status = statusCode()
    if (status !in 200..299) {
        throw statusException(status, String(body(), StandardCharsets.UTF_8))
    }
    if (T::class == ByteArray::class) {
        return (if (body().isEmpty() && null is T) null else body()) as T
    }
    return String(body(), StandardCharsets.UTF_8).asType(objectMapper)
}

@PublishedApi
internal fun statusException(status: Int, body: String): HttpException = when (status) {
    in 400..499 -> HttpClientException(status, body)
    in 500..599 -> HttpServerException(status, body)
    else -> HttpException(status, body, "Unexpected HTTP status $status")
}

/*
    JSON and Response Conversion
*/
@PublishedApi
internal inline fun <reified T> String.asType(objectMapper: ObjectMapper = mapper): T {
    if (T::class == Unit::class) {
        return Unit as T
    }
    if (T::class == String::class) {
        return (if (this.isEmpty() && null is T) null else this) as T
    }
    if (this.isBlank()) {
        if (null is T) return null as T
        throw IllegalStateException("Cannot deserialize empty response body to ${T::class.simpleName}")
    }
    return objectMapper.readValue(this)
}

internal fun <T> T.asJson(objectMapper: ObjectMapper = mapper): String =
    objectMapper.writeValueAsString(this)
