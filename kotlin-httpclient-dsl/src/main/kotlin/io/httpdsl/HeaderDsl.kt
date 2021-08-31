package io.httpdsl

import java.net.http.HttpRequest

class HeaderDsl internal constructor(private val requestBuilder : HttpRequest.Builder){

    fun header(name: String, value: String) {
        requestBuilder.header(name, value)
    }

    fun header(name: String, function: () -> String) {
        requestBuilder.header(name, function.invoke())
    }

    fun authorization(function: () -> String) {
        requestBuilder.header("Authorization", function.invoke())
    }

}