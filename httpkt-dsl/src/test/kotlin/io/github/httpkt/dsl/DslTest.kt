package io.github.httpkt.dsl

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.common.ConsoleNotifier
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.time.Duration.ofMillis
import java.time.Duration.ofSeconds
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DslTest {

    @Test
    internal fun `simple example request`() {
        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        val response = http.get("/admin/ping")

        assertEquals(200, response.statusCode())
        assertEquals("pong", response.body())
    }

    @Test
    internal fun `simple post`() {
        wiremock.stubFor(post(urlEqualTo("/person"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("{\"firstname\":\"first\",\"lastname\":\"last\"}")))

        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        val response = http.post("/person") {
            json(PersonDto("first", "last"))
        }

        assertEquals(200, response.statusCode())
        assertEquals(PersonDto("first", "last"), response.bodyAs<PersonDto>())

        wiremock.verify(postRequestedFor(urlEqualTo("/person"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(equalTo("{\"firstname\":\"first\",\"lastname\":\"last\"}")))
    }

    @Test
    internal fun `get-request with timeouts and a header value fetched by another request`() {
        val http = HttpClientDsl(
            baseUrl = "http://localhost:${wiremock.port()}",
            connectTimeout = ofSeconds(10),
        )
        fun fetchPing(): String = http.get("/admin/ping").body()

        val response = http.get("/admin/{uriVariable}", "ping") {
            requestTimeout = ofSeconds(20)
            header("header1", "value1")
            header("supplier", fetchPing())
        }

        assertEquals(200, response.statusCode())
        assertEquals("pong", response.body())

        wiremock.verify(
            getRequestedFor(urlEqualTo("/admin/ping"))
                .withHeader("header1", equalTo("value1"))
                .withHeader("supplier", equalTo("pong"))
        )
    }

    @Test
    internal fun `json body on a get-request fails before anything is sent`() {
        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        val error = assertFailsWith<IllegalStateException> {
            http.get("/get-with-body") {
                json(PersonDto(firstname = "Bill", lastname = "Anderson"))
            }
        }

        assertEquals("GET requests cannot have a body", error.message)
        wiremock.verify(0, getRequestedFor(urlEqualTo("/get-with-body")))
    }

    @Test
    internal fun `explicit content type overrides the json default regardless of order`() {
        wiremock.stubFor(put(urlEqualTo("/vendor"))
            .willReturn(aResponse().withStatus(204)))

        val http = HttpClientDsl("http://localhost:${wiremock.port()}")
        http.put("/vendor") {
            json(PersonDto(firstname = "Bill", lastname = "Anderson"))
            contentType("application/vnd.person+json")
        }

        wiremock.verify(putRequestedFor(urlEqualTo("/vendor"))
            .withHeader("Content-Type", equalTo("application/vnd.person+json"))
            .withRequestBody(equalTo("{\"firstname\":\"Bill\",\"lastname\":\"Anderson\"}")))
    }

    @Test
    internal fun `post a json-body with authorization and accept headers`() {
        val authHeaderValue = UUID.randomUUID().toString()
        wiremock.stubFor(post(urlEqualTo("/json/ping"))
            .withHeader("Authorization", equalTo(authHeaderValue))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(
                equalTo("{\"firstname\":\"Bill\",\"lastname\":\"Anderson\"}")
            )
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("{\"status\":\"success\"}")))

        fun login(user: String, pass: String): String {
            return authHeaderValue
        }

        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        val response = http.post("/json/{uriVariable}", "ping") {
            requestTimeout = ofSeconds(20)
            authorization { login("user", "pass") }
            header("Accept", "application/json")
            json(PersonDto(firstname = "Bill", lastname = "Anderson"))
        }

        assertEquals(200, response.statusCode())
        assertEquals("{\"status\":\"success\"}", response.body())

        wiremock.verify(postRequestedFor(urlEqualTo("/json/ping"))
            .withHeader("Accept", equalTo("application/json"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withHeader("Authorization", equalTo(authHeaderValue)))
    }

    @Test
    internal fun `post without body sends POST method instead of falling back to GET`() {
        wiremock.stubFor(post(urlEqualTo("/trigger"))
            .willReturn(aResponse().withStatus(204)))

        val http = HttpClientDsl("http://localhost:${wiremock.port()}")
        val response = http.post("/trigger")

        assertEquals(204, response.statusCode())
        wiremock.verify(postRequestedFor(urlEqualTo("/trigger")))
    }

    @Test
    internal fun `expand multiple uri placeholders and url-encode values`() {
        wiremock.stubFor(get(urlEqualTo("/users/user%201/orders/order%2F2"))
            .willReturn(aResponse().withStatus(200).withBody("ok")))

        val http = HttpClientDsl("http://localhost:${wiremock.port()}")
        val response = http.get("/users/{userId}/orders/{orderId}", "user 1", "order/2")

        assertEquals(200, response.statusCode())
        assertEquals("ok", response.body())

        assertFailsWith<IllegalArgumentException> {
            UriExpander.expand("/users/{userId}/orders/{orderId}", "only-one")
        }
    }

    @Test
    internal fun `client authorization provider is called for each request when it is sent`() {
        wiremock.stubFor(get(urlEqualTo("/secure")).willReturn(aResponse().withStatus(200)))
        var issued = 0
        val http = HttpClientDsl(
            baseUrl = "http://localhost:${wiremock.port()}",
            authorization = { "Bearer token-${++issued}" },
        )

        http.get("/secure")
        http.get("/secure")

        assertEquals(2, issued)
        wiremock.verify(1, getRequestedFor(urlEqualTo("/secure")).withHeader("Authorization", equalTo("Bearer token-1")))
        wiremock.verify(1, getRequestedFor(urlEqualTo("/secure")).withHeader("Authorization", equalTo("Bearer token-2")))
    }

    @Test
    internal fun `request authorization replaces the client provider without calling it`() {
        wiremock.stubFor(get(urlEqualTo("/admin-only")).willReturn(aResponse().withStatus(200)))
        var clientProviderCalls = 0
        val http = HttpClientDsl(
            baseUrl = "http://localhost:${wiremock.port()}",
            authorization = { clientProviderCalls++; "Bearer client" },
        )

        http.get("/admin-only") {
            authorization { "Basic YWRtaW46c2VjcmV0" }
        }

        assertEquals(0, clientProviderCalls)
        wiremock.verify(getRequestedFor(urlEqualTo("/admin-only"))
            .withHeader("Authorization", equalTo("Basic YWRtaW46c2VjcmV0")))
    }

    @Test
    internal fun `authorization provider is not called when the request fails before sending`() {
        var providerCalls = 0
        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        assertFailsWith<IllegalStateException> {
            http.get("/never-sent") {
                authorization { providerCalls++; "Bearer x" }
                json(PersonDto(firstname = "Bill", lastname = "Anderson"))
            }
        }

        assertEquals(0, providerCalls)
    }

    @Test
    internal fun `cached authorization fetches the token once and reuses it`() {
        wiremock.stubFor(get(urlEqualTo("/cached")).willReturn(aResponse().withStatus(200)))
        var fetches = 0
        val http = HttpClientDsl(
            baseUrl = "http://localhost:${wiremock.port()}",
            authorization = CachedAuthorization { "Bearer token-${++fetches}" },
        )

        http.get("/cached")
        http.get("/cached")

        assertEquals(1, fetches)
        wiremock.verify(2, getRequestedFor(urlEqualTo("/cached")).withHeader("Authorization", equalTo("Bearer token-1")))
    }

    @Test
    internal fun `cached authorization refetches after a 401 and resends the request once`() {
        wiremock.stubFor(post(urlEqualTo("/expiring")).inScenario("expiry")
            .whenScenarioStateIs(STARTED)
            .willReturn(aResponse().withStatus(401))
            .willSetStateTo("token refreshed"))
        wiremock.stubFor(post(urlEqualTo("/expiring")).inScenario("expiry")
            .whenScenarioStateIs("token refreshed")
            .willReturn(aResponse().withStatus(201)))
        var fetches = 0
        val http = HttpClientDsl(
            baseUrl = "http://localhost:${wiremock.port()}",
            authorization = CachedAuthorization { "Bearer token-${++fetches}" },
        )

        val response = http.post("/expiring") {
            json(PersonDto(firstname = "Bill", lastname = "Anderson"))
        }

        assertEquals(201, response.statusCode())
        assertEquals(2, fetches)
        wiremock.verify(1, postRequestedFor(urlEqualTo("/expiring")).withHeader("Authorization", equalTo("Bearer token-1")))
        wiremock.verify(1, postRequestedFor(urlEqualTo("/expiring"))
            .withHeader("Authorization", equalTo("Bearer token-2"))
            .withRequestBody(equalTo("{\"firstname\":\"Bill\",\"lastname\":\"Anderson\"}")))
    }

    @Test
    internal fun `static authorization returns the 401 without resending`() {
        wiremock.stubFor(get(urlEqualTo("/rejected")).willReturn(aResponse().withStatus(401)))
        var calls = 0
        val http = HttpClientDsl(
            baseUrl = "http://localhost:${wiremock.port()}",
            authorization = { calls++; "Bearer static" },
        )

        val response = http.get("/rejected")

        assertEquals(401, response.statusCode())
        assertEquals(1, calls)
        wiremock.verify(1, getRequestedFor(urlEqualTo("/rejected")))
    }

    @Test
    internal fun `withoutAuthorization lets the token request use the same client`() {
        wiremock.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(200).withBody("issued-token")))
        wiremock.stubFor(get(urlEqualTo("/protected")).willReturn(aResponse().withStatus(200)))
        lateinit var http: HttpClientDsl
        http = HttpClientDsl(
            baseUrl = "http://localhost:${wiremock.port()}",
            authorization = Authorization.cachedBearer {
                http.post("/token") { withoutAuthorization() }.body()
            },
        )

        http.get("/protected")

        wiremock.verify(1, postRequestedFor(urlEqualTo("/token")).withoutHeader("Authorization"))
        wiremock.verify(1, getRequestedFor(urlEqualTo("/protected")).withHeader("Authorization", equalTo("Bearer issued-token")))
    }

    @Test
    internal fun `forgetting withoutAuthorization on recursive token fetch throws clear IllegalStateException`() {
        lateinit var http: HttpClientDsl
        http = HttpClientDsl(
            baseUrl = "http://localhost:${wiremock.port()}",
            authorization = Authorization.cachedBearer {
                http.post("/token").body()
            },
        )

        val error = assertFailsWith<IllegalStateException> {
            http.get("/protected")
        }
        assertEquals(
            "Recursive authorization detected while fetching token. Use withoutAuthorization() on the token request.",
            error.message
        )
    }

    @Test
    internal fun `basicAuth queryParam numeric uriVariables and slash normalization work intuitively`() {
        wiremock.stubFor(get(urlEqualTo("/persons/42?filter=active%20user&page=1"))
            .withHeader("Authorization", equalTo("Basic YWRtaW46c2VjcmV0"))
            .willReturn(aResponse().withStatus(200).withBody("ok")))

        val http = HttpClientDsl("http://localhost:${wiremock.port()}/")
        val response = http.get("persons/{id}", 42) {
            basicAuth("admin", "secret")
            queryParam("filter", "active user")
            queryParam("ignored", null)
            queryParam("page", 1)
        }

        assertEquals(200, response.statusCode())
        assertEquals("ok", response.body())
        wiremock.verify(getRequestedFor(urlEqualTo("/persons/42?filter=active%20user&page=1"))
            .withHeader("Authorization", equalTo("Basic YWRtaW46c2VjcmV0")))
    }

    @Test
    internal fun `raw body sends the given content and Content-Type on POST and respects explicit contentType override`() {
        wiremock.stubFor(post(urlEqualTo("/oauth/token"))
            .willReturn(aResponse().withStatus(200).withBody("raw-token")))
        wiremock.stubFor(post(urlEqualTo("/custom-body"))
            .willReturn(aResponse().withStatus(200)))

        val http = HttpClientDsl("http://localhost:${wiremock.port()}")
        val response = http.post("/oauth/token") {
            body("grant_type=client_credentials", "application/x-www-form-urlencoded")
        }

        assertEquals(200, response.statusCode())
        assertEquals("raw-token", response.body())
        wiremock.verify(postRequestedFor(urlEqualTo("/oauth/token"))
            .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
            .withRequestBody(equalTo("grant_type=client_credentials")))

        http.post("/custom-body") {
            contentType("text/custom")
            body("plain-text", "text/plain")
        }
        wiremock.verify(postRequestedFor(urlEqualTo("/custom-body"))
            .withHeader("Content-Type", equalTo("text/custom"))
            .withRequestBody(equalTo("plain-text")))
    }

    @Test
    internal fun `raw body on a get-request fails before anything is sent`() {
        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        val error = assertFailsWith<IllegalStateException> {
            http.get("/get-with-raw-body") {
                body("grant_type=client_credentials", "application/x-www-form-urlencoded")
            }
        }

        assertEquals("GET requests cannot have a body", error.message)
        wiremock.verify(0, getRequestedFor(urlEqualTo("/get-with-raw-body")))
    }

    @Test
    internal fun `patch sends the PATCH method with a JSON body`() {
        wiremock.stubFor(patch(urlEqualTo("/persons/42"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("{\"firstname\":\"Updated\",\"lastname\":\"Anderson\"}")))

        val http = HttpClientDsl("http://localhost:${wiremock.port()}")
        val updated = http.patch("/persons/{id}", 42) {
            bearerToken("patch-token")
            json(PersonDto(firstname = "Updated", lastname = "Anderson"))
        }.bodyAs<PersonDto>()

        assertEquals(PersonDto("Updated", "Anderson"), updated)
        wiremock.verify(patchRequestedFor(urlEqualTo("/persons/42"))
            .withHeader("Authorization", equalTo("Bearer patch-token"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(equalTo("{\"firstname\":\"Updated\",\"lastname\":\"Anderson\"}")))
    }

    @Test
    internal fun `bodyAs throws HttpStatusException carrying status and body for 400 and 500`() {
        wiremock.stubFor(post(urlEqualTo("/bad-request"))
            .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"invalid input\"}")))
        wiremock.stubFor(get(urlEqualTo("/server-error"))
            .willReturn(aResponse().withStatus(500).withBody("internal failure")))
        wiremock.stubFor(delete(urlEqualTo("/no-content"))
            .willReturn(aResponse().withStatus(204)))

        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        val clientError = assertFailsWith<HttpStatusException> {
            http.post("/bad-request") {
                json(PersonDto("first", "last"))
            }.bodyAs<PersonDto>()
        }
        assertEquals(400, clientError.status)
        assertEquals("{\"error\":\"invalid input\"}", clientError.body)
        assertTrue(clientError.message!!.contains("400"))

        val serverError = assertFailsWith<HttpStatusException> {
            http.get("/server-error").bodyAs<PersonDto>()
        }
        assertEquals(500, serverError.status)
        assertEquals("internal failure", serverError.body)
        assertTrue(serverError.message!!.contains("500"))

        val emptyNullable: PersonDto? = http.delete("/no-content").bodyAs()
        assertNull(emptyNullable)

        val emptyUnit: Unit = http.delete("/no-content").bodyAs()
        assertEquals(Unit, emptyUnit)
    }

    @Test
    internal fun `queryParam places query parameters before URI fragment`() {
        wiremock.stubFor(get(urlEqualTo("/docs?page=2"))
            .willReturn(aResponse().withStatus(200).withBody("ok")))

        val http = HttpClientDsl("http://localhost:${wiremock.port()}")
        val response = http.get("/docs#section") {
            queryParam("page", 2)
        }

        assertEquals(200, response.statusCode())
        assertEquals("http://localhost:${wiremock.port()}/docs?page=2#section", response.request().uri().toString())
    }

    @Test
    internal fun `two requests rejected with the same stale token share one refresh`() {
        // The 401 is delayed, so both requests have been sent with token-1 before either is
        // rejected. The second 401 then arrives for a token that has already been replaced.
        wiremock.stubFor(get(urlEqualTo("/concurrent-resource"))
            .withHeader("Authorization", equalTo("Bearer token-1"))
            .willReturn(aResponse().withStatus(401).withFixedDelay(500)))
        wiremock.stubFor(get(urlEqualTo("/concurrent-resource"))
            .withHeader("Authorization", equalTo("Bearer token-2"))
            .willReturn(aResponse().withStatus(200).withBody("ok")))
        val tokenCalls = AtomicInteger(0)
        val http = HttpClientDsl(
            baseUrl = "http://localhost:${wiremock.port()}",
            authorization = Authorization.cachedBearer { "token-${tokenCalls.incrementAndGet()}" },
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                executor.submit<Int> {
                    start.await()
                    http.get("/concurrent-resource").statusCode()
                }
            }
            start.countDown()
            assertEquals(listOf(200, 200), futures.map { it.get(10, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }

        wiremock.verify(2, getRequestedFor(urlEqualTo("/concurrent-resource")).withHeader("Authorization", equalTo("Bearer token-1")))
        wiremock.verify(2, getRequestedFor(urlEqualTo("/concurrent-resource")).withHeader("Authorization", equalTo("Bearer token-2")))
        assertEquals(2, tokenCalls.get(), "token-1 once, then one shared refresh to token-2")
    }

    @Test
    internal fun `a 404 response can still be read through statusCode and body without an exception`() {
        wiremock.stubFor(get(urlEqualTo("/missing"))
            .willReturn(aResponse().withStatus(404).withBody("not found")))

        val http = HttpClientDsl("http://localhost:${wiremock.port()}")
        val response = http.get("/missing")

        assertEquals(404, response.statusCode())
        assertEquals("not found", response.body())
    }

    @Test
    internal fun `a custom HttpClient passed to HttpClientDsl is the one that is used`() {
        wiremock.stubFor(get(urlEqualTo("/redirect-source"))
            .willReturn(aResponse()
                .withStatus(302)
                .withHeader("Location", "http://localhost:${wiremock.port()}/admin/ping")))

        val customClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val http = HttpClientDsl(
            baseUrl = "http://localhost:${wiremock.port()}",
            httpClient = customClient,
        )

        assertSame(customClient, http.httpClient)
        val response = http.get("/redirect-source")
        assertEquals(302, response.statusCode())
        wiremock.verify(1, getRequestedFor(urlEqualTo("/redirect-source")))
    }

    @Test
    internal fun `header rejects Content-Type and Authorization so the request never carries two values`() {
        val http = HttpClientDsl(
            baseUrl = "http://localhost:${wiremock.port()}",
            authorization = Authorization.bearer("client"),
        )

        val contentTypeError = assertFailsWith<IllegalArgumentException> {
            http.post("/duplicate-headers") {
                header("Content-Type", "text/plain")
                json(PersonDto(firstname = "Bill", lastname = "Anderson"))
            }
        }
        assertEquals("Use contentType(...) instead of header(\"Content-Type\", ...)", contentTypeError.message)

        val authorizationError = assertFailsWith<IllegalArgumentException> {
            http.get("/duplicate-headers") { header("authorization", "Bearer mine") }
        }
        assertEquals(
            "Use authorization { ... }, bearerToken(...) or basicAuth(...) instead of header(\"authorization\", ...)",
            authorizationError.message,
        )

        wiremock.verify(0, anyRequestedFor(urlEqualTo("/duplicate-headers")))
    }

    @Test
    internal fun `bodyAs String returns the raw body text without parsing it as JSON`() {
        wiremock.stubFor(get(urlEqualTo("/json-object"))
            .willReturn(okJson("{\"firstname\":\"Bill\"}")))
        wiremock.stubFor(get(urlEqualTo("/text-error"))
            .willReturn(aResponse().withStatus(503).withBody("down")))

        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        assertEquals("pong", http.get("/admin/ping").bodyAs<String>())
        assertEquals("{\"firstname\":\"Bill\"}", http.get("/json-object").bodyAs<String>())
        val error = assertFailsWith<HttpStatusException> { http.get("/text-error").bodyAs<String>() }
        assertEquals(503, error.status)
    }

    @Test
    internal fun `a custom Authorization is told which header value the server rejected`() {
        wiremock.stubFor(get(urlEqualTo("/told-what-was-rejected"))
            .withHeader("Authorization", equalTo("Token first"))
            .willReturn(aResponse().withStatus(401)))
        wiremock.stubFor(get(urlEqualTo("/told-what-was-rejected"))
            .withHeader("Authorization", equalTo("Token second"))
            .willReturn(aResponse().withStatus(200).withBody("ok")))
        val rejected = mutableListOf<String>()
        val tokens = listOf("Token first", "Token second").iterator()
        val http = HttpClientDsl(
            baseUrl = "http://localhost:${wiremock.port()}",
            authorization = object : Authorization {
                override fun headerValue() = tokens.next()
                override fun onUnauthorized(rejectedHeaderValue: String): Boolean {
                    rejected += rejectedHeaderValue
                    return true
                }
            },
        )

        assertEquals("ok", http.get("/told-what-was-rejected").body())
        assertEquals(listOf("Token first"), rejected)
    }

    @Test
    internal fun `a base url with a query string or a fragment keeps them in the right place`() {
        wiremock.stubFor(get(urlEqualTo("/v1/orders?api_key=abc&page=1")).willReturn(aResponse().withStatus(200).withBody("orders")))
        wiremock.stubFor(get(urlEqualTo("/v1?api_key=abc&b=2")).willReturn(aResponse().withStatus(200).withBody("root")))
        val http = HttpClientDsl("http://localhost:${wiremock.port()}/v1?api_key=abc")

        assertEquals("orders", http.get("/orders") { queryParam("page", 1) }.body())
        assertEquals("root", http.get("?b=2").body())

        val withFragment = HttpClientDsl("http://localhost:${wiremock.port()}/v1?api_key=abc#top")
        assertEquals(
            "http://localhost:${wiremock.port()}/v1/orders?api_key=abc&page=1#top",
            withFragment.get("orders") { queryParam("page", 1) }.request().uri().toString(),
        )

        val error = assertFailsWith<IllegalArgumentException> { HttpClientDsl("/relative") }
        assertEquals("baseUrl must include scheme and host, got '/relative'", error.message)

        val absolute = assertFailsWith<IllegalArgumentException> { http.get("https://other.example.com/v1/orders") }
        assertEquals("path must be relative to baseUrl, got 'https://other.example.com/v1/orders'", absolute.message)
        assertFailsWith<IllegalArgumentException> { http.get("//other.example.com/v1/orders") }

        // A colon in the first segment is a path, not a scheme, with or without the leading slash.
        wiremock.stubFor(post(urlEqualTo("/v1/orders:cancel?api_key=abc")).willReturn(aResponse().withStatus(204)))
        assertEquals(204, http.post("orders:cancel").statusCode())
        assertEquals(204, http.post("/orders:{action}", "cancel").statusCode())
    }

    @Test
    internal fun `each client has its own ObjectMapper, so reconfiguring one does not change another`() {
        wiremock.stubFor(get(urlEqualTo("/person-with-extra"))
            .willReturn(okJson("{\"firstname\":\"Bill\",\"lastname\":\"Anderson\",\"age\":50}")))
        val tolerant = HttpClientDsl("http://localhost:${wiremock.port()}")
        val strict = HttpClientDsl("http://localhost:${wiremock.port()}")

        tolerant.objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

        assertNotSame(tolerant.objectMapper, strict.objectMapper)
        assertEquals(PersonDto("Bill", "Anderson"), tolerant.get("/person-with-extra").bodyAs<PersonDto>())
        assertFailsWith<com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException> {
            strict.get("/person-with-extra").bodyAs<PersonDto>()
        }
    }

    @Test
    internal fun `an empty or blank text body is returned as it is, and HttpStatusException bodyAs follows the same rules`() {
        wiremock.stubFor(get(urlEqualTo("/empty-text")).willReturn(aResponse().withStatus(200)))
        wiremock.stubFor(get(urlEqualTo("/blank-text")).willReturn(aResponse().withStatus(200).withBody("   ")))
        wiremock.stubFor(get(urlEqualTo("/maintenance")).willReturn(aResponse().withStatus(503).withBody("Down for maintenance")))
        wiremock.stubFor(get(urlEqualTo("/bad-gateway")).willReturn(aResponse().withStatus(502)))
        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        assertEquals("", http.get("/empty-text").bodyAs<String>())
        assertNull(http.get("/empty-text").bodyAs<String?>())
        assertEquals("   ", http.get("/blank-text").bodyAs<String>())
        assertEquals("   ", http.get("/blank-text").bodyAs<String?>())

        val maintenance = assertFailsWith<HttpStatusException> { http.get("/maintenance").bodyAs<PersonDto>() }
        assertEquals("Down for maintenance", maintenance.bodyAs<String>())
        val badGateway = assertFailsWith<HttpStatusException> { http.get("/bad-gateway").bodyAs<PersonDto>() }
        assertNull(badGateway.bodyAs<PersonDto?>())
        assertFailsWith<IllegalStateException> { badGateway.bodyAs<PersonDto>() }
    }

    @Test
    internal fun `delete rejects a body, defaultHeaders reject Content-Type, and a later change to the map is ignored`() {
        wiremock.stubFor(get(urlEqualTo("/copied-defaults")).willReturn(aResponse().withStatus(200)))
        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        val error = assertFailsWith<IllegalStateException> {
            http.delete("/delete-with-body") { form("a" to "b") }
        }
        assertEquals("DELETE requests cannot have a body", error.message)
        wiremock.verify(0, deleteRequestedFor(urlEqualTo("/delete-with-body")))

        assertFailsWith<IllegalArgumentException> {
            HttpClientDsl("http://localhost", defaultHeaders = mapOf("content-type" to "text/plain"))
        }

        val headers = mutableMapOf("X-Tenant" to "acme")
        val withDefaults = HttpClientDsl("http://localhost:${wiremock.port()}", defaultHeaders = headers)
        headers["Authorization"] = "Bearer added-later"
        withDefaults.get("/copied-defaults")
        wiremock.verify(getRequestedFor(urlEqualTo("/copied-defaults"))
            .withHeader("X-Tenant", equalTo("acme"))
            .withoutHeader("Authorization"))
    }

    @Test
    internal fun `the response prints like the JDK response, and bearer with a lambda asks for a token per request`() {
        wiremock.stubFor(get(urlEqualTo("/per-request-token")).willReturn(aResponse().withStatus(200)))
        var issued = 0
        val http = HttpClientDsl(
            baseUrl = "http://localhost:${wiremock.port()}",
            authorization = Authorization.bearer { "token-${++issued}" },
        )

        val response = http.get("/per-request-token")
        http.get("/per-request-token")

        assertEquals("(GET http://localhost:${wiremock.port()}/per-request-token) 200", response.toString())
        wiremock.verify(1, getRequestedFor(urlEqualTo("/per-request-token")).withHeader("Authorization", equalTo("Bearer token-1")))
        wiremock.verify(1, getRequestedFor(urlEqualTo("/per-request-token")).withHeader("Authorization", equalTo("Bearer token-2")))
    }

    @Test
    internal fun `cached authorization keeps a newer token when a 401 arrives for an older one`() {
        var fetches = 0
        val auth = CachedAuthorization { "Bearer token-${++fetches}" }
        assertEquals("Bearer token-1", auth.headerValue())

        // Two requests were sent with token-1 and both are rejected. Whichever order the two
        // rejections and the next fetch come in, token-1 is replaced exactly once.
        assertTrue(auth.onUnauthorized("Bearer token-1"))
        assertTrue(auth.onUnauthorized("Bearer token-1"))
        assertEquals("Bearer token-2", auth.headerValue())
        assertTrue(auth.onUnauthorized("Bearer token-1"))
        assertEquals("Bearer token-2", auth.headerValue())
        assertEquals(2, fetches)

        // A rejection of the current token does replace it.
        assertTrue(auth.onUnauthorized("Bearer token-2"))
        assertEquals("Bearer token-3", auth.headerValue())
    }

    @Test
    internal fun `HttpStatusException message leaves out user info and query, and a response equals only itself wrapped`() {
        val error = HttpStatusException(500, "boom", "GET", java.net.URI("https://user:s3cret@api.example.com:8443/v1/orders?api_key=s3cret#top"))
        assertEquals("HTTP status 500 for GET https://api.example.com:8443/v1/orders", error.message)

        val http = HttpClientDsl("http://localhost:${wiremock.port()}")
        val first = http.get("/admin/ping")
        val second = http.get("/admin/ping")
        assertEquals(first, first)
        assertTrue(first != second)
        assertEquals(first.hashCode(), first.hashCode())
    }

    @Test
    internal fun `debug logging shows method, url, status, resends and failures, and no secrets`() {
        wiremock.stubFor(get(urlEqualTo("/logged?api_key=s3cret-key")).willReturn(aResponse().withStatus(200)))
        wiremock.stubFor(get(urlEqualTo("/logged-expiring")).withHeader("Authorization", equalTo("Bearer s3cret-1"))
            .willReturn(aResponse().withStatus(401)))
        wiremock.stubFor(get(urlEqualTo("/logged-expiring")).withHeader("Authorization", equalTo("Bearer s3cret-2"))
            .willReturn(aResponse().withStatus(200)))
        wiremock.stubFor(get(urlEqualTo("/logged-slow")).willReturn(aResponse().withStatus(200).withFixedDelay(1_000)))
        val base = "http://localhost:${wiremock.port()}"
        var fetches = 0
        val http = HttpClientDsl(base, authorization = Authorization.cachedBearer { "s3cret-${++fetches}" })

        assertTrue(!System.getLogger("io.github.httpkt.dsl").isLoggable(System.Logger.Level.DEBUG), "silent until DEBUG is switched on")
        val lines = debugLinesOf("io.github.httpkt.dsl") {
            http.get("/logged") { withoutAuthorization(); queryParam("api_key", "s3cret-key") }
            http.get("/logged-expiring")
            assertFailsWith<HttpTimeoutException> { http.get("/logged-slow") { requestTimeout = ofMillis(100) } }
        }

        assertEquals(5, lines.size, lines.toString())
        assertTrue(Regex("GET \\Q$base/logged\\E -> 200 in \\d+ ms").matches(lines[0]), lines[0])
        assertTrue(Regex("GET \\Q$base/logged-expiring\\E -> 401 in \\d+ ms").matches(lines[1]), lines[1])
        assertEquals("GET $base/logged-expiring -> 401, sending again with a new Authorization", lines[2])
        assertTrue(Regex("GET \\Q$base/logged-expiring\\E -> 200 in \\d+ ms").matches(lines[3]), lines[3])
        assertTrue(Regex("GET \\Q$base/logged-slow\\E failed after \\d+ ms: HttpTimeoutException").matches(lines[4]), lines[4])
        assertTrue(lines.none { "s3cret" in it }, lines.toString())
    }

    /** Runs [block] with DEBUG switched on for [loggerName] and returns the lines it logged. */
    private fun debugLinesOf(loggerName: String, block: () -> Unit): List<String> {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { lines += record.message }
            override fun flush() = Unit
            override fun close() = Unit
        }
        // System.Logger ends up in java.util.logging when no other backend is installed.
        val backend = java.util.logging.Logger.getLogger(loggerName)
        val previousLevel = backend.level
        backend.level = java.util.logging.Level.FINE
        backend.addHandler(handler)
        try {
            block()
        } finally {
            backend.removeHandler(handler)
            backend.level = previousLevel
        }
        return lines.toList()
    }

    private data class LineDto(val productId: String, val unitPrice: Int)

    @Test
    internal fun `the client's ObjectMapper serializes json bodies and deserializes bodyAs results`() {
        wiremock.stubFor(post(urlEqualTo("/lines"))
            .willReturn(okJson("{\"product_id\":\"p-1\",\"unit_price\":10,\"added_by_server\":true}")))
        val snakeCase = defaultObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        val http = HttpClientDsl("http://localhost:${wiremock.port()}", objectMapper = snakeCase)

        val line = http.post("/lines") { json(LineDto("p-1", 10)) }.bodyAs<LineDto>()

        assertEquals(LineDto("p-1", 10), line)
        wiremock.verify(postRequestedFor(urlEqualTo("/lines"))
            .withRequestBody(equalTo("{\"product_id\":\"p-1\",\"unit_price\":10}")))

        // The shared default mapper is untouched: another client still writes camelCase.
        HttpClientDsl("http://localhost:${wiremock.port()}").post("/lines") { json(LineDto("p-2", 20)) }
        wiremock.verify(postRequestedFor(urlEqualTo("/lines"))
            .withRequestBody(equalTo("{\"productId\":\"p-2\",\"unitPrice\":20}")))
    }

    @Test
    internal fun `default headers are sent with every request and a request header replaces a default`() {
        wiremock.stubFor(get(urlEqualTo("/defaults")).willReturn(aResponse().withStatus(200)))
        wiremock.stubFor(get(urlEqualTo("/replaced")).willReturn(aResponse().withStatus(200)))
        val http = HttpClientDsl(
            baseUrl = "http://localhost:${wiremock.port()}",
            defaultHeaders = mapOf("Accept" to "application/json", "X-Tenant" to "acme"),
        )

        http.get("/defaults")
        http.get("/replaced") { header("accept", "text/plain") }

        val defaults = wiremock.findAll(getRequestedFor(urlEqualTo("/defaults"))).single()
        assertEquals(listOf("application/json"), defaults.headers.getHeader("Accept").values())
        assertEquals(listOf("acme"), defaults.headers.getHeader("X-Tenant").values())
        val replaced = wiremock.findAll(getRequestedFor(urlEqualTo("/replaced"))).single()
        assertEquals(listOf("text/plain"), replaced.headers.getHeader("Accept").values())
        assertEquals(listOf("acme"), replaced.headers.getHeader("X-Tenant").values())

        val error = assertFailsWith<IllegalArgumentException> {
            HttpClientDsl("http://localhost", defaultHeaders = mapOf("Authorization" to "Bearer x"))
        }
        assertEquals(
            "Use authorization { ... }, bearerToken(...) or basicAuth(...) instead of defaultHeaders(\"Authorization\", ...)",
            error.message,
        )
    }

    @Test
    internal fun `the client's requestTimeout applies to every request unless the request sets its own`() {
        wiremock.stubFor(get(urlEqualTo("/takes-half-a-second"))
            .willReturn(aResponse().withStatus(200).withBody("done").withFixedDelay(500)))
        val http = HttpClientDsl("http://localhost:${wiremock.port()}", requestTimeout = ofMillis(100))

        assertFailsWith<HttpTimeoutException> { http.get("/takes-half-a-second") }
        assertEquals("done", http.get("/takes-half-a-second") { requestTimeout = ofSeconds(10) }.body())
    }

    @Test
    internal fun `bodyAsOrNull returns null for 404 and an empty body and still throws for other errors`() {
        wiremock.stubFor(get(urlEqualTo("/person/1"))
            .willReturn(okJson("{\"firstname\":\"Bill\",\"lastname\":\"Anderson\"}")))
        wiremock.stubFor(get(urlEqualTo("/person/2")).willReturn(aResponse().withStatus(404).withBody("no such person")))
        wiremock.stubFor(get(urlEqualTo("/person/3")).willReturn(aResponse().withStatus(204)))
        wiremock.stubFor(get(urlEqualTo("/person/4")).willReturn(aResponse().withStatus(403)))
        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        assertEquals(PersonDto("Bill", "Anderson"), http.get("/person/{id}", 1).bodyAsOrNull<PersonDto>())
        assertNull(http.get("/person/{id}", 2).bodyAsOrNull<PersonDto>())
        assertNull(http.get("/person/{id}", 3).bodyAsOrNull<PersonDto>())
        val forbidden = assertFailsWith<HttpStatusException> { http.get("/person/{id}", 4).bodyAsOrNull<PersonDto>() }
        assertEquals(403, forbidden.status)
    }

    @Test
    internal fun `HttpStatusException names the request and deserializes the error body with the client's ObjectMapper`() {
        wiremock.stubFor(post(urlEqualTo("/lines?dryRun=true"))
            .willReturn(aResponse().withStatus(422).withBody("{\"product_id\":\"p-1\",\"unit_price\":-1}")))
        val http = HttpClientDsl(
            baseUrl = "http://localhost:${wiremock.port()}",
            objectMapper = defaultObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE),
        )

        val error = assertFailsWith<HttpStatusException> {
            http.post("/lines") {
                queryParam("dryRun", true)
                json(LineDto("p-1", -1))
            }.bodyAs<LineDto>()
        }

        assertEquals(422, error.status)
        assertEquals("POST", error.method)
        assertEquals("http://localhost:${wiremock.port()}/lines?dryRun=true", error.uri.toString())
        // The query string can carry keys or personal data, so the message leaves it out.
        assertEquals("HTTP status 422 for POST http://localhost:${wiremock.port()}/lines", error.message)
        assertEquals(LineDto("p-1", -1), error.bodyAs<LineDto>())
        assertEquals("HTTP status 500", HttpStatusException(500, "boom").message)
    }

    @Test
    internal fun `queryParam repeats the parameter for each element of an Iterable or Array`() {
        wiremock.stubFor(get(urlEqualTo("/search?id=1&id=2&tag=a%20b&tag=c&page=0"))
            .willReturn(aResponse().withStatus(200).withBody("ok")))
        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        val response = http.get("/search") {
            queryParam("id", listOf(1, null, 2))
            queryParam("tag", arrayOf("a b", "c"))
            queryParam("none", emptyList<String>())
            queryParam("page", intArrayOf(0))
        }

        assertEquals("ok", response.body())
    }

    @Test
    internal fun `form sends url-encoded fields as application x-www-form-urlencoded`() {
        wiremock.stubFor(post(urlEqualTo("/oauth/form-token")).willReturn(aResponse().withStatus(200)))
        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        http.post("/oauth/form-token") {
            form(
                "grant_type" to "client_credentials",
                "scope" to "orders:read orders:write",
                "audience" to listOf("api", "admin"),
                "skipped" to null,
                "retries" to 3,
            )
        }

        wiremock.verify(postRequestedFor(urlEqualTo("/oauth/form-token"))
            .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
            .withRequestBody(equalTo(
                "grant_type=client_credentials&scope=orders%3Aread+orders%3Awrite&audience=api&audience=admin&retries=3"
            )))

        val error = assertFailsWith<IllegalStateException> {
            http.get("/oauth/form-token") { form("a" to "b") }
        }
        assertEquals("GET requests cannot have a body", error.message)
    }

    companion object {
        private val wiremock = WireMockServer(WireMockConfiguration()
            .dynamicPort()
            .notifier(ConsoleNotifier(true)))

        @BeforeAll @JvmStatic
        internal fun startWiremock() {
            wiremock.start()
            wiremock.stubFor(get(urlEqualTo("/admin/ping"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withBody("pong")))
        }

        @AfterAll @JvmStatic
        internal fun tearDown() {
            wiremock.stop()
        }
    }
}