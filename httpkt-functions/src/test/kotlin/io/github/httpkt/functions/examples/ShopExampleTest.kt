package io.github.httpkt.functions.examples

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.github.httpkt.functions.HttpClientException
import io.github.httpkt.functions.RequestConfig
import io.github.httpkt.functions.delete
import io.github.httpkt.functions.downloadFile
import io.github.httpkt.functions.get
import io.github.httpkt.functions.getOrNull
import io.github.httpkt.functions.multipart
import io.github.httpkt.functions.patch
import io.github.httpkt.functions.post
import io.github.httpkt.functions.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/*
 * The web shop from the README, written with httpkt-functions: the customer's profile, order
 * history, a photo upload and a receipt download. The code between the "README" markers is what
 * the README shows; the tests below it run that code against WireMock.
 */

// README: shop types
data class CustomerProfile(val id: String, val name: String, val email: String, val memberSince: LocalDate)

data class NewCustomer(val name: String, val email: String)

data class OrderSummary(val id: String, val status: String, val total: BigDecimal)

data class Page<T>(val items: List<T>, val page: Int, val totalPages: Int)

data class ShopError(val code: String, val message: String)

/** "That e-mail is taken" is an answer the caller must handle, so it is a result and not an exception. */
sealed interface RegisterResult {
    data class Registered(val profile: CustomerProfile) : RegisterResult
    data class EmailTaken(val error: ShopError) : RegisterResult
}
// README: end

// README: customer client
class CustomerClient(private val shop: RequestConfig) {

    fun register(customer: NewCustomer): RegisterResult = try {
        RegisterResult.Registered(shop.post("/customers", body = customer))
    } catch (e: HttpClientException) {
        if (e.status != 409) throw e
        RegisterResult.EmailTaken(e.bodyAs())
    }

    fun find(id: String): CustomerProfile? =
        shop.getOrNull("/customers/{id}", pathParams = mapOf("id" to id))      // null on 404

    fun changeEmail(id: String, email: String): CustomerProfile =
        shop.patch("/customers/{id}", body = mapOf("email" to email), pathParams = mapOf("id" to id))

    fun orders(customerId: String, page: Int = 0, status: String? = null): Page<OrderSummary> =
        shop.get(
            "/customers/{id}/orders",
            queryParams = mapOf("page" to page, "status" to status),            // a null filter is left out
            pathParams = mapOf("id" to customerId),
        )

    fun uploadPhoto(customerId: String, photo: Path) {
        shop.put<Unit>(
            "/customers/{id}/photo",
            body = multipart { file("photo", photo, contentType = "image/jpeg") },
            pathParams = mapOf("id" to customerId),
        )
    }

    fun downloadReceipt(orderId: String, destination: Path): Path =
        shop.downloadFile("/orders/{id}/receipt", destination, pathParams = mapOf("id" to orderId))

    fun delete(id: String): Unit =
        shop.delete("/customers/{id}", pathParams = mapOf("id" to id))
}
// README: end

class ShopExampleTest {

    private val customers = CustomerClient(RequestConfig(url = baseUrl()))

    private val kariJson = """{"id":"c-1","name":"Kari Nordmann","email":"kari@example.com","memberSince":"2026-11-28"}"""
    private val kari = CustomerProfile("c-1", "Kari Nordmann", "kari@example.com", LocalDate.parse("2026-11-28"))

    @Test
    fun `register a customer`() {
        wiremock.stubFor(post(urlEqualTo("/customers")).willReturn(aResponse().withStatus(201).withBody(kariJson)))

        val result = customers.register(NewCustomer("Kari Nordmann", "kari@example.com"))

        assertEquals(RegisterResult.Registered(kari), result)
        wiremock.verify(postRequestedFor(urlEqualTo("/customers"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(equalToJson("""{"name":"Kari Nordmann","email":"kari@example.com"}""")))
    }

    @Test
    fun `an e-mail that is taken is a result, not an exception`() {
        wiremock.stubFor(post(urlEqualTo("/customers")).willReturn(aResponse().withStatus(409)
            .withBody("""{"code":"EMAIL_TAKEN","message":"kari@example.com is already registered"}""")))

        val result = customers.register(NewCustomer("Kari Nordmann", "kari@example.com"))

        assertEquals(RegisterResult.EmailTaken(ShopError("EMAIL_TAKEN", "kari@example.com is already registered")), result)
    }

    @Test
    fun `a customer that does not exist is null`() {
        wiremock.stubFor(get(urlEqualTo("/customers/c-1")).willReturn(okJson(kariJson)))
        wiremock.stubFor(get(urlEqualTo("/customers/missing")).willReturn(aResponse().withStatus(404)))

        assertEquals(kari, customers.find("c-1"))
        assertNull(customers.find("missing"))
    }

    @Test
    fun `change the e-mail address of a customer`() {
        wiremock.stubFor(patch(urlEqualTo("/customers/c-1")).willReturn(okJson(kariJson)))

        customers.changeEmail("c-1", "kari@example.com")

        wiremock.verify(patchRequestedFor(urlEqualTo("/customers/c-1"))
            .withRequestBody(equalToJson("""{"email":"kari@example.com"}""")))
    }

    @Test
    fun `page through a customer's orders with an optional filter`() {
        wiremock.stubFor(get(urlPathEqualTo("/customers/c-1/orders")).willReturn(okJson(
            """{"items":[{"id":"o-1","status":"SHIPPED","total":498.00}],"page":0,"totalPages":3}""",
        )))

        val firstPage = customers.orders("c-1")
        customers.orders("c-1", page = 1, status = "SHIPPED")

        assertEquals(Page(listOf(OrderSummary("o-1", "SHIPPED", BigDecimal("498.00"))), page = 0, totalPages = 3), firstPage)
        wiremock.verify(getRequestedFor(urlEqualTo("/customers/c-1/orders?page=0")))
        wiremock.verify(getRequestedFor(urlEqualTo("/customers/c-1/orders?page=1&status=SHIPPED")))
    }

    @Test
    fun `upload a photo as multipart`(@TempDir dir: Path) {
        wiremock.stubFor(put(urlEqualTo("/customers/c-1/photo")).willReturn(aResponse().withStatus(204)))
        val photo = Files.write(dir.resolve("kari.jpg"), byteArrayOf(1, 2, 3))

        customers.uploadPhoto("c-1", photo)

        wiremock.verify(putRequestedFor(urlEqualTo("/customers/c-1/photo"))
            .withHeader("Content-Type", containing("multipart/form-data; boundary="))
            .withRequestBody(containing("""name="photo"; filename="kari.jpg""""))
            .withRequestBody(containing("Content-Type: image/jpeg")))
    }

    @Test
    fun `download a receipt to a file`(@TempDir dir: Path) {
        val pdf = "%PDF-1.7 receipt".toByteArray()
        wiremock.stubFor(get(urlEqualTo("/orders/o-1/receipt")).willReturn(aResponse().withStatus(200).withBody(pdf)))

        val file = customers.downloadReceipt("o-1", dir.resolve("receipt-o-1.pdf"))

        assertArrayEquals(pdf, Files.readAllBytes(file))
    }

    @Test
    fun `delete a customer`() {
        wiremock.stubFor(delete(urlEqualTo("/customers/c-1")).willReturn(aResponse().withStatus(204)))

        customers.delete("c-1")

        wiremock.verify(deleteRequestedFor(urlEqualTo("/customers/c-1")))
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
