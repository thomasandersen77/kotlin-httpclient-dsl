package io.github.httpkt.functions

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Dependency-free builder for `multipart/form-data` request bodies (RFC 7578). Pass it as the
 * `body` of [post], [put] or [patch]; the `Content-Type` with the boundary is then set for you.
 * Usually built with [multipart]. The whole body is assembled in memory before it is sent.
 *
 * @param boundary the separator between the parts. The random default is right unless a test
 * needs a predictable body.
 */
class MultipartBody(
    val boundary: String = UUID.randomUUID().toString(),
) {
    private sealed class Part {
        data class Field(val name: String, val value: String) : Part()
        data class File(
            val name: String,
            val filename: String,
            val content: ByteArray,
            val contentType: String,
        ) : Part()
    }

    private val parts = mutableListOf<Part>()

    init {
        // RFC 2046 allows more characters, but the boundary goes unquoted into the Content-Type
        // header, where ( ) , / : = ? and spaces would need quoting. Only the safe ones are accepted.
        require(boundaryPattern.matches(boundary)) {
            "Multipart boundary must be 1-70 characters of A-Z a-z 0-9 ' + _ - ."
        }
    }

    /** Adds a text field. Returns this builder, so calls can be chained. */
    fun field(name: String, value: String): MultipartBody {
        requireSingleLine("name", name)
        parts += Part.Field(name, value)
        return this
    }

    /** Adds a file part from bytes already in memory. [filename] is what the server is told. */
    fun file(
        name: String,
        filename: String,
        content: ByteArray,
        contentType: String = "application/octet-stream",
    ): MultipartBody {
        requireSingleLine("name", name)
        requireSingleLine("filename", filename)
        requireSingleLine("contentType", contentType)
        parts += Part.File(name, filename, content, contentType)
        return this
    }

    /** Adds a file part read from [path] right away, under the file's own name. */
    fun file(
        name: String,
        path: Path,
        contentType: String = "application/octet-stream",
    ): MultipartBody = file(
        name = name,
        filename = requireNotNull(path.fileName) { "Path '$path' has no file name" }.toString(),
        content = Files.readAllBytes(path),
        contentType = contentType,
    )

    /** The `Content-Type` value for this body, boundary included. */
    val contentTypeHeader: String
        get() = "multipart/form-data; boundary=$boundary"

    internal fun toByteArray(): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) {
            out.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
            when (part) {
                is Part.Field -> {
                    val escapedName = escapeQuoted(part.name)
                    out.write(
                        "Content-Disposition: form-data; name=\"$escapedName\"\r\n\r\n"
                            .toByteArray(StandardCharsets.UTF_8)
                    )
                    out.write(part.value.toByteArray(StandardCharsets.UTF_8))
                    out.write("\r\n".toByteArray(StandardCharsets.UTF_8))
                }
                is Part.File -> {
                    val escapedName = escapeQuoted(part.name)
                    val escapedFilename = escapeQuoted(part.filename)
                    out.write(
                        "Content-Disposition: form-data; name=\"$escapedName\"; filename=\"$escapedFilename\"\r\n"
                            .toByteArray(StandardCharsets.UTF_8)
                    )
                    out.write("Content-Type: ${part.contentType}\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                    out.write(part.content)
                    out.write("\r\n".toByteArray(StandardCharsets.UTF_8))
                }
            }
        }
        out.write("--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
        return out.toByteArray()
    }

    /** These values end up in part headers; a line break would let the caller's data add headers. */
    private fun requireSingleLine(what: String, value: String) {
        require('\r' !in value && '\n' !in value) { "Multipart $what must not contain line breaks" }
    }

    private fun escapeQuoted(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}

private val boundaryPattern = Regex("[A-Za-z0-9'+_.-]{1,70}")

/**
 * Builds a [MultipartBody]:
 *
 * ```
 * api.post<Unit>("/documents", body = multipart {
 *     field("title", "Invoice")
 *     file("file", Path.of("invoice.pdf"), contentType = "application/pdf")
 * })
 * ```
 */
fun multipart(block: MultipartBody.() -> Unit): MultipartBody =
    MultipartBody().apply(block)


/**
 * An `application/x-www-form-urlencoded` request body, as OAuth token endpoints expect. Build it
 * with [formBody] and pass it as the `body` of [post], [put] or [patch].
 */
class FormBody internal constructor(internal val encoded: String) {
    /** The fields are often credentials, so they are left out. */
    override fun toString(): String = "FormBody(${encoded.length} characters)"
}

/**
 * Builds a [FormBody] with URL-encoded names and values. Values follow the same rules as
 * `queryParams`: null is skipped, and an `Iterable` or an array repeats the field.
 *
 * ```
 * val token: TokenResponse = auth.post("/token", body = formBody(
 *     "grant_type" to "client_credentials",
 *     "scope" to "orders:read orders:write",
 * ))
 * ```
 */
fun formBody(vararg fields: Pair<String, Any?>): FormBody = FormBody(
    fields
        .flatMap { (name, value) -> queryValuesOf(value).map { name to it } }
        .joinToString("&") { (name, value) -> "${formEncode(name)}=${formEncode(value)}" },
)

private fun formEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
