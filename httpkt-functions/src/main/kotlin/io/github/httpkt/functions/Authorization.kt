package io.github.httpkt.functions

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * Produces the full `Authorization` header value, scheme included (e.g. `"Bearer $token"`).
 * It is asked for the value each time a request is sent.
 *
 * A lambda is enough for static or always-fresh credentials: `Authorization { "ApiKey $key" }`.
 * Use [Authorization.cachedBearer] to fetch a token once and refresh it only after a 401, and
 * [Authorization.expiringBearer] for tokens with a known lifetime.
 */
fun interface Authorization {

    /** Called once per sent request. */
    fun headerValue(): String

    /**
     * Called when the server answers 401 to a request that carried [rejectedHeaderValue].
     * Return `true` to resend the request once with a fresh [headerValue]. The default never
     * retries, which is right for static credentials.
     */
    fun onUnauthorized(rejectedHeaderValue: String): Boolean = false

    companion object {
        /** Static HTTP Basic authentication (`Basic <base64(username:password)>`). */
        fun basic(username: String, password: String): Authorization {
            val credentials = "$username:$password".toByteArray(StandardCharsets.UTF_8)
            val header = "Basic ${Base64.getEncoder().encodeToString(credentials)}"
            return Authorization { header }
        }

        /** Static Bearer token (`Bearer <token>`). */
        fun bearer(token: String): Authorization {
            val header = "Bearer $token"
            return Authorization { header }
        }

        /** Asks [tokenProvider] for a bare token before every request. Never retries. */
        fun bearer(tokenProvider: () -> String): Authorization =
            Authorization { "Bearer ${tokenProvider()}" }

        /** Fetches a bare token once, caches `Bearer <token>`, and fetches again after a 401. */
        fun cachedBearer(fetchToken: () -> String): Authorization =
            CachedAuthorization { "Bearer ${fetchToken()}" }

        /**
         * For tokens that say when they expire, such as OAuth access tokens with `expires_in`.
         * [fetchToken] is called for the first request, again when the token is within
         * [refreshMargin] of its expiry, and again after a 401. Concurrent requests share one fetch.
         *
         * ```
         * Authorization.expiringBearer {
         *     val response = tokenEndpoint.fetch()
         *     ExpiringToken(response.accessToken, expiresIn = Duration.ofSeconds(response.expiresIn))
         * }
         * ```
         *
         * Fetch the token with a [RequestConfig] that has no authorization, otherwise the token
         * request would try to authorize itself.
         *
         * @param refreshMargin how long before the expiry a new token is fetched, so a request never
         * leaves with a token that expires on the way. Must not be negative.
         */
        fun expiringBearer(
            refreshMargin: Duration = Duration.ofSeconds(30),
            fetchToken: () -> ExpiringToken,
        ): Authorization = ExpiringAuthorization(refreshMargin, Instant::now, fetchToken)
    }
}

/**
 * A bare token (without `Bearer `) and the moment it stops being valid; see
 * [Authorization.expiringBearer]. `toString()` leaves the token out, so it is safe to log.
 */
class ExpiringToken(val token: String, val expiresAt: Instant) {

    /** For a token response that gives a lifetime (`expires_in`) instead of a point in time. */
    constructor(token: String, expiresIn: Duration) : this(token, Instant.now().plus(expiresIn))

    override fun toString(): String = "ExpiringToken(expiresAt=$expiresAt)"
}

/** The implementation behind [Authorization.expiringBearer]; [now] is replaceable for tests. */
internal class ExpiringAuthorization(
    private val refreshMargin: Duration,
    private val now: () -> Instant,
    private val fetchToken: () -> ExpiringToken,
) : Authorization {

    private var current: ExpiringToken? = null
    private var fetching: Boolean = false

    init {
        require(!refreshMargin.isNegative) { "refreshMargin must not be negative, got $refreshMargin" }
    }

    @Synchronized
    override fun headerValue(): String {
        current?.takeIf { now() < it.expiresAt.minus(refreshMargin) }?.let { return "Bearer ${it.token}" }
        check(!fetching) {
            "Recursive authorization detected while fetching token. Fetch it with a RequestConfig without authorization."
        }
        fetching = true
        return try {
            "Bearer ${fetchToken().also { current = it }.token}"
        } finally {
            fetching = false
        }
    }

    @Synchronized
    override fun onUnauthorized(rejectedHeaderValue: String): Boolean {
        if (current?.let { "Bearer ${it.token}" } == rejectedHeaderValue) {
            current = null
        }
        return true
    }
}

/**
 * Caches the header value from [fetch] until the server rejects it with a 401, then fetches
 * again and lets the request be retried once. A 401 for a value that has already been replaced
 * does not throw away the newer one.
 *
 * Fetch the token with a [RequestConfig] that has no authorization, otherwise the token request
 * would try to authorize itself.
 */
class CachedAuthorization(private val fetch: () -> String) : Authorization {

    private var cached: String? = null
    private var fetching: Boolean = false

    @Synchronized
    override fun headerValue(): String {
        cached?.let { return it }
        check(!fetching) {
            "Recursive authorization detected while fetching token. Fetch it with a RequestConfig without authorization."
        }
        fetching = true
        return try {
            fetch().also { cached = it }
        } finally {
            fetching = false
        }
    }

    @Synchronized
    override fun onUnauthorized(rejectedHeaderValue: String): Boolean {
        if (cached == rejectedHeaderValue) {
            cached = null
        }
        return true
    }
}
