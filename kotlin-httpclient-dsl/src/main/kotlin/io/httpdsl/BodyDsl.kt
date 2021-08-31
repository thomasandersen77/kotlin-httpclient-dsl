package io.httpdsl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import java.net.http.HttpRequest

class BodyDsl internal constructor(private val requestBuilder : HttpRequest.Builder) {


    fun contentType(contentType : () -> Array<String>) {
        contentType.invoke().iterator().forEach {
            requestBuilder.header("Content-Type", it)
        }
    }

    fun json(value : () -> Any) : String {
        return ObjectMapper().writeValueAsString(value.invoke())
    }

    fun xml(value : () -> Any) : String {
        return XmlMapper().writeValueAsString(value.invoke())
    }

}