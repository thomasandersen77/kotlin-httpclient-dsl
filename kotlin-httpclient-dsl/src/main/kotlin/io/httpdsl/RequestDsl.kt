package io.httpdsl

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class RequestDsl internal constructor(private val clientBuilder: HttpClient.Builder) {
    private var requestBuilder = HttpRequest.newBuilder()
    private val bodyDsl: BodyDsl = BodyDsl(requestBuilder)
    private val headerDsl: HeaderDsl = HeaderDsl(requestBuilder)

    var content: String? = null
    var connectTimeout: Duration = Duration.ofSeconds(30)
    var requestTimeout: Duration = Duration.ofSeconds(30)

    fun body(bdsl: BodyDsl.() -> String) {
        content = bdsl.invoke(bodyDsl)
    }

    fun headers(headers: HeaderDsl.() -> Unit) {
        headers.invoke(headerDsl)
    }

    fun request(body: RequestDsl.() -> Unit) {
        body.invoke(this)
    }

    internal fun exchange(url: String, method: String): HttpResponse<*> {
        if (method in arrayOf("PUT", "POST"))
            content?.let {
                requestBuilder.apply {
                    method(method, HttpRequest.BodyPublishers.ofString(content))
                }
            }
        else {
            requestBuilder.apply {
                method(method, HttpRequest.BodyPublishers.noBody())
            }
        }

        requestBuilder.apply {
            timeout(requestTimeout)
            uri(URI.create(url))
        }
        return clientBuilder.apply { connectTimeout(connectTimeout) }.build()
            .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
    }
}