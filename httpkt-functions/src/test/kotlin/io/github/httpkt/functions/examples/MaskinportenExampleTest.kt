package io.github.httpkt.functions.examples

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.github.httpkt.functions.Authorization
import io.github.httpkt.functions.ExpiringToken
import io.github.httpkt.functions.RequestConfig
import io.github.httpkt.functions.formBody
import io.github.httpkt.functions.get
import io.github.httpkt.functions.post
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/*
 * Calling an API that is protected by Maskinporten, with httpkt-functions. The client signs a JWT
 * grant with its own key, exchanges it for an access token, and uses that token until shortly
 * before it expires. The code between the "README" markers is what the README shows.
 *
 * Protocol: https://docs.digdir.no/docs/Maskinporten/maskinporten_protocol_jwtgrant
 */

// README: maskinporten
@JsonIgnoreProperties(ignoreUnknown = true)
data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("expires_in") val expiresIn: Long,
)

class Maskinporten(
    baseUrl: String,                       // "https://test.maskinporten.no"
    private val audience: String,          // "https://test.maskinporten.no/", production "https://maskinporten.no/"
    private val clientId: String,
    private val scope: String,
    private val keyId: String,
    private val privateKey: PrivateKey,
) {
    // No authorization on this config: the signed grant is what identifies us.
    private val maskinporten = RequestConfig(url = baseUrl)

    fun fetchToken(): ExpiringToken {
        val response: TokenResponse = maskinporten.post(
            "/token",
            body = formBody(
                "grant_type" to "urn:ietf:params:oauth:grant-type:jwt-bearer",
                "assertion" to signedGrant(),
            ),
        )
        return ExpiringToken(response.accessToken, expiresIn = Duration.ofSeconds(response.expiresIn))
    }

    /** An RS256-signed JWT, made with the JDK alone. */
    private fun signedGrant(): String {
        val now = Instant.now().epochSecond
        val header = mapOf("alg" to "RS256", "kid" to keyId)
        val claims = mapOf(
            "aud" to audience,
            "iss" to clientId,
            "scope" to scope,
            "iat" to now,
            "exp" to now + 120,
            "jti" to UUID.randomUUID().toString(),
        )
        val json = maskinporten.objectMapper
        val unsigned = base64Url(json.writeValueAsBytes(header)) + "." + base64Url(json.writeValueAsBytes(claims))
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(unsigned.toByteArray(StandardCharsets.US_ASCII))
            sign()
        }
        return unsigned + "." + base64Url(signature)
    }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
// README: end

class MaskinportenExampleTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private val maskinporten = Maskinporten(
        baseUrl = baseUrl(),
        audience = "https://test.maskinporten.no/",
        clientId = "my-client-id",
        scope = "spk:pensjon.read",
        keyId = "my-key-2026",
        privateKey = keyPair.private,
    )

    @Test
    fun `call a Maskinporten-protected API with a token that is fetched once and reused`() {
        wiremock.stubFor(post(urlEqualTo("/token")).willReturn(okJson(
            """{"access_token":"token-from-maskinporten","token_type":"Bearer","expires_in":120,"scope":"spk:pensjon.read"}""",
        )))
        wiremock.stubFor(get(urlEqualTo("/pensjon/v1/medlemskap"))
            .withHeader("Authorization", equalTo("Bearer token-from-maskinporten"))
            .willReturn(okJson("""{"status":"AKTIV"}""")))

        // README: maskinporten usage
        val spk = RequestConfig(
            url = baseUrl(),                   // "https://api.spk.example"
            authorization = Authorization.expiringBearer { maskinporten.fetchToken() },
        )

        val membership: Map<String, String> = spk.get("/pensjon/v1/medlemskap")
        // README: end
        spk.get<Map<String, String>>("/pensjon/v1/medlemskap")

        assertEquals("AKTIV", membership["status"])
        wiremock.verify(1, postRequestedFor(urlEqualTo("/token")))
        wiremock.verify(2, getRequestedFor(urlEqualTo("/pensjon/v1/medlemskap")))
    }

    @Test
    fun `the token request is a form post with a JWT grant signed by the client's key`() {
        wiremock.stubFor(post(urlEqualTo("/token")).willReturn(okJson("""{"access_token":"t","expires_in":120}""")))

        maskinporten.fetchToken()

        wiremock.verify(postRequestedFor(urlEqualTo("/token"))
            .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
            .withoutHeader("Authorization"))
        val form = wiremock.allServeEvents.single().request.bodyAsString.split("&").associate {
            val (name, value) = it.split("=", limit = 2)
            name to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }
        assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])

        val (header, claims, signature) = form.getValue("assertion").split(".")
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keyPair.public)
            update("$header.$claims".toByteArray(StandardCharsets.US_ASCII))
        }
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(signature)), "the grant must verify with the client's public key")
        assertTrue(String(Base64.getUrlDecoder().decode(claims)).contains(""""aud":"https://test.maskinporten.no/""""))
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
