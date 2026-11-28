package io.github.httpkt.functions

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.httpkt.functions.Method.DELETE
import io.github.httpkt.functions.Method.GET
import io.github.httpkt.functions.Method.PATCH
import io.github.httpkt.functions.Method.POST
import io.github.httpkt.functions.Method.PUT
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

/** The HTTP methods [exchange] can send. */
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

/**
 * Sends a GET and returns the response body as [T]: `Unit` ignores it, `String` is the raw text,
 * `ByteArray` the raw bytes, and any other type is deserialized from JSON. An empty body gives
 * `null` when [T] is nullable.
 *
 * ```
 * val api = RequestConfig("https://api.example.com/v1")
 * val order: Order = api.get("/orders/{id}", pathParams = mapOf("id" to 42))
 * ```
 *
 * @param path relative to [RequestConfig.url]. It may contain `{name}` placeholders.
 * @param queryParams URL-encoded and appended. A null value is skipped; an `Iterable` or an array
 * repeats the parameter (`id=1&id=2`).
 * @param headers for this request; they replace headers of the same name on the config.
 * @param pathParams the values for the placeholders in [path], URL-encoded. The names must match exactly.
 * @throws HttpClientException for a 4xx status.
 * @throws HttpServerException for a 5xx status.
 * @throws HttpException for any other status outside `200..299`.
 * @throws java.io.IOException if the request could not be sent or timed out.
 */
inline fun <reified T> RequestConfig.get(
    path: String,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    pathParams: Map<String, Any> = emptyMap(),
): T = exchange<T>(GET, path, queryParams = queryParams, headers = headers, pathParams = pathParams).body

/** Like [get], but a `404` gives `null` instead of an [HttpClientException], as does an empty body. */
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

/**
 * Sends a POST and returns the response body as [T]. The other parameters and the exceptions are
 * as for [get].
 *
 * @param body `null` sends no body. A `String` is sent as it is, a `ByteArray` as raw bytes, a
 * [MultipartBody] as `multipart/form-data`, a [FormBody] as a URL-encoded form, and anything else
 * is serialized to JSON.
 * @param contentType defaults to the body: `application/octet-stream` for a `ByteArray`, the
 * multipart type with its boundary for a [MultipartBody], `application/x-www-form-urlencoded` for
 * a [FormBody], and `application/json` for everything else, a `String` included.
 */
inline fun <reified T> RequestConfig.post(
    path: String,
    body: Any? = null,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    contentType: String? = null,
    pathParams: Map<String, Any> = emptyMap(),
): T = exchange<T>(POST, path, body, queryParams, headers, contentType, pathParams).body

/** Sends a PUT and returns the response body as [T]. Parameters and exceptions are as for [post]. */
inline fun <reified T> RequestConfig.put(
    path: String,
    body: Any? = null,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    contentType: String? = null,
    pathParams: Map<String, Any> = emptyMap(),
): T = exchange<T>(PUT, path, body, queryParams, headers, contentType, pathParams).body

/** Sends a PATCH and returns the response body as [T]. Parameters and exceptions are as for [post]. */
inline fun <reified T> RequestConfig.patch(
    path: String,
    body: Any? = null,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    contentType: String? = null,
    pathParams: Map<String, Any> = emptyMap(),
): T = exchange<T>(PATCH, path, body, queryParams, headers, contentType, pathParams).body

/** Sends a DELETE and returns the response body as [T]. Parameters and exceptions are as for [get]. */
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

/**
 * Downloads the response body of a GET to [destination] without holding it in memory, and returns
 * [destination]. The content is written to a temporary file in the same directory and moved into
 * place only after a `2xx`, so a failed download neither replaces an existing file nor leaves a
 * partial one. The other parameters and the status exceptions are as for [get]; on a non-`2xx`
 * response, at most the first 64 KB of the error body are read into the exception (decoded with
 * the response's `Content-Type` charset, defaulting to UTF-8).
 *
 * @param destination the file to write. An existing file is replaced; its directory must exist.
 * @throws IllegalArgumentException if [destination] is a directory or its directory does not exist.
 */
fun RequestConfig.downloadFile(
    path: String,
    destination: Path,
    queryParams: Map<String, Any?> = emptyMap(),
    headers: Map<String, String> = emptyMap(),
    pathParams: Map<String, Any> = emptyMap(),
): Path {
    // Download next to the destination and move it into place only after a 2xx, so an error
    // response never replaces an existing file or is left behind as the "downloaded" file.
    val target = destination.toAbsolutePath().normalize()
    val fileName = requireNotNull(target.fileName) { "destination '$destination' has no file name" }
    // Checked before the request is sent, so a mistake in the path does not cost a download.
    require(!Files.isDirectory(target)) { "destination '$destination' is a directory, not a file" }
    require(Files.isDirectory(target.parent)) { "destination directory '${target.parent}' does not exist" }
    val partial = target.resolveSibling("$fileName.${UUID.randomUUID()}.part")
    try {
        // TRUNCATE_EXISTING matters: after a 401 the request is sent again into the same file, and
        // without it the tail of a longer error body would survive behind the downloaded content.
        val response = send(
            GET, body = null, path, queryParams, headers, contentType = null, pathParams, accept = "*/*",
            HttpResponse.BodyHandlers.ofFile(
                partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
            ),
        )
        val status = response.statusCode()
        if (status !in 200..299) {
            throw statusException(status, readErrorBody(partial, response.responseCharset()), objectMapper)
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
        return destination
    } finally {
        Files.deleteIfExists(partial)
    }
}

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
    // The publisher and the Content-Type the body has by default; null when the request has no body.
    val content: Pair<HttpRequest.BodyPublisher, String>? = when (method) {
        GET, DELETE -> null
        POST, PUT, PATCH -> when (body) {
            null -> null
            is MultipartBody -> HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()) to body.contentTypeHeader
            is FormBody -> HttpRequest.BodyPublishers.ofString(body.encoded, StandardCharsets.UTF_8) to "application/x-www-form-urlencoded"
            is ByteArray -> HttpRequest.BodyPublishers.ofByteArray(body) to "application/octet-stream"
            is String -> HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8) to "application/json"
            else -> HttpRequest.BodyPublishers.ofString(body.asJson(objectMapper), StandardCharsets.UTF_8) to "application/json"
        }
    }

    val builder = HttpRequest
        .newBuilder(resolveUri(expandPath(path, pathParams), queryParams))
        .timeout(requestTimeout)
        .method(method.name, content?.first ?: HttpRequest.BodyPublishers.noBody())
        .setHeader("Accept", accept)

    // Content-Type, weakest first: the body's default, the config's headers, this call's
    // contentType, this call's headers. Each later one is more specific to the request.
    if (content != null) builder.setHeader("Content-Type", content.second)
    if (!hasAuthorizationHeader(headers)) authorization?.let { builder.setHeader("Authorization", it.headerValue()) }
    // Without a body there is no content to describe, whoever asked for a Content-Type.
    fun applies(name: String) = content != null || !name.equals("Content-Type", ignoreCase = true)
    this.headers.forEach { (name, value) -> if (applies(name)) builder.setHeader(name, value) }
    if (content != null && contentType != null) builder.setHeader("Content-Type", contentType)
    headers.forEach { (name, value) -> if (applies(name)) builder.setHeader(name, value) }
    // A multipart body is unreadable without its own boundary, so nothing may replace this one.
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
        val started = startTimer()
        val response = try {
            httpClient.send(request, bodyHandler)
        } catch (e: Exception) {
            logFailure(request, e, started)
            throw e
        }
        logResponse(request, response.statusCode(), started)
        return request to response
    }

    val (request, response) = attempt()
    val auth = authorization?.takeUnless { hasAuthorizationHeader(headers) } ?: return response
    if (response.statusCode() != 401) return response
    val rejected = request.headers().firstValue("Authorization").orElse(null) ?: return response
    if (!auth.onUnauthorized(rejected)) return response
    logResend(request)
    return attempt().second
}

@PublishedApi
internal inline fun <reified T> HttpResponse<ByteArray>.bodyAs(objectMapper: ObjectMapper): T {
    val status = statusCode()
    if (status !in 200..299) {
        throw statusException(status, String(body(), responseCharset()), objectMapper)
    }
    if (T::class == ByteArray::class) {
        // Only 204 means "nothing there"; an empty 200 is a real, empty file.
        return (if (status == 204 && null is T) null else body()) as T
    }
    return String(body(), responseCharset()).asType(objectMapper)
}

private val charsetPattern = Regex("""(?i)(?:^|;)\s*charset\s*=\s*"?([^";\s]+)"?""")
private const val MAX_DOWNLOAD_ERROR_BYTES = 64 * 1024

@PublishedApi
internal fun HttpResponse<*>.responseCharset(): Charset {
    val contentType = headers().firstValue("Content-Type").orElse(null) ?: return StandardCharsets.UTF_8
    val name = charsetPattern.find(contentType)?.groupValues?.get(1) ?: return StandardCharsets.UTF_8
    return runCatching { Charset.forName(name) }.getOrDefault(StandardCharsets.UTF_8)
}

private fun readErrorBody(file: Path, charset: Charset): String = runCatching {
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(MAX_DOWNLOAD_ERROR_BYTES)
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read == -1) break
            total += read
        }
        String(buffer, 0, total, charset)
    }
}.getOrDefault("")

@PublishedApi
internal fun statusException(status: Int, body: String, objectMapper: ObjectMapper): HttpException =
    statusException(status, body).also { it.objectMapper = objectMapper }

// Kept with this signature: code compiled against 1.0 has the inline callers of it built in.
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
