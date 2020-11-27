package io.httpdsl

import java.net.URI
import java.net.URISyntaxException
import java.util.*

object UriExpander {
    fun expand(uriTemplate: String, vararg uriVariables: Any): URI {
        Objects.requireNonNull(uriTemplate, "Url required, was null")
        var url: String
        require(!((uriTemplate.contains("{") || uriTemplate.contains("}")) && uriVariables.isEmpty())) { "Missing uriVariables" }
        if (!uriTemplate.contains("{") || uriVariables.isEmpty()) {
            url = uriTemplate
        } else {
            url = uriTemplate
            for (uriVariable in uriVariables) {
                url = replaceWithValue(url, uriVariable)
            }
        }
        return try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("Malformed url: [$url]")
        }
    }

    private fun replaceWithValue(urlTemplate: String, uriVariable: Any): String {
        val stop = urlTemplate.indexOf('}')
        val start = urlTemplate.indexOf('{')
        return urlTemplate.replace(urlTemplate.substring(start, stop + 1), uriVariable.toString())
    }
}