package io.httpdsl

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.common.ConsoleNotifier
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.matching.EqualToXmlPattern
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import java.util.*
import kotlin.test.assertEquals

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [DslTest.Config::class])
class DslTest {

    @Autowired
    lateinit var http : HttpClientDsl

    @Test
    internal fun `ping samhandling-webservice med httpclient DSL`() {
        val supplierFunction : () -> String =  { http.get("/admin/ping").body().toString() }

        val response = http.get("/admin/{uriVariable}", "ping") {
            request {
                requestTimeout { 20 }
                headers {
                    header("header1", "value1")
                    header("supplier", supplierFunction)
                    authorization {
                        login("user", "password")
                    }
                }
                body {
                    contentType {
                        arrayOf("application/json")
                    }
                    json { PersonDto( firstame = "Bill", lastname = "Anderson") }
                }
            }
        }

        assertEquals(200, response.statusCode())
        assertEquals("pong", response.body())
    }

    private val wiremock = WireMockServer(WireMockConfiguration()
            .dynamicPort().notifier(ConsoleNotifier(true)))

    @Test
    internal fun `post xml til wiremock`() {
        // sett opp mock endepunkt
        val authHeaderValue = UUID.randomUUID().toString()
        wiremock.start()
        wiremock.stubFor(post(urlEqualTo("/xml/ping"))
                .withHeader("Authorization", equalTo(authHeaderValue))
                .withHeader("Content-Type", equalTo("application/xml"))
                .withHeader("pong", equalTo("pong")) // skal matche resultat av supplierFunction
                .withRequestBody(
                        EqualToXmlPattern("<PersonDto><firstame>Bill</firstame><lastname>Anderson</lastname></PersonDto>"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("<status>success</status>")))

        // lokal login funksjon så auth header kan assertes av wiremock
        fun login(user: String, pass: String) : String {
            println("simuler login med user=$user pass=$pass")
            return authHeaderValue
        }

        // funksjon som skal trigges i det header skal populeres i en request
        val supplierFunction : () -> String =  { http.get("/admin/ping").body().toString() }
        // lag ny http client som peker på wiremock, ikke samhandling-webservice som den brukt i funksjonen ovenfor
        val http = HttpClientDsl("http://localhost:${wiremock.port()}")

        // start DSL
        val response = http.post("/xml/{uriVariable}", "ping") {
            request {
                requestTimeout { 20 }
                headers {
                    header("header1", "value1")
                    header("pong", supplierFunction)
                    authorization {
                        login("user", "password")
                    }
                }
                body {
                    contentType {
                        arrayOf("application/xml")
                    }
                    xml { PersonDto(firstame = "Bill", lastname = "Anderson") }
                }
            }
        }

        // assert
        assertEquals(200, response.statusCode())
        assertEquals("<status>success</status>", response.body())

        wiremock.stop()
    }

    private fun login(user: String, password: String) : String {
        val token = UUID.randomUUID().toString()
        // logger liksom inn
        println("log in with username = $user, password = $password. token: $token")
        return token
    }

    class Config {
        @Bean
        fun dsl() : HttpClientDsl {
            return HttpClientDsl("http://samhandling-webservice.kpt.spk.no")
        }
    }
}