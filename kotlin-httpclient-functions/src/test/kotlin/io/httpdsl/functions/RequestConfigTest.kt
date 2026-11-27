package io.httpdsl.functions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.time.Duration

class RequestConfigTest {

    @Test
    fun `authorization factories produce the full header value`() {
        assertEquals("Basic YWRtaW46c2VjcmV0", Authorization.basic("admin", "secret").headerValue())
        assertEquals("Bearer static-token", Authorization.bearer("static-token").headerValue())
        assertEquals("ApiKey abc", Authorization { "ApiKey abc" }.headerValue())

        var calls = 0
        val perRequest = Authorization.bearer { "token-${++calls}" }
        assertEquals("Bearer token-1", perRequest.headerValue())
        assertEquals("Bearer token-2", perRequest.headerValue())

        var fetches = 0
        val cached = Authorization.cachedBearer { "token-${++fetches}" }
        assertEquals("Bearer token-1", cached.headerValue())
        assertEquals("Bearer token-1", cached.headerValue())
        assertTrue(cached.onUnauthorized("Bearer token-1"))
        assertEquals("Bearer token-2", cached.headerValue())
        // A late 401 for the old token must not throw away the newer one.
        assertTrue(cached.onUnauthorized("Bearer token-1"))
        assertEquals("Bearer token-2", cached.headerValue())
        assertFalse(Authorization.bearer("static-token").onUnauthorized("Bearer static-token"))
    }

    @Test
    fun `the config authorization sets the header, and an explicit Authorization header takes its place`() {
        var calls = 0
        val config = RequestConfig(url = "http://localhost", authorization = { calls++; "Bearer from-config" })

        val request = config.buildRequest(method = Method.GET, body = null, path = "/items")
        assertEquals(listOf("Bearer from-config"), request.headers().allValues("Authorization"))
        assertEquals(1, calls)

        val overridden = config.buildRequest(
            method = Method.GET, body = null, path = "/items", headers = mapOf("authorization" to "Basic explicit"),
        )
        assertEquals(listOf("Basic explicit"), overridden.headers().allValues("Authorization"))
        assertEquals(1, calls)

        val none = RequestConfig(url = "http://localhost").buildRequest(method = Method.GET, body = null, path = "/items")
        assertTrue(none.headers().allValues("Authorization").isEmpty())
    }

    @Test
    fun `toString does not reveal credentials`() {
        val text = RequestConfig(
            url = "http://localhost",
            authorization = Authorization.basic("admin", "s3cret-password"),
        ).toString()
        val bearerText = RequestConfig(url = "http://localhost", authorization = Authorization.bearer("s3cret-token")).toString()

        assertFalse(text.contains("s3cret") || text.contains("YWRtaW4"), text)
        assertFalse(bearerText.contains("s3cret"), bearerText)
    }

    @Test
    fun resolveUri() {
        val configWithBasePath = RequestConfig(url = "https://api.example.com:8443/v1/")
        val uri = configWithBasePath.resolveUri(
            path = "orders?status=open#details",
            queryParams = mapOf("q" to "hello world", "skip" to null),
        )
        assertEquals(
            "https://api.example.com:8443/v1/orders?status=open&q=hello%20world#details",
            uri.toString(),
        )

        assertEquals("http://localhost/ping", RequestConfig(url = "http://localhost").resolveUri("/ping").toString())

        val missingHostConfig = RequestConfig(url = "/relative")
        assertThrows(IllegalArgumentException::class.java) {
            missingHostConfig.resolveUri("/ping")
        }
    }

    @Test
    fun `path placeholders are replaced with encoded pathParams and mismatches are rejected`() {
        val config = RequestConfig(url = "http://localhost/v1")

        val request = config.buildRequest(
            method = Method.GET,
            body = null,
            path = "/users/{userId}/orders/{orderId}",
            pathParams = mapOf("userId" to "user 1", "orderId" to "a/b?c#d"),
        )
        assertEquals("http://localhost/v1/users/user%201/orders/a%2Fb%3Fc%23d", request.uri().toString())

        val missing = assertThrows(IllegalArgumentException::class.java) {
            config.buildRequest(method = Method.GET, body = null, path = "/users/{userId}")
        }
        assertEquals("Path '/users/{userId}' has placeholders [userId] but pathParams has []", missing.message)
        assertThrows(IllegalArgumentException::class.java) {
            config.buildRequest(method = Method.GET, body = null, path = "/users", pathParams = mapOf("userId" to 1))
        }
    }

    @Test
    fun `Content-Type is only set when the request has a body`() {
        val config = RequestConfig(url = "http://localhost")
        fun contentTypeOf(method: Method, body: Any?) =
            config.buildRequest(method = method, body = body, path = "/items").headers().allValues("Content-Type")

        assertEquals(emptyList<String>(), contentTypeOf(Method.GET, null))
        assertEquals(emptyList<String>(), contentTypeOf(Method.DELETE, null))
        assertEquals(emptyList<String>(), contentTypeOf(Method.POST, null))
        assertEquals(listOf("application/json"), contentTypeOf(Method.POST, mapOf("a" to 1)))
        assertEquals(listOf("application/json"), contentTypeOf(Method.PUT, "{}"))
    }

    @Test
    fun getHeaders() {
        val config = RequestConfig(
            url = "http://localhost",
            headers = mapOf("X-Tenant" to "acme", "X-Env" to "test"),
        )
        val request = config.buildRequest(
            method = Method.GET,
            body = null,
            path = "/items",
            headers = mapOf("X-Env" to "prod", "X-Request-Id" to "123"),
        )
        assertEquals("acme", request.headers().firstValue("X-Tenant").orElse(null))
        assertEquals("prod", request.headers().firstValue("X-Env").orElse(null))
        assertEquals("123", request.headers().firstValue("X-Request-Id").orElse(null))
    }

    @Test
    fun getHttpClient() {
        val customClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val config = RequestConfig(url = "http://localhost", httpClient = customClient)
        assertSame(customClient, config.httpClient)
        assertEquals(HttpClient.Redirect.NEVER, config.httpClient.followRedirects())
    }
}
