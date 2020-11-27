package io.httpdsl

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.common.ConsoleNotifier
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.matching.EqualToXmlPattern
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*
import kotlin.test.assertEquals

class DslTest {

    private val wiremock = WireMockServer(WireMockConfiguration()
            .dynamicPort()
            .notifier(ConsoleNotifier(true)))

    @Test
    internal fun `simple example request`() {
        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        val response = http.get("/admin/ping") 

        assertEquals(200, response.statusCode())
        assertEquals("pong", response.bodyString())
    }

    @Test
    internal fun `perform get-request and prvoide a function that calls ping before get-request is sent`() {
        val http = HttpClientDsl("http://localhost:${wiremock.port()}")
        val supplierFunction: () -> String = { http.get("/admin/ping").bodyString() }

        val response = http.get("/admin/{uriVariable}", "ping") {
            request {
                timeout { 20 }
                headers {
                    header("header1", "value1")
                    header("supplier", supplierFunction)
                }
                body {
                    contentType {
                        arrayOf("application/json")
                    }
                    json { PersonDto(firstame = "Bill", lastname = "Anderson") }
                }
            }
        }

        assertEquals(200, response.statusCode())
        assertEquals("pong", response.body())
    }

    @Test
    internal fun `post an xml-body and add a function for providing authorization header in dsl`() {
        // set up
        val authHeaderValue = UUID.randomUUID().toString()
        wiremock.stubFor(post(urlEqualTo("/xml/ping"))
                .withHeader("Authorization", equalTo(authHeaderValue))
                .withHeader("Content-Type", equalTo("application/xml"))
                .withRequestBody(
                        EqualToXmlPattern("<PersonDto><firstame>Bill</firstame><lastname>Anderson</lastname></PersonDto>"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("<status>success</status>")))

        fun login(user: String, pass: String): String { // anonymous function for simulating a login
            println("simuler login med user=$user pass=$pass")
            return authHeaderValue
        }

        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        // start DSL
        val response = http.post("/xml/{uriVariable}", "ping") {
            request {
                timeout { 20 }
                headers {
                    authorization { login("user", "pass") }
                }
                body {
                    contentType { arrayOf("application/xml") }
                    xml { PersonDto(firstame = "Bill", lastname = "Anderson") }
                }
            }
        }

        // then assert
        assertEquals(200, response.statusCode())
        assertEquals("<status>success</status>", response.bodyString())
    }

    @BeforeEach
    internal fun startWiremock() {
        wiremock.start()
        wiremock.stubFor(get(urlEqualTo("/admin/ping"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("pong")));
    }

    @AfterEach
    internal fun tearDown() {
        wiremock.stop()
    }
}