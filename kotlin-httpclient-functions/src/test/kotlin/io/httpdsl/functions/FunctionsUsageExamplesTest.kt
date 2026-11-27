package io.httpdsl.functions

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.LocalDate

/*
 * Usage recipes for the functions module. Each test is a small, self-contained example of how
 * to build a typed client on RequestConfig. The README links here, so keep the examples readable.
 */

data class Customer(val id: String, val name: String, val since: LocalDate)

data class NewCustomer(val name: String)

data class Page<T>(val items: List<T>, val page: Int, val totalPages: Int)

data class Problem(val title: String, val detail: String)

/** Expected outcomes as a closed set, so callers handle "already exists" without catching exceptions. */
sealed interface CreateCustomerResult {
    data class Created(val customer: Customer) : CreateCustomerResult
    data class AlreadyExists(val problem: Problem) : CreateCustomerResult
}

class CustomerClient(private val config: RequestConfig) {

    /** `{id}` is replaced with the URL-encoded value from pathParams. */
    fun find(id: String): Customer? =
        config.getOrNull("/customers/{id}", pathParams = mapOf("id" to id))

    /** Null query parameters are skipped, so optional filters can be passed as they are. */
    fun list(page: Int = 0, size: Int = 20, nameFilter: String? = null): Page<Customer> =
        config.get("/customers", queryParams = mapOf("page" to page, "size" to size, "name" to nameFilter))

    fun create(customer: NewCustomer): CreateCustomerResult = try {
        CreateCustomerResult.Created(config.post("/customers", body = customer))
    } catch (e: HttpClientException) {
        if (e.status != 409) throw e
        CreateCustomerResult.AlreadyExists(config.objectMapper.readValue(e.body))
    }

    fun rename(id: String, name: String): Customer =
        config.patch("/customers/{id}", body = mapOf("name" to name), pathParams = mapOf("id" to id))

    fun remove(id: String): Unit =
        config.delete("/customers/{id}", pathParams = mapOf("id" to id))

    /** exchange gives the status and the response headers as well as the body. */
    fun createAndLocate(customer: NewCustomer): Pair<Customer, String?> {
        val created = config.exchange<Customer>(Method.POST, "/customers", body = customer)
        return created.body to created.header("Location")
    }
}

class FunctionsUsageExamplesTest {

    private val adaJson = """{"id":"c-1","name":"Ada","since":"2020-01-15"}"""
    private val ada = Customer("c-1", "Ada", LocalDate.of(2020, 1, 15))

    @Test
    fun `a typed client reads, pages, updates and deletes`() {
        wiremock.stubFor(get(urlEqualTo("/customers/c-1")).willReturn(okJson(adaJson)))
        wiremock.stubFor(get(urlEqualTo("/customers/unknown")).willReturn(aResponse().withStatus(404)))
        wiremock.stubFor(get(urlEqualTo("/customers?page=1&size=20&name=Ad"))
            .willReturn(okJson("""{"items":[$adaJson],"page":1,"totalPages":3}""")))
        wiremock.stubFor(patch(urlEqualTo("/customers/c-1"))
            .willReturn(okJson("""{"id":"c-1","name":"Ada L.","since":"2020-01-15"}""")))
        wiremock.stubFor(delete(urlEqualTo("/customers/c-1")).willReturn(aResponse().withStatus(204)))

        val customers = CustomerClient(RequestConfig(url = baseUrl(), authorization = Authorization.bearer("dummy-token")))

        assertEquals(ada, customers.find("c-1"))
        assertNull(customers.find("unknown"))
        assertEquals(Page(listOf(ada), page = 1, totalPages = 3), customers.list(page = 1, nameFilter = "Ad"))
        assertEquals("Ada L.", customers.rename("c-1", "Ada L.").name)
        customers.remove("c-1")

        wiremock.verify(patchRequestedFor(urlEqualTo("/customers/c-1"))
            .withHeader("Authorization", equalTo("Bearer dummy-token"))
            .withRequestBody(equalToJson("""{"name":"Ada L."}""")))
        wiremock.verify(deleteRequestedFor(urlEqualTo("/customers/c-1")))
    }

    @Test
    fun `map an expected 409 to a domain result instead of an exception`() {
        wiremock.stubFor(post(urlEqualTo("/customers")).withRequestBody(equalToJson("""{"name":"Ada"}"""))
            .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(adaJson)))
        wiremock.stubFor(post(urlEqualTo("/customers")).withRequestBody(equalToJson("""{"name":"Grace"}"""))
            .willReturn(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                .withBody("""{"title":"Conflict","detail":"Grace already exists"}""")))

        val customers = CustomerClient(RequestConfig(url = baseUrl()))

        assertEquals(CreateCustomerResult.Created(ada), customers.create(NewCustomer("Ada")))
        assertEquals(
            CreateCustomerResult.AlreadyExists(Problem("Conflict", "Grace already exists")),
            customers.create(NewCustomer("Grace")),
        )
    }

    @Test
    fun `read the Location header of a created resource with exchange`() {
        wiremock.stubFor(post(urlEqualTo("/customers"))
            .willReturn(aResponse().withStatus(201).withHeader("Location", "/customers/c-1").withBody(adaJson)))

        val (customer, location) = CustomerClient(RequestConfig(url = baseUrl())).createAndLocate(NewCustomer("Ada"))

        assertEquals(ada, customer)
        assertEquals("/customers/c-1", location)
    }

    @Test
    fun `derive configs with copy, for example one per tenant`() {
        wiremock.stubFor(get(urlEqualTo("/customers/c-1")).willReturn(okJson(adaJson)))
        val base = RequestConfig(url = baseUrl(), headers = mapOf("Accept-Language" to "nb"))
        val acme = base.copy(headers = base.headers + ("X-Tenant" to "acme"))

        CustomerClient(acme).find("c-1")

        wiremock.verify(getRequestedFor(urlEqualTo("/customers/c-1"))
            .withHeader("Accept-Language", equalTo("nb"))
            .withHeader("X-Tenant", equalTo("acme")))
    }

    @Test
    fun `a base url with a path prefix keeps the prefix`() {
        wiremock.stubFor(get(urlEqualTo("/api/v1/customers/c-1")).willReturn(okJson(adaJson)))

        val customer: Customer = RequestConfig(url = "${baseUrl()}/api/v1").get("/customers/c-1")

        assertEquals(ada, customer)
    }

    @Test
    fun `use a copied ObjectMapper to tolerate unknown fields`() {
        wiremock.stubFor(get(urlEqualTo("/customers/c-1"))
            .willReturn(okJson("""{"id":"c-1","name":"Ada","since":"2020-01-15","segment":"gold"}""")))
        val strict = RequestConfig(url = baseUrl())
        // defaultObjectMapper() is a fresh copy of the library's default, so other configs are unaffected.
        val tolerant = strict.copy(
            objectMapper = defaultObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
        )

        assertThrows(UnrecognizedPropertyException::class.java) { strict.get<Customer>("/customers/c-1") }
        assertEquals(ada, tolerant.get<Customer>("/customers/c-1"))
    }

    @Test
    fun `fetch a token once and again after a 401 with cachedBearer`() {
        wiremock.stubFor(post(urlEqualTo("/oauth/token")).willReturn(aResponse().withStatus(200).withBody("token-from-auth-server")))
        wiremock.stubFor(get(urlEqualTo("/customers/c-1")).willReturn(okJson(adaJson)))
        // The token request uses its own config without authorization.
        val authServer = RequestConfig(url = baseUrl())
        val config = RequestConfig(
            url = baseUrl(),
            authorization = Authorization.cachedBearer {
                authServer.post<String>(
                    "/oauth/token",
                    body = "grant_type=client_credentials",
                    contentType = "application/x-www-form-urlencoded",
                )
            },
        )

        config.get<Customer>("/customers/c-1")
        config.get<Customer>("/customers/c-1")

        wiremock.verify(1, postRequestedFor(urlEqualTo("/oauth/token")).withoutHeader("Authorization"))
        wiremock.verify(2, getRequestedFor(urlEqualTo("/customers/c-1"))
            .withHeader("Authorization", equalTo("Bearer token-from-auth-server")))
    }

    @Test
    fun `other schemes are a lambda away`() {
        wiremock.stubFor(get(urlEqualTo("/customers/c-1")).willReturn(okJson(adaJson)))
        val config = RequestConfig(url = baseUrl(), authorization = { "ApiKey dummy-key" })

        config.get<Customer>("/customers/c-1")

        wiremock.verify(getRequestedFor(urlEqualTo("/customers/c-1")).withHeader("Authorization", equalTo("ApiKey dummy-key")))
    }

    @Test
    fun `multi-value query parameters go in the path, single values in queryParams`() {
        wiremock.stubFor(get(urlEqualTo("/customers?id=c-1&id=c-2&size=2"))
            .willReturn(okJson("""{"items":[],"page":0,"totalPages":0}""")))

        // queryParams is a Map, so a repeated name has to be written into the path.
        val page: Page<Customer> = RequestConfig(url = baseUrl())
            .get("/customers?id=c-1&id=c-2", queryParams = mapOf("size" to 2))

        assertEquals(0, page.totalPages)
    }

    @Test
    fun `a short request timeout fails fast with HttpTimeoutException`() {
        wiremock.stubFor(get(urlEqualTo("/slow")).willReturn(aResponse().withStatus(200).withFixedDelay(1_000)))
        val config = RequestConfig(url = baseUrl(), requestTimeout = Duration.ofMillis(100))

        assertThrows(HttpTimeoutException::class.java) { config.get<String>("/slow") }
    }

    @BeforeEach
    fun resetStubs() {
        wiremock.resetAll()
    }

    companion object {
        private val wiremock = WireMockServer(WireMockConfiguration.options().dynamicPort())

        private fun baseUrl() = "http://localhost:${wiremock.port()}"

        @BeforeAll @JvmStatic
        fun start() = wiremock.start()

        @AfterAll @JvmStatic
        fun stop() = wiremock.stop()
    }
}
