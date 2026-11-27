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
import java.nio.file.Path

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
    contentType: String = "application/json",
    pathParams: Map<String, Any> = emptyMap(),
): T = exchange<T>(POST, path, body, queryParams, headers, contentType, pathParams).body

inline fun <reified T> RequestConfig.put(
    path: String,
    body: Any? = null,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    contentType: String = "application/json",
    pathParams: Map<String, Any> = emptyMap(),
): T = exchange<T>(PUT, path, body, queryParams, headers, contentType, pathParams).body

inline fun <reified T> RequestConfig.patch(
    path: String,
    body: Any? = null,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    contentType: String = "application/json",
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
    contentType: String = "application/json",
    pathParams: Map<String, Any> = emptyMap(),
): HttpResult<T> {
    val response = send(method, body, path, queryParams, headers, contentType, pathParams)
    return HttpResult(response.statusCode(), response.headers(), response.bodyAs(objectMapper))
}

fun RequestConfig.downloadFile(
    path: String,
    destination: Path,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    pathParams: Map<String, Any> = emptyMap(),
): Path {
    val request = buildRequest(GET, body = null, path, queryParams, headers, pathParams = pathParams)
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(destination))
    val status = response.statusCode()
    if (status !in 200..299) {
        val errorBody = runCatching { java.nio.file.Files.readString(destination, StandardCharsets.UTF_8) }.getOrDefault("")
        throw statusException(status, errorBody)
    }
    return response.body()
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
    contentType: String = "application/json",
    pathParams: Map<String, Any> = emptyMap(),
): HttpRequest {
    var effectiveContentType: String? = contentType
    val bodyPublisher = when (method) {
        GET, DELETE -> null
        POST, PUT, PATCH -> when (body) {
            null -> null
            is MultipartBody -> {
                effectiveContentType = body.contentTypeHeader
                HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())
            }
            is ByteArray -> HttpRequest.BodyPublishers.ofByteArray(body)
            is String -> HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
            else -> HttpRequest.BodyPublishers.ofString(body.asJson(objectMapper), StandardCharsets.UTF_8)
        }
    }

    val builder = HttpRequest
        .newBuilder(resolveUri(expandPath(path, pathParams), queryParams))
        .timeout(requestTimeout)
        .method(method.name, bodyPublisher ?: HttpRequest.BodyPublishers.noBody())
        .setHeader("Accept", "application/json")

    // A request without a body has no content to describe.
    if (bodyPublisher != null && effectiveContentType != null) builder.setHeader("Content-Type", effectiveContentType)
    if (!hasAuthorizationHeader(headers)) authorization?.let { builder.setHeader("Authorization", it.headerValue()) }
    this.headers.forEach { (name, value) -> builder.setHeader(name, value) }
    headers.forEach { (name, value) -> builder.setHeader(name, value) }

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
    contentType: String,
    pathParams: Map<String, Any>,
): HttpResponse<ByteArray> {
    fun attempt(): Pair<HttpRequest, HttpResponse<ByteArray>> {
        val request = buildRequest(method, body, path, queryParams, headers, contentType, pathParams)
        return request to httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
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
        return body() as T
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
    if (this.isBlank()) {
        if (null is T) return null as T
        throw IllegalStateException("Cannot deserialize empty response body to ${T::class.simpleName}")
    }
    if (T::class == String::class) {
        return this as T
    }
    return objectMapper.readValue(this)
}

internal fun <T> T.asJson(objectMapper: ObjectMapper = mapper): String =
    objectMapper.writeValueAsString(this)
