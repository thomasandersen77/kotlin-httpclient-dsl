package io.github.thomasandersen77.httpclient.functions

import java.lang.System.Logger.Level
import java.net.URI
import java.net.http.HttpRequest
import java.util.function.Supplier

/*
 * Debug logging through the JDK's own System.Logger, so the library needs no logging dependency.
 * Everything is logged at DEBUG under the logger name "io.github.thomasandersen77.httpclient.functions" and is silent until that level is
 * switched on. A line names the method, the URL and the outcome; it never contains the query
 * string, user info, headers or bodies.
 */
private val logger: System.Logger = System.getLogger("io.github.thomasandersen77.httpclient.functions")

internal fun startTimer(): Long = System.nanoTime()

private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

private fun describe(request: HttpRequest): String = "${request.method()} ${request.uri().withoutSecrets()}"

internal fun logResponse(request: HttpRequest, status: Int, startedNanos: Long) {
    logger.log(Level.DEBUG, Supplier<String> { "${describe(request)} -> $status in ${elapsedMs(startedNanos)} ms" })
}

/** A timeout or connection error never becomes a response, so it gets its own line. */
internal fun logFailure(request: HttpRequest, failure: Exception, startedNanos: Long) {
    logger.log(Level.DEBUG, Supplier<String> {
        "${describe(request)} failed after ${elapsedMs(startedNanos)} ms: ${failure.javaClass.simpleName}"
    })
}

internal fun logResend(request: HttpRequest) {
    logger.log(Level.DEBUG, Supplier<String> { "${describe(request)} -> 401, sending again with a new Authorization" })
}

/** Scheme, host, port and path: enough to recognise the call, nothing that could be a secret. */
internal fun URI.withoutSecrets(): String = buildString {
    if (scheme != null) append(scheme).append("://")
    if (host != null) append(host)
    if (port != -1) append(':').append(port)
    append(rawPath.orEmpty())
}
