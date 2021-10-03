package no.co_pilot_cli.template.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.common.ConsoleNotifier
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import no.co_pilot_cli.template.client.config.HttpClientException
import no.co_pilot_cli.template.client.config.RequestConfig
import no.co_pilot_cli.template.client.config.mapper
import no.co_pilot_cli.template.client.domain.Foo
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class FooHttpClientTest {
    private val wiremock: WireMockServer = WireMockServer(WireMockConfiguration.options()
            .dynamicPort()
            .notifier(ConsoleNotifier(true)))


    @Test
    fun getFooByNameNotFound() {
        val secureRequestConfig = RequestConfig(
            url = "http://localhost",
            port = wiremock.port(),
            username = "user",
            password = "pass"

        )
        val fooClient = FooHttpClient(config = secureRequestConfig)

        val exception = assertThrows(HttpClientException::class.java) { fooClient.getType("wrong_name", Foo::class.java) }
        assertEquals(404, exception.status)
    }

    @Test
    fun getFooByName() {
        val requestConfig = RequestConfig(
            url = "http://localhost",
            port = wiremock.port(),
            username = "user",
            password = "pass"

        )
        val fooClient = FooHttpClient(config = requestConfig)
        val result = fooClient.getType("bar", Foo::class.java)

        assertNotNull(result)
        assertEquals("bar", result.name)
        wiremock.verify(1, getRequestedFor(urlEqualTo("/api/foo?name=bar"))
            .withHeader("Authorization", equalTo("dXNlcjpwYXNz")))
    }

    @Test
    internal fun postRequestForFoo() {

        val result = FooHttpClient(config = RequestConfig(
            url = "http://localhost",
            port = wiremock.port(),
            username = "user",
            password = "pass"

        )).getTypeByName("bar", Foo("bar"))

        assertNotNull(result)
        assertEquals("bar", result.name)

        wiremock.verify(1, postRequestedFor(
            urlEqualTo("/api/foo?type=bar"))
            .withHeader("Authorization", equalTo("dXNlcjpwYXNz")))
    }

    @BeforeEach
    internal fun setUp() {
        wiremock.start()
        wiremock.stubFor(get(urlEqualTo("/api/foo?name=bar"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(jacksonObjectMapper().writeValueAsString(Foo("bar")))))

        wiremock.stubFor(post(urlEqualTo("/api/foo?type=bar"))
                .withRequestBody(equalToJson(mapper.writeValueAsString(Foo("bar"))))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody(jacksonObjectMapper().writeValueAsString(Foo("bar")))))
    }

    @AfterEach
    internal fun tearDown() {
        wiremock.stop()

    }

    fun jacksonObjectMapper() = ObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).writerWithDefaultPrettyPrinter()
}