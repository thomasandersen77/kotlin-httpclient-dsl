package io.httpdsl

import java.net.http.HttpClient
import java.net.http.HttpResponse

class HttpClientDsl(val baseUrl: String)

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
    return RequestDsl(builder).apply(requestDsl).exchange("${this.baseUrl}${expandedPath}", "PUT")
}

fun HttpClientDsl.delete(path: String, vararg uriVariables: String, requestDsl: RequestDsl.() -> Unit = {}): HttpResponse<*> {
    val builder = HttpClient.newBuilder()
    val expandedPath = UriExpander.expand(path, *uriVariables).toString()
    return RequestDsl(builder).apply(requestDsl).exchange("${this.baseUrl}${expandedPath}", "DELETE")
}

fun HttpResponse<*>.bodyAsString() : String {
    return this.body().toString()
}