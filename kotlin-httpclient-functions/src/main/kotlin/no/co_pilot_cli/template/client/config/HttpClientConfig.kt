package no.co_pilot_cli.template.client.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import no.co_pilot_cli.template.client.config.Method.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.nio.charset.StandardCharsets
import java.util.*


/*
    Config and data classes
 */
class HttpClientException(val status: Int, val body: String) : RuntimeException()
class HttpServerException(val status: Int, val body: String) : RuntimeException()

data class RequestConfig constructor(val url: String, val port: Int, val username: String?, val password: String?) {
    constructor(url: String, port: Int) : this(url, port, null, null)

    fun hasBasicAuth(): Boolean = username?.isEmpty() == false && password?.isEmpty() == false
}

internal enum class Method(val value: String) {
    GET("GET"),
    POST("POST"),
    DELETE("DELETE"),
    PUT("PUT")
}

/*
    Functions for executing HTTP requests
*/
fun <T> httpGet(config: RequestConfig, path: String, responseType: Class<T>): T =
    config.buildRequest(method = GET, body = null, path = path)
        .execute() asType responseType

fun <T> httpPost(config: RequestConfig, body: Any, path: String, responseType: Class<T>): T =
    config.buildRequest(method = POST, body = body, path = path)
        .execute() asType responseType

fun <T> httpPut(config: RequestConfig, body: Any, path: String, responseType: Class<T>): T =
    config.buildRequest(method = PUT, body = body, path = path)
        .execute() asType responseType

fun <T> httpDelete(config: RequestConfig, path: String, responseType: Class<T>): T =
    config.buildRequest(method = DELETE, body = null, path = path)
        .execute() asType responseType

/*
    Build the Request
 */
internal fun RequestConfig.buildRequest(method: Method, body: Any?, path: String): HttpRequest {
    val builder = HttpRequest
        .newBuilder(
            URI("${this.url}:${this.port}$path")
        ).let {
            when (method) {
                GET -> it.method(GET.value, HttpRequest.BodyPublishers.noBody())
                DELETE -> it.method(DELETE.value, HttpRequest.BodyPublishers.noBody())
                POST -> it.method(POST.value, HttpRequest.BodyPublishers.ofString(body.asJson(), Charsets.UTF_8))
                PUT -> it.method(PUT.value, HttpRequest.BodyPublishers.ofString(body.asJson(), Charsets.UTF_8))
            }

        }.header("Content-Type", "application/json")

    if (this.hasBasicAuth()) {
        builder.authorize(username!!, password!!)
    }
    return builder.build()

}

/*
    Basic authentication
 */

fun HttpRequest.Builder.authorize(username: String, password: String): HttpRequest.Builder {
    val credentials = Base64.getEncoder().encodeToString("$username:$password".encodeToByteArray())
    this.header("Authorization", credentials)
    return this
}

val httpClient: HttpClient = HttpClient.newHttpClient()
val responseBodyHandler = BodyHandler { HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8) }

fun HttpRequest.execute(): String {
    val response = httpClient.send(this, responseBodyHandler)
    return when (response.statusCode()) {
        in 200..399 -> response.body()
        in 400..499 -> throw HttpClientException(response.statusCode(), response.body())
        else -> throw HttpServerException(response.statusCode(), response.body())
    }
}

/*
 JSON STUFF
 */
val mapper: ObjectMapper = ObjectMapper()
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

infix fun <T> String.asType(type: Class<T>): T {
    return mapper.readValue(this, type)
}

fun <T> T.asJson(): String {
    return mapper.writeValueAsString(this)
}
