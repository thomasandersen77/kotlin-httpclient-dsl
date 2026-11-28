package io.github.httpkt.functions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
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
    fun `cachedBearer reports a token fetch that would authorize itself`() {
        lateinit var auth: Authorization
        auth = Authorization.cachedBearer { auth.headerValue() }

        val error = assertThrows(IllegalStateException::class.java) { auth.headerValue() }

        assertEquals(
            "Recursive authorization detected while fetching token. Fetch it with a RequestConfig without authorization.",
            error.message,
        )
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

        val withHeaders = RequestConfig(url = "http://localhost", headers = mapOf("X-Api-Key" to "s3cret-key")).toString()
        assertFalse(withHeaders.contains("s3cret"), withHeaders)
        assertTrue(withHeaders.contains("headers=[X-Api-Key]"), withHeaders)

        val withUrlSecrets = RequestConfig(url = "https://user:s3cret-pw@api.example.com:8443/v1?api_key=s3cret-key").toString()
        assertFalse(withUrlSecrets.contains("s3cret"), withUrlSecrets)
        assertTrue(withUrlSecrets.contains("url=https://api.example.com:8443/v1,"), withUrlSecrets)
    }

    @Test
    fun `each config has its own ObjectMapper, and copy keeps it`() {
        val first = RequestConfig(url = "http://localhost")
        val second = RequestConfig(url = "http://localhost")

        first.objectMapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

        assertNotSame(first.objectMapper, second.objectMapper)
        assertTrue(second.objectMapper.isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
        assertSame(first.objectMapper, first.copy(url = "http://other").objectMapper)
    }

    @Test
    fun `the Content-Type follows the body, and a multipart boundary cannot be replaced by a header`() {
        val config = RequestConfig(url = "http://localhost", headers = mapOf("Content-Type" to "application/vnd.api+json"))
        val plain = RequestConfig(url = "http://localhost")
        fun RequestConfig.contentTypeOf(body: Any?, contentType: String? = null) =
            buildRequest(method = Method.POST, body = body, path = "/x", contentType = contentType)
                .headers().allValues("Content-Type")

        assertEquals(listOf("application/octet-stream"), plain.contentTypeOf(byteArrayOf(1, 2)))
        assertEquals(listOf("application/pdf"), plain.contentTypeOf(byteArrayOf(1, 2), "application/pdf"))
        assertEquals(listOf("application/json"), plain.contentTypeOf("{}"))
        assertEquals(listOf("text/plain"), plain.contentTypeOf("hi", "text/plain"))
        assertEquals(listOf("application/vnd.api+json"), config.contentTypeOf(mapOf("a" to 1)))
        // A contentType given for one call is more specific than a header set for all calls ...
        assertEquals(listOf("text/plain"), config.contentTypeOf("hello", "text/plain"))
        assertEquals(listOf("application/pdf"), config.contentTypeOf(byteArrayOf(1), "application/pdf"))
        // ... and that call's own headers are the most specific of all.
        assertEquals(
            listOf("text/csv"),
            config.buildRequest(
                method = Method.POST, body = "a;b", path = "/x", contentType = "text/plain",
                headers = mapOf("content-type" to "text/csv"),
            ).headers().allValues("Content-Type"),
        )

        val upload = multipart { field("a", "b") }
        assertEquals(listOf(upload.contentTypeHeader), config.contentTypeOf(upload))
    }

    @Test
    fun `multipart rejects line breaks in anything that becomes a part header`() {
        assertThrows(IllegalArgumentException::class.java) { multipart { file("doc", "a.pdf\r\nX-Injected: 1", byteArrayOf(1)) } }
        assertThrows(IllegalArgumentException::class.java) { multipart { file("doc", "a.pdf", byteArrayOf(1), "application/pdf\nX-Evil: 1") } }
        assertThrows(IllegalArgumentException::class.java) { multipart { field("na\nme", "value") } }
        assertThrows(IllegalArgumentException::class.java) { MultipartBody(boundary = "b\r\n") }
        assertThrows(IllegalArgumentException::class.java) { MultipartBody(boundary = "") }
        assertThrows(IllegalArgumentException::class.java) { MultipartBody(boundary = "a; b") }
        assertThrows(IllegalArgumentException::class.java) { MultipartBody(boundary = "a=b") }
        assertThrows(IllegalArgumentException::class.java) { MultipartBody(boundary = "a/b") }
        assertThrows(IllegalArgumentException::class.java) { MultipartBody(boundary = "x".repeat(71)) }
        MultipartBody(boundary = "----WebKitFormBoundary7MA4YWxkTrZu0gW")
        assertThrows(IllegalArgumentException::class.java) { multipart { file("doc", java.nio.file.Path.of("/")) } }
        multipart { field("note", "line one\nline two") } // a field value is a body, not a header
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

        // A query string on the url is sent with every request, before the request's own.
        assertEquals(
            "https://api.example.com/v1/orders?api-version=2&page=1",
            RequestConfig(url = "https://api.example.com/v1?api-version=2").resolveUri("/orders", mapOf("page" to 1)).toString(),
        )

        // An empty path leaves the base path alone, trailing slash included.
        assertEquals("https://api.example.com/v1/", RequestConfig(url = "https://api.example.com/v1/").resolveUri("").toString())

        val absolute = assertThrows(IllegalArgumentException::class.java) {
            RequestConfig(url = "https://api.example.com").resolveUri("https://other.example.com/v1")
        }
        assertEquals("path must be relative to RequestConfig.url, got 'https://other.example.com/v1'", absolute.message)

        assertThrows(IllegalArgumentException::class.java) {
            RequestConfig(url = "https://api.example.com").resolveUri("//other.example.com/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RequestConfig(url = "https://api.example.com").resolveUri("http:/orders")
        }
        // A colon in the first segment is a path, not a scheme, with or without the leading slash.
        assertEquals("https://api.example.com/v1/orders:cancel", RequestConfig(url = "https://api.example.com/v1").resolveUri("orders:cancel").toString())
        assertEquals("https://api.example.com/v1/orders:cancel", RequestConfig(url = "https://api.example.com/v1").resolveUri("/orders:cancel").toString())

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
        val unclosed = assertThrows(IllegalArgumentException::class.java) {
            config.buildRequest(method = Method.GET, body = null, path = "/users/{unclosed")
        }
        assertEquals("Invalid path template '/users/{unclosed'", unclosed.message)
    }

    @Test
    fun `Content-Type is only set when the request has a body`() {
        val config = RequestConfig(url = "http://localhost")
        fun contentTypeOf(method: Method, body: Any?) =
            config.buildRequest(method = method, body = body, path = "/items").headers().allValues("Content-Type")

        // Also when a Content-Type is asked for in headers: a request without a body has none.
        val jsonApi = RequestConfig(url = "http://localhost", headers = mapOf("Content-Type" to "application/vnd.api+json", "X-Tenant" to "acme"))
        for (method in listOf(Method.GET, Method.DELETE, Method.POST)) {
            val request = jsonApi.buildRequest(method = method, body = null, path = "/items", headers = mapOf("content-type" to "text/plain"))
            assertEquals(emptyList<String>(), request.headers().allValues("Content-Type"), method.name)
            assertEquals(listOf("acme"), request.headers().allValues("X-Tenant"))
        }
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

    @Test
    fun `requestTimeout must be positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            RequestConfig(url = "http://localhost", requestTimeout = Duration.ZERO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RequestConfig(url = "http://localhost", requestTimeout = Duration.ofSeconds(-5))
        }
    }
}
