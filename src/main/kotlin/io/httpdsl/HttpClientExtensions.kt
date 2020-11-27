package io.httpdsl

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.net.http.HttpResponse

class HttpClientDsl(val baseUrl: String) {
    var connectionTimeout: Long? = null

    fun body(body: HttpClientDsl.() -> Unit) {
        body.invoke(this)
    }
}

fun HttpClientDsl.post(path: String, vararg uriVariables: String, requestDsl: RequestDsl.() -> Unit = {}): HttpResponse<*> {
    val builder = HttpClient.newBuilder()
    val expandedPath = UriExpander.expand(path, *uriVariables).toString()
    return RequestDsl(builder).apply(requestDsl).exchange("${this.baseUrl}${expandedPath}", "POST")
}

fun HttpClientDsl.get(path: String, vararg uriVariables: String, requestDsl: RequestDsl.() -> Unit = {}): HttpResponse<*> {
    val builder = HttpClient.newBuilder()
    val expandedPath = UriExpander.expand(path, *uriVariables).toString()
    return RequestDsl(builder).apply(requestDsl).exchange("${this.baseUrl}${expandedPath}", "GET")
}

fun HttpClientDsl.put(path: String, vararg uriVariables: String, requestDsl: RequestDsl.() -> Unit = {}): HttpResponse<*> {
    val builder = HttpClient.newBuilder()
    val expandedPath = UriExpander.expand(path, *uriVariables).toString()
    return RequestDsl(builder).apply(requestDsl).exchange("${this.baseUrl}${expandedPath}", "GET")
}

fun HttpClientDsl.delete(path: String, vararg uriVariables: String, requestDsl: RequestDsl.() -> Unit = {}): HttpResponse<*> {
    val builder = HttpClient.newBuilder()
    val expandedPath = UriExpander.expand(path, *uriVariables).toString()
    return RequestDsl(builder).apply(requestDsl).exchange("${this.baseUrl}${expandedPath}", "GET")
}

fun asJson(type: Any): String {
    return ObjectMapper().writeValueAsString(type)
}