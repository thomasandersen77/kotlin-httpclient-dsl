package io.httpdsl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

@PublishedApi
internal val dslObjectMapper: ObjectMapper = jacksonObjectMapper()
    .registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

/**
 * A new `ObjectMapper` configured like the one [HttpClientDsl] uses by default (Kotlin module,
 * Java time, ISO dates). Adjust the copy and pass it as `HttpClientDsl(objectMapper = ...)`;
 * the shared default is never changed.
 */
fun defaultObjectMapper(): ObjectMapper = dslObjectMapper.copy()
