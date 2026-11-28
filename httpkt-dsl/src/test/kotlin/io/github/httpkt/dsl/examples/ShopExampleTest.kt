package io.github.httpkt.dsl.examples

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.github.httpkt.dsl.HttpKt
import io.github.httpkt.dsl.bodyAs
import io.github.httpkt.dsl.bodyAsOrNull
import io.github.httpkt.dsl.requireSuccess
import io.github.httpkt.dsl.toStatusException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/*
 * The web shop from the README, written with httpkt-dsl: products, the customer's cart and
 * orders. The code between the "README" markers is what the README shows; the tests below it
 * run that code against WireMock and show what goes over the wire.
 */

// README: shop types
data class Product(val id: String, val name: String, val price: BigDecimal)

data class CartLine(val productId: String, val quantity: Int)

data class Cart(val customerId: String, val lines: List<CartLine>)

data class Address(val street: String, val postalCode: String, val city: String)

data class NewOrder(val customerId: String, val deliveryAddress: Address)

data class Order(
    val id: String,
    val status: String,
    val total: BigDecimal,
    val deliveryAddress: Address,
    val createdAt: Instant,
)

data class ShopError(val code: String, val message: String)

/** "Out of stock" is an answer the caller must handle, so it is a result and not an exception. */
sealed interface PlaceOrderResult {
    data class Placed(val order: Order) : PlaceOrderResult
    data class OutOfStock(val error: ShopError) : PlaceOrderResult
}
// README: end

// README: shop client
class ShopClient(private val http: HttpKt) {

    fun searchProducts(category: String, inStock: Boolean = true): List<Product> =
        http.get("/products") {
            queryParam("category", category)
            queryParam("inStock", inStock)
        }.bodyAs()

    fun addToCart(customerId: String, productId: String, quantity: Int): Cart =
        http.put("/customers/{customerId}/cart/items/{productId}", customerId, productId) {
            json(mapOf("quantity" to quantity))
        }.bodyAs()

    fun placeOrder(order: NewOrder): PlaceOrderResult {
        val response = http.post("/orders") { json(order) }
        return when (response.statusCode()) {
            409 -> PlaceOrderResult.OutOfStock(response.toStatusException().bodyAs())
            else -> PlaceOrderResult.Placed(response.bodyAs())    // throws unless 2xx
        }
    }

    fun changeDeliveryAddress(orderId: String, address: Address): Order =
        http.patch("/orders/{id}", orderId) { json(mapOf("deliveryAddress" to address)) }.bodyAs()

    fun findOrder(orderId: String): Order? =
        http.get("/orders/{id}", orderId).bodyAsOrNull()          // null on 404

    fun cancelOrder(orderId: String) {
        http.delete("/orders/{id}", orderId).requireSuccess()     // throws unless 2xx
    }
}
// README: end

class ShopExampleTest {

    private val shop = ShopClient(HttpKt(baseUrl()))

    private val home = Address("Storgata 1", "0155", "Oslo")
    private val orderJson = """
        {"id":"o-1","status":"PLACED","total":498.00,
         "deliveryAddress":{"street":"Storgata 1","postalCode":"0155","city":"Oslo"},
         "createdAt":"2026-11-28T10:15:30Z"}
    """.trimIndent()

    @Test
    fun `search products with query parameters`() {
        wiremock.stubFor(get(urlEqualTo("/products?category=books&inStock=true"))
            .willReturn(okJson("""[{"id":"p-1","name":"Kotlin in Action","price":499.00}]""")))

        val products = shop.searchProducts(category = "books")

        assertEquals(listOf(Product("p-1", "Kotlin in Action", BigDecimal("499.00"))), products)
    }

    @Test
    fun `put a product in the customer's cart`() {
        wiremock.stubFor(put(urlEqualTo("/customers/c-1/cart/items/p-1"))
            .willReturn(okJson("""{"customerId":"c-1","lines":[{"productId":"p-1","quantity":2}]}""")))

        val cart = shop.addToCart(customerId = "c-1", productId = "p-1", quantity = 2)

        assertEquals(Cart("c-1", listOf(CartLine("p-1", 2))), cart)
        wiremock.verify(putRequestedFor(urlEqualTo("/customers/c-1/cart/items/p-1"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(equalToJson("""{"quantity":2}""")))
    }

    @Test
    fun `place an order from the cart`() {
        wiremock.stubFor(post(urlEqualTo("/orders")).willReturn(aResponse().withStatus(201).withBody(orderJson)))

        val result = shop.placeOrder(NewOrder("c-1", home))

        val placed = assertIs<PlaceOrderResult.Placed>(result)
        assertEquals("o-1", placed.order.id)
        assertEquals(Instant.parse("2026-11-28T10:15:30Z"), placed.order.createdAt)
        wiremock.verify(postRequestedFor(urlEqualTo("/orders")).withRequestBody(equalToJson(
            """{"customerId":"c-1","deliveryAddress":{"street":"Storgata 1","postalCode":"0155","city":"Oslo"}}""",
        )))
    }

    @Test
    fun `an out-of-stock answer is a result, not an exception`() {
        wiremock.stubFor(post(urlEqualTo("/orders")).willReturn(aResponse().withStatus(409)
            .withBody("""{"code":"OUT_OF_STOCK","message":"p-1 is sold out"}""")))

        val result = shop.placeOrder(NewOrder("c-1", home))

        assertEquals(PlaceOrderResult.OutOfStock(ShopError("OUT_OF_STOCK", "p-1 is sold out")), result)
    }

    @Test
    fun `change the delivery address of an order`() {
        wiremock.stubFor(patch(urlEqualTo("/orders/o-1")).willReturn(okJson(orderJson)))

        val order = shop.changeDeliveryAddress("o-1", home)

        assertEquals(home, order.deliveryAddress)
        wiremock.verify(patchRequestedFor(urlEqualTo("/orders/o-1")).withRequestBody(equalToJson(
            """{"deliveryAddress":{"street":"Storgata 1","postalCode":"0155","city":"Oslo"}}""",
        )))
    }

    @Test
    fun `an order that does not exist is null`() {
        wiremock.stubFor(get(urlEqualTo("/orders/o-1")).willReturn(okJson(orderJson)))
        wiremock.stubFor(get(urlEqualTo("/orders/missing")).willReturn(aResponse().withStatus(404)))

        assertEquals("PLACED", shop.findOrder("o-1")?.status)
        assertNull(shop.findOrder("missing"))
    }

    @Test
    fun `cancel an order`() {
        wiremock.stubFor(delete(urlEqualTo("/orders/o-1")).willReturn(aResponse().withStatus(204)))

        shop.cancelOrder("o-1")

        wiremock.verify(deleteRequestedFor(urlEqualTo("/orders/o-1")))
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
