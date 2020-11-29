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

    fun body(bdsl: BodyDsl.() -> String) {
        content = bdsl.invoke(bodyDsl)
    }

    fun headers(headers: HeaderDsl.() -> Unit) {
        headers.invoke(headerDsl)
    }

    fun request(body: RequestDsl.() -> Unit) {
        body.invoke(this)
    }

    fun timeout(timeout: () -> Int) {
        requestBuilder.timeout(Duration.ofSeconds(timeout.invoke().toLong()))
    }

    internal fun exchange(url: String, method: String): HttpResponse<*> {
        if (method in arrayOf("PUT", "POST"))
            content?.let { requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(content)) }
        else {
            requestBuilder.method(method, HttpRequest.BodyPublishers.noBody())
        }

        requestBuilder.uri(URI.create(url))
        return clientBuilder
                .build()
                .send(requestBuilder.uri(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString())
    }
}