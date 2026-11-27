package io.httpdsl

import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object UriExpander {

    private val placeholderRegex = Regex("\\{([^{}/]+)\\}")

    fun expand(uriTemplate: String, vararg uriVariables: Any): URI {
        val matches = placeholderRegex.findAll(uriTemplate).toList()
        require(matches.size == uriVariables.size) {
            "Expected ${matches.size} uriVariables for template '$uriTemplate', but got ${uriVariables.size}"
        }

        var index = 0
        val expanded = placeholderRegex.replace(uriTemplate) {
            encodeValue(uriVariables[index++].toString())
        }

        require(!expanded.contains("{") && !expanded.contains("}")) {
            "Invalid URI template '$uriTemplate'"
        }

        return try {
            URI(expanded)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException(e)
        }
    }

    internal fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun encodeValue(value: String): String = encode(value)
}