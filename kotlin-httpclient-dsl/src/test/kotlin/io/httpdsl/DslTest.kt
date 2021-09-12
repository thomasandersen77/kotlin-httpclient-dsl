package io.httpdsl

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.common.ConsoleNotifier
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.matching.EqualToXmlPattern
import org.junit.jupiter.api.*
import java.time.Duration
import java.time.Duration.*
import java.util.*
import kotlin.test.assertEquals

class DslTest {

    @Test
    internal fun `simple example request`() {
        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        val response = http.get("/admin/ping")

        assertEquals(200, response.statusCode())
        assertEquals("pong", response.bodyAsString())
    }

    @Test
    internal fun `simple post`() {
        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        val response = http.post("/person") {
            request {
                body {
                    json { PersonDto("first", "last") }
                }
            }
        }

        assertEquals(200, response.statusCode())

        wiremock.verify(postRequestedFor(urlEqualTo("/person"))
            .withRequestBody(equalTo("{\"firstame\":\"first\",\"lastname\":\"last\"}")))
    }

    @Test
    internal fun `perform get-request and provide a function that calls ping before the get-request is sent`() {
        val http = HttpClientDsl("http://localhost:${wiremock.port()}")
        val supplierFunction: () -> String = { http.get("/admin/ping").bodyAsString() }

        val response = http.get("/admin/{uriVariable}", "ping") {
            request {
                connectTimeout = ofSeconds(10)
                requestTimeout = ofSeconds(20)
                headers {
                    header("header1", "value1")
                    header("supplier", supplierFunction)
                }
                body {
                    contentType { arrayOf("application/json") }
                    json { PersonDto(firstame = "Bill", lastname = "Anderson") }
                }
            }
        }

        assertEquals(200, response.statusCode())
        assertEquals("pong", response.body())

        wiremock.verify(
            getRequestedFor(urlEqualTo("/admin/ping"))
            .withHeader("Content-type", equalTo("application/json")))
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
            println("simulate login: user=$user pass=$pass")
            return authHeaderValue
        }

        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        // start DSL
        val response = http.post("/xml/{uriVariable}", "ping") {
            request {
                requestTimeout = ofSeconds(20)
                headers {
                    authorization { login("user", "pass") }
                    header("Accept" , "application/xml")
                }
                body {
                    contentType { arrayOf("application/xml") }
                    xml { PersonDto(firstame = "Bill", lastname = "Anderson") }
                }
            }
        }

        // then assert
        assertEquals(200, response.statusCode())
        assertEquals("<status>success</status>", response.bodyAsString())

        wiremock.verify(postRequestedFor(urlEqualTo("/xml/ping"))
            .withHeader("Accept", equalTo("application/xml"))
            .withHeader("Authorization", equalTo(authHeaderValue)))
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
                            .withBody("pong")));

            wiremock.stubFor(post(urlEqualTo("/person"))
                .willReturn(aResponse()
                    .withStatus(200)))
        }

        @AfterAll @JvmStatic
        internal fun tearDown() {
            wiremock.stop()
        }
    }
}