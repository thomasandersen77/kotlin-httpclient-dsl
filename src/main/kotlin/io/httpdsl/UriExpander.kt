package io.httpdsl

import java.net.URI
import java.net.URISyntaxException
import java.util.*

object UriExpander {
    fun expand(uriTemplate: String, vararg uriVariables: Any): URI {
        Objects.requireNonNull(uriTemplate, "Url required, was null")
        require(!((uriTemplate.contains("{") || uriTemplate.contains("}")) && uriVariables.isEmpty())) { "Missing uriVariables" }
        return try {
            if (!uriTemplate.contains("{") || uriVariables.isEmpty()) {
                URI(uriTemplate)
            } else {
                var url = ""
                uriVariables.forEach {
                    url = replaceWithValue(uriTemplate, it)
                }
                URI(url)
            }
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException(e)
        }
    }

    private fun replaceWithValue(urlTemplate: String, uriVariable: Any): String {
        val stop = urlTemplate.indexOf('}')
        val start = urlTemplate.indexOf('{')
        return urlTemplate.replace(urlTemplate.substring(start, stop + 1), uriVariable.toString())
    }
}