package io.github.httpkt.dsl

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/*
 * Usage recipes for the DSL module. Each test is a small, self-contained example of how to
 * use HttpClientDsl in application code. The WireMock stubs and verifications show exactly
 * what goes over the wire. The README links here, so keep the examples readable.
 */

data class OrderDto(val id: Long, val status: String, val createdAt: Instant)

data class NewOrderDto(val customer: String, val items: List<String>)

data class ApiErrorDto(val code: String, val message: String)

/** A read model that only needs some of the fields the server sends. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OrderSummaryDto(val id: Long, val status: String)

/**
 * Recommended shape for application code: a small typed client that owns the paths and hides
 * `HttpResponse` from its callers.
 */
class OrderClient(private val http: HttpClientDsl) {

    /** 404 is an expected answer here, so it becomes `null` instead of an exception. */
    fun find(id: Long): OrderDto? =
        http.get("/orders/{id}", id).bodyAsOrNull()

    fun search(status: String, page: Int = 0): List<OrderDto> =
        http.get("/orders") {
            queryParam("status", status)
            queryParam("page", page)
        }.bodyAs()

    /** Returns the `Location` of the created order; response headers are on the `HttpResponse`. */
    fun create(order: NewOrderDto): URI {
        val response = http.post("/orders") { json(order) }
        response.bodyAs<Unit>()   // throws HttpStatusException, naming the request, unless 2xx
        return URI.create(response.header("Location") ?: error("created, but no Location header"))
    }

    /** `bodyAs<Unit>()` is a compact way to say "any 2xx is fine, throw otherwise". */
    fun cancel(id: Long) {
        http.delete("/orders/{id}", id).bodyAs<Unit>()
    }
}

/* Your own DSL words: plain extension functions on RequestDsl. */
private fun RequestDsl.tenant(id: String) = header("X-Tenant", id)
private fun RequestDsl.acceptJson() = header("Accept", "application/json")

data class IssuedToken(val value: String, val expiresAt: Instant)

/**
 * Example of a custom [Authorization]: refresh the token shortly before it expires, and also
 * after a 401. [onUnauthorized] is told which header value was rejected; this implementation
 * simply drops whatever it holds.
 */
class ExpiringTokenAuthorization(
    private val now: () -> Instant,
    private val margin: Duration = Duration.ofSeconds(30),
    private val fetch: () -> IssuedToken,
) : Authorization {

    private var current: IssuedToken? = null

    @Synchronized
    override fun headerValue(): String {
        val token = current?.takeIf { now() < it.expiresAt.minus(margin) } ?: fetch().also { current = it }
        return "Bearer ${token.value}"
    }

    @Synchronized
    override fun onUnauthorized(rejectedHeaderValue: String): Boolean {
        current = null
        return true
    }
}

class DslUsageExamplesTest {

    private val openOrderJson = """{"id":1,"status":"OPEN","createdAt":"2026-11-27T10:15:30Z"}"""
    private val openOrder = OrderDto(1, "OPEN", Instant.parse("2026-11-27T10:15:30Z"))

    @Test
    fun `wrap HttpClientDsl in a small typed client`() {
        wiremock.stubFor(get(urlEqualTo("/orders/1")).willReturn(okJson(openOrderJson)))
        wiremock.stubFor(get(urlEqualTo("/orders/2")).willReturn(aResponse().withStatus(404)))
        wiremock.stubFor(get(urlEqualTo("/orders?status=OPEN&page=0")).willReturn(okJson("[$openOrderJson]")))
        wiremock.stubFor(post(urlEqualTo("/orders"))
            .willReturn(aResponse().withStatus(201).withHeader("Location", "/orders/3")))
        wiremock.stubFor(delete(urlEqualTo("/orders/1")).willReturn(aResponse().withStatus(204)))

        val orders = OrderClient(HttpClientDsl(baseUrl()))

        assertEquals(openOrder, orders.find(1))
        assertNull(orders.find(2))
        assertEquals(listOf(openOrder), orders.search("OPEN"))
        assertEquals(URI.create("/orders/3"), orders.create(NewOrderDto("acme", listOf("book"))))
        orders.cancel(1)

        wiremock.verify(postRequestedFor(urlEqualTo("/orders"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(equalToJson("""{"customer":"acme","items":["book"]}""")))
        wiremock.verify(deleteRequestedFor(urlEqualTo("/orders/1")))
    }

    @Test
    fun `add your own DSL words with extension functions on RequestDsl`() {
        wiremock.stubFor(get(urlEqualTo("/orders?status=OPEN")).willReturn(okJson("[]")))
        val http = HttpClientDsl(baseUrl())

        val orders = http.get("/orders") {
            tenant("acme")
            acceptJson()
            queryParam("status", "OPEN")
        }.bodyAs<List<OrderDto>>()

        assertEquals(emptyList(), orders)
        wiremock.verify(getRequestedFor(urlEqualTo("/orders?status=OPEN"))
            .withHeader("X-Tenant", equalTo("acme"))
            .withHeader("Accept", equalTo("application/json")))
    }

    @Test
    fun `set headers and the request timeout once on the client`() {
        wiremock.stubFor(get(urlEqualTo("/orders")).willReturn(okJson("[]")))
        wiremock.stubFor(get(urlEqualTo("/orders/export")).willReturn(aResponse().withStatus(200).withBody("id;status")))
        val http = HttpClientDsl(
            baseUrl = baseUrl(),
            defaultHeaders = mapOf("Accept" to "application/json", "X-Tenant" to "acme"),
            requestTimeout = Duration.ofSeconds(5),
        )

        http.get("/orders")
        http.get("/orders/export") {
            header("Accept", "text/csv")                 // replaces the default for this request
            requestTimeout = Duration.ofSeconds(60)      // this one is allowed to take longer
        }

        wiremock.verify(getRequestedFor(urlEqualTo("/orders"))
            .withHeader("Accept", equalTo("application/json"))
            .withHeader("X-Tenant", equalTo("acme")))
        val export = wiremock.findAll(getRequestedFor(urlEqualTo("/orders/export"))).single()
        assertEquals(listOf("text/csv"), export.headers.getHeader("Accept").values())
        assertEquals(listOf("acme"), export.headers.getHeader("X-Tenant").values())
    }

    @Test
    fun `a base url with a path prefix keeps the prefix`() {
        wiremock.stubFor(get(urlEqualTo("/api/v1/orders/7")).willReturn(okJson("""{"id":7,"status":"SHIPPED","createdAt":"2026-11-27T10:15:30Z"}""")))
        val http = HttpClientDsl("${baseUrl()}/api/v1/")

        val order = http.get("orders/{id}", 7).bodyAs<OrderDto>()

        assertEquals("SHIPPED", order.status)
    }

    @Test
    fun `read plain text with body() or bodyAs String`() {
        wiremock.stubFor(get(urlEqualTo("/health"))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/plain").withBody("UP")))
        wiremock.stubFor(get(urlEqualTo("/maintenance"))
            .willReturn(aResponse().withStatus(503).withHeader("Content-Type", "text/plain").withBody("DOWN")))
        val http = HttpClientDsl(baseUrl())

        // body() returns the text for any status; bodyAs<String>() returns it unparsed but throws outside 2xx.
        assertEquals("UP", http.get("/health").body())
        assertEquals("UP", http.get("/health").bodyAs<String>())
        assertEquals("DOWN", http.get("/maintenance").body())
        assertFailsWith<HttpStatusException> { http.get("/maintenance").bodyAs<String>() }
    }

    @Test
    fun `turn an error response into a domain error`() {
        val problemJson = """{"code":"EMPTY_ORDER","message":"An order needs at least one item"}"""
        wiremock.stubFor(post(urlEqualTo("/orders"))
            .willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json").withBody(problemJson)))
        val http = HttpClientDsl(baseUrl())

        // Either let bodyAs throw and read the error body from the exception ...
        val error = assertFailsWith<HttpStatusException> {
            http.post("/orders") { json(NewOrderDto("acme", emptyList())) }.bodyAs<OrderDto>()
        }
        assertEquals(422, error.status)
        assertEquals("HTTP status 422 for POST ${baseUrl()}/orders", error.message)
        assertEquals("EMPTY_ORDER", error.bodyAs<ApiErrorDto>().code)

        // ... or branch on the status before deserializing anything, with the client's ObjectMapper.
        val response = http.post("/orders") { json(NewOrderDto("acme", emptyList())) }
        val outcome = when (response.statusCode()) {
            201 -> "created"
            422 -> http.objectMapper.readValue<ApiErrorDto>(response.body()).message
            else -> throw response.toStatusException()
        }
        assertEquals("An order needs at least one item", outcome)
    }

    @Test
    fun `send an url-encoded form body`() {
        wiremock.stubFor(post(urlEqualTo("/oauth/token")).willReturn(okJson("""{"access_token":"abc"}""")))
        val http = HttpClientDsl(baseUrl(), authorization = Authorization.bearer("not-for-the-token-endpoint"))

        http.post("/oauth/token") {
            withoutAuthorization()
            form("grant_type" to "client_credentials", "scope" to "orders:read orders:write")
        }

        wiremock.verify(postRequestedFor(urlEqualTo("/oauth/token"))
            .withoutHeader("Authorization")
            .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
            .withRequestBody(equalTo("grant_type=client_credentials&scope=orders%3Aread+orders%3Awrite")))
    }

    @Test
    fun `pass a list to queryParam for a multi-value parameter`() {
        wiremock.stubFor(get(urlEqualTo("/orders?id=1&id=2&id=3")).willReturn(okJson("[]")))
        val http = HttpClientDsl(baseUrl())

        val response = http.get("/orders") {
            queryParam("id", listOf(1, 2, 3))
        }

        assertEquals(200, response.statusCode())
    }

    @Test
    fun `ignore JSON fields you do not need, per DTO or for the whole client`() {
        wiremock.stubFor(get(urlEqualTo("/orders/1"))
            .willReturn(okJson("""{"id":1,"status":"OPEN","createdAt":"2026-11-27T10:15:30Z","internalNote":"vip"}""")))

        // Per DTO: @JsonIgnoreProperties(ignoreUnknown = true) on the class.
        val strict = HttpClientDsl(baseUrl())
        assertEquals(OrderSummaryDto(1, "OPEN"), strict.get("/orders/1").bodyAs<OrderSummaryDto>())

        // For every response of a client: start from defaultObjectMapper() and adjust it.
        val tolerant = HttpClientDsl(
            baseUrl = baseUrl(),
            objectMapper = defaultObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
        )
        assertEquals(openOrder, tolerant.get("/orders/1").bodyAs<OrderDto>())
    }

    @Test
    fun `a short request timeout fails fast with HttpTimeoutException`() {
        wiremock.stubFor(get(urlEqualTo("/slow")).willReturn(aResponse().withStatus(200).withFixedDelay(1_000)))
        val http = HttpClientDsl(baseUrl())

        assertFailsWith<HttpTimeoutException> {
            http.get("/slow") { requestTimeout = Duration.ofMillis(100) }
        }
    }

    @Test
    fun `implement Authorization yourself for expiry-based token refresh`() {
        wiremock.stubFor(get(urlEqualTo("/orders")).willReturn(okJson("[]")))
        var clock = Instant.parse("2026-11-27T10:00:00Z")
        var issued = 0
        val http = HttpClientDsl(
            baseUrl = baseUrl(),
            authorization = ExpiringTokenAuthorization(now = { clock }) {
                IssuedToken("token-${++issued}", expiresAt = clock.plusSeconds(300))
            },
        )

        http.get("/orders")
        clock = clock.plusSeconds(60)      // still valid: reused
        http.get("/orders")
        clock = clock.plusSeconds(240)     // inside the 30 s margin: refreshed
        http.get("/orders")

        assertEquals(2, issued)
        wiremock.verify(2, getRequestedFor(urlEqualTo("/orders")).withHeader("Authorization", equalTo("Bearer token-1")))
        wiremock.verify(1, getRequestedFor(urlEqualTo("/orders")).withHeader("Authorization", equalTo("Bearer token-2")))

        // A 401 also makes it fetch again, and the request is sent once more with the new token.
        wiremock.stubFor(get(urlEqualTo("/revoked")).withHeader("Authorization", equalTo("Bearer token-2"))
            .willReturn(aResponse().withStatus(401)))
        wiremock.stubFor(get(urlEqualTo("/revoked")).withHeader("Authorization", equalTo("Bearer token-3"))
            .willReturn(aResponse().withStatus(200)))
        assertEquals(200, http.get("/revoked").statusCode())
        assertEquals(3, issued)
    }

    @BeforeEach
    fun resetStubs() {
        wiremock.resetAll()
    }

    companion object {
        private val wiremock = WireMockServer(WireMockConfiguration().dynamicPort())

        private fun baseUrl() = "http://localhost:${wiremock.port()}"

        @BeforeAll @JvmStatic
        fun start() = wiremock.start()

        @AfterAll @JvmStatic
        fun stop() = wiremock.stop()
    }
}
