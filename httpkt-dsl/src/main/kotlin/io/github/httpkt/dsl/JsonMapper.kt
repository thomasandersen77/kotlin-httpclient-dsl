package io.github.httpkt.dsl

import com.fasterxml.jackson.databind.DeserializationFeature
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
 *
 * @param ignoreUnknownProperties `true` makes JSON fields that your type does not declare be
 * skipped instead of failing. Use it for an API you do not control, which may add fields at any
 * time. `@JsonIgnoreProperties(ignoreUnknown = true)` on a type does the same for that type.
 */
@JvmOverloads
fun defaultObjectMapper(ignoreUnknownProperties: Boolean = false): ObjectMapper =
    dslObjectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, !ignoreUnknownProperties)
