package io.httpdsl.functions

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Dependency-free builder for `multipart/form-data` request bodies (RFC 7578).
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

    fun field(name: String, value: String): MultipartBody {
        parts += Part.Field(name, value)
        return this
    }

    fun file(
        name: String,
        filename: String,
        content: ByteArray,
        contentType: String = "application/octet-stream",
    ): MultipartBody {
        parts += Part.File(name, filename, content, contentType)
        return this
    }

    fun file(
        name: String,
        path: Path,
        contentType: String = "application/octet-stream",
    ): MultipartBody = file(
        name = name,
        filename = path.fileName.toString(),
        content = Files.readAllBytes(path),
        contentType = contentType,
    )

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

    private fun escapeQuoted(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}

fun multipart(block: MultipartBody.() -> Unit): MultipartBody =
    MultipartBody().apply(block)

