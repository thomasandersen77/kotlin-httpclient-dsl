package io.github.httpkt.dsl

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Marks the request DSL, so that inside a nested block the compiler does not silently resolve a
 * call against an outer request.
 */
@DslMarker
annotation class HttpDsl

internal enum class HttpMethod(val allowsBody: Boolean) {
    GET(allowsBody = false),
    POST(allowsBody = true),
    PUT(allowsBody = true),
    PATCH(allowsBody = true),
    DELETE(allowsBody = false),
}

/**
 * `Content-Type` and `Authorization` are set by the DSL itself. Accepting them as ordinary
 * headers too would put two values on the request.
 */
internal fun requireOrdinaryHeader(name: String, setWith: String) {
    require(!name.equals("Content-Type", ignoreCase = true)) {
        "Use contentType(...) instead of $setWith(\"$name\", ...)"
    }
    require(!name.equals("Authorization", ignoreCase = true)) {
        "Use authorization { ... }, bearerToken(...) or basicAuth(...) instead of $setWith(\"$name\", ...)"
    }
}

/**
 * The receiver of the block passed to [HttpClientDsl.get], [HttpClientDsl.post] and the others.
 * Everything here applies to that one request only. The block runs once, before the request is
 * sent, and an instance is not meant to be kept or shared.
 */
@HttpDsl
class RequestDsl internal constructor(
    private val method: HttpMethod,
    private val httpClient: HttpClient,
    private var authorization: Authorization?,
    private val objectMapper: ObjectMapper,
    private val defaultHeaders: Map<String, String>,
    /** Starts as the client's `requestTimeout`; assign to change it for this request. */
    var requestTimeout: Duration,
) {
    private val headers = mutableListOf<Pair<String, String>>()
    private val queryParams = mutableListOf<Pair<String, String>>()
    private var explicitContentType: String? = null
    private var defaultContentType: String? = null
    private var body: String? = null

    /**
     * Adds a request header. A header with the same name as one of the client's default headers
     * replaces that default for this request. `Content-Type` and `Authorization` are rejected,
     * because the DSL sets them itself and the request would otherwise carry two values: use
     * [contentType], and [authorization], [bearerToken] or [basicAuth] instead.
     */
    fun header(name: String, value: String) {
        requireOrdinaryHeader(name, setWith = "header")
        headers += name to value
    }

    /**
     * Appends URL-encoded query parameters to the request URI. An `Iterable` or any array adds
     * one parameter per element (`id=1&id=2`). Null values and null elements are ignored.
     */
    fun queryParam(name: String, value: Any?) {
        valuesOf(value).forEach { queryParams += name to it }
    }

    /**
     * Replaces the client's [Authorization] for this request. [provider] is asked for the
     * header value when the request is sent, not when this block runs, and is not asked at
     * all if the request fails before sending.
     */
    fun authorization(provider: Authorization) {
        authorization = provider
    }

    /** Sets HTTP Basic authentication (`Basic <base64(username:password)>`) for this request. */
    fun basicAuth(username: String, password: String) =
        authorization(Authorization.basic(username, password))

    /** Sets a Bearer token (`Bearer <token>`) for this request. */
    fun bearerToken(token: String) =
        authorization(Authorization.bearer(token))

    /** Sends this request with no `Authorization` header, ignoring the client's provider. */
    fun withoutAuthorization() {
        authorization = null
    }

    /** Overrides the `Content-Type` that [body], [json] or [form] sets. */
    fun contentType(value: String) {
        explicitContentType = value
    }

    /** Sets a raw request body and default `Content-Type` (unless overridden by [contentType]). */
    fun body(content: String, contentType: String) {
        checkBodyAllowed()
        body = content
        defaultContentType = contentType
    }

    /** Serializes [value] with the client's `ObjectMapper` and sets `Content-Type: application/json`. */
    fun json(value: Any) {
        checkBodyAllowed() // before serializing, so a GET fails for the right reason
        body(objectMapper.writeValueAsString(value), "application/json")
    }

    /**
     * Sets an `application/x-www-form-urlencoded` body with encoded names and values. Values are
     * expanded as in [queryParam]: null is skipped, an `Iterable` or any array repeats the field.
     */
    fun form(vararg fields: Pair<String, Any?>) {
        val encoded = fields
            .flatMap { (name, value) -> valuesOf(value).map { name to it } }
            .joinToString("&") { (name, value) -> "${formEncode(name)}=${formEncode(value)}" }
        body(encoded, "application/x-www-form-urlencoded")
    }

    private fun checkBodyAllowed() {
        check(method.allowsBody) { "${method.name} requests cannot have a body" }
    }

    internal fun exchange(uri: URI): HttpResponse<String> {
        val targetUri = appendQueryParams(uri)
        val firstRequest = buildRequest(targetUri)

        val response = send(firstRequest)
        val auth = authorization ?: return response
        if (response.statusCode() != 401) return response
        val rejected = firstRequest.headers().firstValue("Authorization").orElse(null) ?: return response
        if (!auth.onUnauthorized(rejected)) return response

        logResend(firstRequest)
        return send(buildRequest(targetUri))
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        val started = startTimer()
        val response = try {
            httpClient.send(request, BodyHandlers.ofString())
        } catch (e: Exception) {
            logFailure(request, e, started)
            throw e
        }
        logResponse(request, response.statusCode(), started)
        return MappedHttpResponse(response, objectMapper)
    }

    private fun appendQueryParams(uri: URI): URI {
        if (queryParams.isEmpty()) return uri
        val encodedParams = queryParams.joinToString("&") { (name, value) ->
            "${UriExpander.encode(name)}=${UriExpander.encode(value)}"
        }
        val mergedQuery = if (uri.rawQuery.isNullOrEmpty()) encodedParams else "${uri.rawQuery}&$encodedParams"
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        val authority = uri.rawAuthority?.let { "//$it" }.orEmpty()
        val scheme = uri.scheme?.let { "$it:" }.orEmpty()
        val path = uri.rawPath.orEmpty()
        return URI.create("$scheme$authority$path?$mergedQuery$fragment")
    }

    private fun buildRequest(uri: URI): HttpRequest =
        HttpRequest.newBuilder(uri)
            .method(method.name, body?.let(BodyPublishers::ofString) ?: BodyPublishers.noBody())
            .timeout(requestTimeout)
            .apply {
                defaultHeadersNotReplaced().forEach { (name, value) -> header(name, value) }
                headers.forEach { (name, value) -> header(name, value) }
                (explicitContentType ?: defaultContentType)?.let { header("Content-Type", it) }
                authorization?.let { header("Authorization", it.headerValue()) }
            }
            .build()

    private fun defaultHeadersNotReplaced(): Map<String, String> {
        val requestHeaderNames = headers.map { (name, _) -> name.lowercase() }.toSet()
        return defaultHeaders.filterKeys { it.lowercase() !in requestHeaderNames }
    }
}

private fun valuesOf(value: Any?): List<String> = when (value) {
    null -> emptyList()
    is Iterable<*> -> value.filterNotNull().map { it.toString() }
    is Array<*> -> value.filterNotNull().map { it.toString() }
    else -> if (value.javaClass.isArray) primitiveArrayValues(value) else listOf(value.toString())
}

/** `IntArray`, `LongArray` and the other primitive arrays share no supertype, hence reflection. */
private fun primitiveArrayValues(array: Any): List<String> =
    (0 until java.lang.reflect.Array.getLength(array)).map { java.lang.reflect.Array.get(array, it).toString() }

private fun formEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)
