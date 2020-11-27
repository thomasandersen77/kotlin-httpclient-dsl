package io.httpdsl

import java.net.http.HttpRequest

class HeaderDsl internal constructor(val requestBuilder : HttpRequest.Builder){

    fun header(name: String, value: String) {
        requestBuilder.header(name, value)
    }

    fun header(name: String, value: () -> String) {
        requestBuilder.header(name, value.invoke())
    }

    fun authorization(function: () -> String) {
        requestBuilder.header("Authorization", function.invoke())
    }

}