package io.httpdsl.functions

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.common.ConsoleNotifier
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION") // the deprecated http* helpers stay covered until they are removed
internal class FooHttpClientTest {
    private val wiremock: WireMockServer = WireMockServer(WireMockConfiguration.options()
        .dynamicPort()
        .notifier(ConsoleNotifier(true)))

    @Test
    fun getFooByNameNotFound() {
        val secureRequestConfig = RequestConfig(
            url = "http://localhost:${wiremock.port()}",
            authorization = Authorization.basic("user", "pass"),
        )
        val fooClient = FooHttpClient(config = secureRequestConfig)

        val exception = assertThrows(HttpClientException::class.java) { fooClient.getFoo("wrong_name") }
        assertEquals(404, exception.status)
    }

    @Test
    fun getFooByName() {
        val requestConfig = RequestConfig(
            url = "http://localhost:${wiremock.port()}",
            authorization = Authorization.basic("user", "pass"),
        )
        val fooClient = FooHttpClient(config = requestConfig)
        val result = fooClient.getFoo("bar")

        assertNotNull(result)
        assertEquals("bar", result.name)
        wiremock.verify(1, getRequestedFor(urlEqualTo("/api/foo?name=bar"))
            .withHeader("Authorization", equalTo("Basic dXNlcjpwYXNz")))
    }

    @Test
    internal fun postRequestForFoo() {
        val result = FooHttpClient(config = RequestConfig(
            url = "http://localhost:${wiremock.port()}",
            authorization = Authorization.basic("user", "pass"),
        )).getTypeByName("bar", Foo("bar"))

        assertNotNull(result)
        assertEquals("bar", result.name)

        wiremock.verify(1, postRequestedFor(
            urlEqualTo("/api/foo?type=bar"))
            .withHeader("Authorization", equalTo("Basic dXNlcjpwYXNz")))
    }

    @Test
    internal fun `getOrNull returns value on 200, null on 404 or 204, and rethrows other errors`() {
        wiremock.stubFor(get(urlEqualTo("/api/foo?name=missing"))
            .willReturn(aResponse().withStatus(404).withBody("not found")))
        wiremock.stubFor(get(urlEqualTo("/api/foo?name=forbidden"))
            .willReturn(aResponse().withStatus(403).withBody("forbidden")))

        val config = RequestConfig(
            url = "http://localhost:${wiremock.port()}",
            authorization = Authorization.basic("user", "pass"),
        )
        val fooClient = FooHttpClient(config)

        assertEquals(Foo("bar"), fooClient.getFooOrNull("bar"))
        assertNull(fooClient.getFooOrNull("missing"))
        assertNull(httpGetOrNull<Foo>(config, "/api/empty"))

        val ex403 = assertThrows(HttpClientException::class.java) {
            fooClient.getFooOrNull("forbidden")
        }
        assertEquals(403, ex403.status)
    }

    @Test
    internal fun `supports static bearerToken and dynamic tokenProvider`() {
        wiremock.stubFor(get(urlEqualTo("/api/protected"))
            .willReturn(aResponse().withStatus(200).withBody("{\"name\":\"secured\"}")))

        val staticBearerConfig = RequestConfig(
            url = "http://localhost:${wiremock.port()}",
            authorization = Authorization.bearer("static-jwt-token")
        )
        val staticResult: Foo = staticBearerConfig.get("/api/protected")
        assertEquals(Foo("secured"), staticResult)

        wiremock.verify(1, getRequestedFor(urlEqualTo("/api/protected"))
            .withHeader("Authorization", equalTo("Bearer static-jwt-token")))

        val counter = AtomicInteger(0)
        val dynamicBearerConfig = RequestConfig(
            url = "http://localhost:${wiremock.port()}",
            authorization = Authorization.bearer { "dynamic-token-${counter.incrementAndGet()}" }
        )

        val first: Foo = dynamicBearerConfig.get("/api/protected")
        val second: Foo = dynamicBearerConfig.get("/api/protected")
        assertEquals(Foo("secured"), first)
        assertEquals(Foo("secured"), second)

        wiremock.verify(1, getRequestedFor(urlEqualTo("/api/protected"))
            .withHeader("Authorization", equalTo("Bearer dynamic-token-1")))
        wiremock.verify(1, getRequestedFor(urlEqualTo("/api/protected"))
            .withHeader("Authorization", equalTo("Bearer dynamic-token-2")))
    }

    @Test
    internal fun `supports raw String request and response bodies and header overrides`() {
        wiremock.stubFor(get(urlEqualTo("/health"))
            .willReturn(aResponse().withStatus(200).withBody("OK")))
        wiremock.stubFor(post(urlEqualTo("/echo"))
            .withHeader("Content-Type", equalTo("text/plain"))
            .withHeader("X-Custom", equalTo("override"))
            .withRequestBody(equalTo("plain text payload"))
            .willReturn(aResponse().withStatus(200).withBody("plain text response")))

        val config = RequestConfig(
            url = "http://localhost:${wiremock.port()}",
            headers = mapOf("X-Custom" to "default", "X-Tenant" to "acme")
        )

        val health: String = config.get("/health")
        assertEquals("OK", health)

        val echoed: String = config.post(
            path = "/echo",
            body = "plain text payload",
            headers = mapOf("Content-Type" to "text/plain", "X-Custom" to "override")
        )
        assertEquals("plain text response", echoed)

        wiremock.verify(1, postRequestedFor(urlEqualTo("/echo"))
            .withHeader("Content-Type", equalTo("text/plain"))
            .withHeader("X-Custom", equalTo("override"))
            .withHeader("X-Tenant", equalTo("acme"))
            .withRequestBody(equalTo("plain text payload")))
    }

    private data class EventDto(val title: String, val date: LocalDate)

    @Test
    internal fun `url encodes query parameters, ignores null query params, and supports 204 and java time`() {

        wiremock.stubFor(get(urlEqualTo("/api/foo?name=bar%20baz%26qux"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("{\"title\":\"Launch\",\"date\":\"2026-10-23\"}")))

        wiremock.stubFor(delete(urlEqualTo("/api/foo/1"))
            .willReturn(aResponse().withStatus(204)))

        val config = RequestConfig(url = "http://localhost:${wiremock.port()}")
        val fooClient = FooHttpClient(config)

        val event: EventDto = config.get("/api/foo", queryParams = mapOf("name" to "bar baz&qux"))
        assertEquals("Launch", event.title)
        assertEquals(LocalDate.of(2026, 10, 23), event.date)

        val withNullParam: EventDto = config.get(
            "/api/foo",
            queryParams = mapOf("name" to "bar baz&qux", "ignored" to null)
        )
        assertEquals(event, withNullParam)

        fooClient.deleteFoo("1")
        val nullableResult: Foo? = httpGet(config, "/api/empty")
        assertNull(nullableResult)
    }

    @Test
    internal fun `put and patch requests send JSON body and deserialize response`() {
        wiremock.stubFor(put(urlEqualTo("/api/foo/1"))
            .withRequestBody(equalToJson("{\"name\":\"updated\"}"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("{\"name\":\"updated\"}")))
        wiremock.stubFor(patch(urlEqualTo("/api/foo/1"))
            .withRequestBody(equalToJson("{\"name\":\"patched\"}"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("{\"name\":\"patched\"}")))

        val config = RequestConfig(
            url = "http://localhost:${wiremock.port()}",
            authorization = Authorization.basic("user", "pass"),
        )
        val fooClient = FooHttpClient(config)

        assertEquals(Foo("updated"), fooClient.updateFoo("1", Foo("updated")))
        assertEquals(Foo("updated"), httpPut<Foo>(config, Foo("updated"), "/api/foo/1"))
        assertEquals(Foo("patched"), config.patch<Foo>("/api/foo/1", Foo("patched")))
        assertEquals(Foo("patched"), httpPatch<Foo>(config, Foo("patched"), "/api/foo/1"))
        assertEquals(Foo("bar"), httpPost<Foo>(config, Foo("bar"), "/api/foo", queryParams = mapOf("type" to "bar")))

        wiremock.verify(2, putRequestedFor(urlEqualTo("/api/foo/1"))
            .withHeader("Authorization", equalTo("Basic dXNlcjpwYXNz"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(equalToJson("{\"name\":\"updated\"}")))
        wiremock.verify(2, patchRequestedFor(urlEqualTo("/api/foo/1"))
            .withRequestBody(equalToJson("{\"name\":\"patched\"}")))
    }

    @Test
    internal fun `5xx throws HttpServerException and 4xx throws HttpClientException with common HttpException base`() {
        wiremock.stubFor(get(urlEqualTo("/api/error/500"))
            .willReturn(aResponse().withStatus(500).withBody("internal server failure")))
        wiremock.stubFor(get(urlEqualTo("/api/error/503"))
            .willReturn(aResponse().withStatus(503).withBody("service unavailable")))
        wiremock.stubFor(get(urlEqualTo("/api/error/400"))
            .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"bad request\"}")))

        val config = RequestConfig(url = "http://localhost:${wiremock.port()}")

        val ex500 = assertThrows(HttpServerException::class.java) {
            config.get<Foo>("/api/error/500")
        }
        assertEquals(500, ex500.status)
        assertEquals("internal server failure", ex500.body)

        val ex503: HttpException = assertThrows(HttpException::class.java) {
            httpGet<Foo>(config, "/api/error/503")
        }
        assertEquals(503, ex503.status)
        assertEquals("service unavailable", ex503.body)

        val ex400: HttpException = assertThrows(HttpClientException::class.java) {
            config.get<Foo>("/api/error/400")
        }
        assertEquals(400, ex400.status)
        assertEquals("{\"error\":\"bad request\"}", ex400.body)
    }

    @Test
    internal fun `reified get deserializes generic List of Foo`() {
        wiremock.stubFor(get(urlEqualTo("/api/foos"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("[{\"name\":\"first\"},{\"name\":\"second\"}]")))
        wiremock.stubFor(get(urlEqualTo("/api/foo?name=all"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody("[{\"name\":\"first\"},{\"name\":\"second\"}]")))

        val config = RequestConfig(url = "http://localhost:${wiremock.port()}")
        val fooClient = FooHttpClient(config)

        assertEquals(listOf(Foo("first"), Foo("second")), fooClient.getAllFoos())
        val clientFoos: List<Foo> = config.get("/api/foo", queryParams = mapOf("name" to "all"))
        assertEquals(listOf(Foo("first"), Foo("second")), clientFoos)
    }

    @Test
    internal fun `contentType parameter sets Content-Type for raw String body`() {
        wiremock.stubFor(post(urlEqualTo("/oauth/token"))
            .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
            .withRequestBody(equalTo("grant_type=client_credentials"))
            .willReturn(aResponse().withStatus(200).withBody("{\"name\":\"token\"}")))

        val config = RequestConfig(url = "http://localhost:${wiremock.port()}")
        val result: Foo = config.post(
            path = "/oauth/token",
            body = "grant_type=client_credentials",
            contentType = "application/x-www-form-urlencoded",
        )
        assertEquals(Foo("token"), result)
    }

    @Test
    internal fun `supports binary ByteArray upload and download and downloadFile to Path`(@TempDir tempDir: Path) {
        val binaryPayload = byteArrayOf(0x00, 0x01, 0x7F, 0x80.toByte(), 0xFF.toByte())

        wiremock.stubFor(post(urlEqualTo("/api/binary"))
            .withHeader("Content-Type", equalTo("application/octet-stream"))
            .withRequestBody(binaryEqualTo(binaryPayload))
            .willReturn(aResponse().withStatus(200).withBody(binaryPayload)))

        wiremock.stubFor(get(urlEqualTo("/api/binary/file"))
            .willReturn(aResponse().withStatus(200).withBody(binaryPayload)))

        val config = RequestConfig(url = "http://localhost:${wiremock.port()}")

        val echoedBytes: ByteArray = config.post(
            path = "/api/binary",
            body = binaryPayload,
            contentType = "application/octet-stream",
        )
        assertArrayEquals(binaryPayload, echoedBytes)

        val downloadedBytes: ByteArray = config.get("/api/binary/file")
        assertArrayEquals(binaryPayload, downloadedBytes)

        val targetFile = tempDir.resolve("downloaded.bin")
        val savedPath = config.downloadFile("/api/binary/file", targetFile)
        assertEquals(targetFile, savedPath)
        assertArrayEquals(binaryPayload, Files.readAllBytes(targetFile))
    }

    @Test
    internal fun `multipart uploads fields and files with boundary`(@TempDir tempDir: Path) {
        val fileOnDisk = tempDir.resolve("note.txt")
        Files.write(fileOnDisk, "hello file".toByteArray(Charsets.UTF_8))

        wiremock.stubFor(post(urlEqualTo("/api/upload"))
            .withHeader("Content-Type", matching("multipart/form-data; boundary=.*"))
            .withRequestBody(containing("name=\"description\""))
            .withRequestBody(containing("Signert avtale"))
            .withRequestBody(containing("name=\"document\"; filename=\"avtale.pdf\""))
            .withRequestBody(containing("Content-Type: application/pdf"))
            .withRequestBody(containing("name=\"attachment\"; filename=\"note.txt\""))
            .withRequestBody(containing("hello file"))
            .willReturn(aResponse().withStatus(200).withBody("{\"name\":\"uploaded\"}")))

        val config = RequestConfig(url = "http://localhost:${wiremock.port()}")
        val result: Foo = config.post(
            path = "/api/upload",
            body = multipart {
                field("description", "Signert avtale")
                file("document", "avtale.pdf", byteArrayOf(0x25, 0x50, 0x44, 0x46), "application/pdf")
                file("attachment", fileOnDisk, "text/plain")
            },
        )
        assertEquals(Foo("uploaded"), result)
    }

    @Test
    internal fun `updateFoo and deleteFoo encode special characters in path segments`() {
        wiremock.stubFor(put(urlEqualTo("/api/foo/a%2Fb%3Fc%23d"))
            .willReturn(aResponse().withStatus(200).withBody("{\"name\":\"encoded\"}")))
        wiremock.stubFor(delete(urlEqualTo("/api/foo/a%2Fb%3Fc%23d"))
            .willReturn(aResponse().withStatus(204)))

        val config = RequestConfig(url = "http://localhost:${wiremock.port()}")
        val fooClient = FooHttpClient(config)

        assertEquals(Foo("encoded"), fooClient.updateFoo("a/b?c#d", Foo("encoded")))
        fooClient.deleteFoo("a/b?c#d")
    }

    @Test
    internal fun `3xx responses throw HttpException when not followed`() {
        wiremock.stubFor(get(urlEqualTo("/api/not-modified"))
            .willReturn(aResponse().withStatus(304)))
        wiremock.stubFor(get(urlEqualTo("/api/redirect"))
            .willReturn(aResponse().withStatus(302).withHeader("Location", "/api/foo?name=bar")))

        val noRedirectClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val config = RequestConfig(
            url = "http://localhost:${wiremock.port()}",
            httpClient = noRedirectClient,
        )

        val ex304 = assertThrows(HttpException::class.java) {
            config.get<Foo>("/api/not-modified")
        }
        assertEquals(304, ex304.status)

        val ex302 = assertThrows(HttpException::class.java) {
            config.get<Foo>("/api/redirect")
        }
        assertEquals(302, ex302.status)
    }

    @Test
    internal fun `empty response body throws IllegalStateException for non-nullable type and succeeds for Unit`() {
        wiremock.stubFor(delete(urlEqualTo("/api/foo/2"))
            .willReturn(aResponse().withStatus(204)))

        val config = RequestConfig(url = "http://localhost:${wiremock.port()}")

        assertThrows(IllegalStateException::class.java) {
            config.get<Foo>("/api/empty")
        }

        val unitViaExtension: Unit = config.delete("/api/foo/2")
        assertEquals(Unit, unitViaExtension)

        val unitViaTopLevel: Unit = httpDelete(config, "/api/foo/2")
        assertEquals(Unit, unitViaTopLevel)
    }

    @Test
    internal fun `request without credentials omits Authorization header and normalizes slashes`() {
        val config = RequestConfig(url = "http://localhost:${wiremock.port()}/")
        val result: Foo = config.get("api/foo?name=bar")

        assertEquals(Foo("bar"), result)
        wiremock.verify(1, getRequestedFor(urlEqualTo("/api/foo?name=bar"))
            .withoutHeader("Authorization"))
    }

    @Test
    internal fun `requests without a body carry no Content-Type header`() {
        wiremock.stubFor(delete(urlEqualTo("/api/foo/9")).willReturn(aResponse().withStatus(204)))
        wiremock.stubFor(post(urlEqualTo("/api/trigger")).willReturn(aResponse().withStatus(204)))
        val config = RequestConfig(url = "http://localhost:${wiremock.port()}")

        config.get<Foo>("/api/foo?name=bar")
        config.delete<Unit>("/api/foo/9")
        config.post<Unit>("/api/trigger")
        config.post<Foo>("/api/foo?type=bar", body = Foo("bar"))

        wiremock.verify(getRequestedFor(urlEqualTo("/api/foo?name=bar")).withoutHeader("Content-Type"))
        wiremock.verify(deleteRequestedFor(urlEqualTo("/api/foo/9")).withoutHeader("Content-Type"))
        wiremock.verify(postRequestedFor(urlEqualTo("/api/trigger")).withoutHeader("Content-Type"))
        wiremock.verify(postRequestedFor(urlEqualTo("/api/foo?type=bar"))
            .withHeader("Content-Type", equalTo("application/json")))
    }

    @Test
    internal fun `exchange returns status and response headers together with the typed body`() {
        wiremock.stubFor(post(urlEqualTo("/api/foos"))
            .willReturn(aResponse().withStatus(201)
                .withHeader("Location", "/api/foos/7")
                .withHeader("ETag", "\"v1\"")
                .withBody("{\"name\":\"created\"}")))
        wiremock.stubFor(get(urlEqualTo("/api/foos/missing")).willReturn(aResponse().withStatus(404)))
        val config = RequestConfig(url = "http://localhost:${wiremock.port()}")

        val created = config.exchange<Foo>(Method.POST, "/api/foos", body = Foo("created"))

        assertEquals(201, created.status)
        assertEquals(Foo("created"), created.body)
        assertEquals("/api/foos/7", created.header("location"))
        assertEquals("\"v1\"", created.header("ETag"))
        assertNull(created.header("X-Missing"))
        assertThrows(HttpClientException::class.java) {
            config.exchange<Foo>(Method.GET, "/api/foos/{id}", pathParams = mapOf("id" to "missing"))
        }
    }

    @Test
    internal fun `cachedBearer fetches again after a 401 and resends the request once`() {
        wiremock.stubFor(post(urlEqualTo("/api/expiring"))
            .withHeader("Authorization", equalTo("Bearer token-1"))
            .willReturn(aResponse().withStatus(401)))
        wiremock.stubFor(post(urlEqualTo("/api/expiring"))
            .withHeader("Authorization", equalTo("Bearer token-2"))
            .willReturn(aResponse().withStatus(200).withBody("{\"name\":\"refreshed\"}")))
        val fetches = AtomicInteger(0)
        val config = RequestConfig(
            url = "http://localhost:${wiremock.port()}",
            authorization = Authorization.cachedBearer { "token-${fetches.incrementAndGet()}" },
        )

        val result: Foo = config.post("/api/expiring", body = Foo("payload"))

        assertEquals(Foo("refreshed"), result)
        assertEquals(2, fetches.get())
        wiremock.verify(1, postRequestedFor(urlEqualTo("/api/expiring")).withHeader("Authorization", equalTo("Bearer token-1")))
        wiremock.verify(1, postRequestedFor(urlEqualTo("/api/expiring"))
            .withHeader("Authorization", equalTo("Bearer token-2"))
            .withRequestBody(equalToJson("{\"name\":\"payload\"}")))
    }

    @Test
    internal fun `downloadFile fetches a new token after a 401 and downloads the file`(@TempDir tempDir: Path) {
        wiremock.stubFor(get(urlEqualTo("/api/report.csv"))
            .withHeader("Authorization", equalTo("Bearer token-1"))
            // Longer than the file: the retry must not leave the tail of this body behind it.
            .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"invalid_token\",\"error_description\":\"Token expired\"}")))
        wiremock.stubFor(get(urlEqualTo("/api/report.csv"))
            .withHeader("Authorization", equalTo("Bearer token-2"))
            .willReturn(aResponse().withStatus(200).withBody("id;name")))
        val fetches = AtomicInteger(0)
        val config = RequestConfig(
            url = "http://localhost:${wiremock.port()}",
            authorization = Authorization.cachedBearer { "token-${fetches.incrementAndGet()}" },
        )

        val saved = config.downloadFile("/api/{file}", tempDir.resolve("report.csv"), pathParams = mapOf("file" to "report.csv"))

        assertEquals("id;name", Files.readString(saved))
        assertEquals(2, fetches.get())
    }

    @Test
    internal fun `a failed download leaves an existing file untouched and no partial file behind`(@TempDir tempDir: Path) {
        wiremock.stubFor(get(urlEqualTo("/api/missing.pdf"))
            .willReturn(aResponse().withStatus(404).withBody("{\"error\":\"not found\"}")))
        val existing = tempDir.resolve("report.pdf")
        Files.writeString(existing, "the previous download")
        val config = RequestConfig(url = "http://localhost:${wiremock.port()}")

        val error = assertThrows(HttpClientException::class.java) { config.downloadFile("/api/missing.pdf", existing) }

        assertEquals(404, error.status)
        assertEquals("{\"error\":\"not found\"}", error.body)
        assertEquals("the previous download", Files.readString(existing))
        assertEquals(listOf("report.pdf"), tempDir.toFile().list()!!.toList())
        assertThrows(HttpClientException::class.java) { config.downloadFile("/api/missing.pdf", tempDir.resolve("new.pdf")) }
        assertFalse(Files.exists(tempDir.resolve("new.pdf")))
    }

    @Test
    internal fun `binary requests accept any media type, and an empty body is null or empty by the asked type`(@TempDir tempDir: Path) {
        wiremock.stubFor(get(urlEqualTo("/api/avatar")).willReturn(aResponse().withStatus(200).withBody(byteArrayOf(1, 2))))
        wiremock.stubFor(get(urlEqualTo("/api/no-avatar")).willReturn(aResponse().withStatus(204)))
        wiremock.stubFor(get(urlEqualTo("/api/empty-text")).willReturn(aResponse().withStatus(200)))
        val config = RequestConfig(url = "http://localhost:${wiremock.port()}")

        assertArrayEquals(byteArrayOf(1, 2), config.get<ByteArray>("/api/avatar"))
        config.downloadFile("/api/avatar", tempDir.resolve("avatar.bin"))
        wiremock.verify(2, getRequestedFor(urlEqualTo("/api/avatar")).withHeader("Accept", equalTo("*/*")))

        assertNull(config.getOrNull<ByteArray>("/api/no-avatar"))
        assertArrayEquals(ByteArray(0), config.get<ByteArray>("/api/no-avatar"))
        // An empty 200 is a real, empty file, not "nothing there".
        assertArrayEquals(ByteArray(0), config.getOrNull<ByteArray>("/api/empty-text"))
        assertEquals("", config.get<String>("/api/empty-text"))
        assertNull(config.get<String?>("/api/empty-text"))
    }

    @Test
    internal fun `an Authorization header replaces the config authorization, which is then not told about a 401`() {
        wiremock.stubFor(get(urlEqualTo("/api/explicit-auth")).willReturn(aResponse().withStatus(401)))
        var asked = 0
        var told = 0
        val config = RequestConfig(
            url = "http://localhost:${wiremock.port()}",
            authorization = object : Authorization {
                override fun headerValue(): String { asked++; return "Bearer from-config" }
                override fun onUnauthorized(rejectedHeaderValue: String): Boolean { told++; return true }
            },
        )

        assertThrows(HttpClientException::class.java) {
            config.get<Foo>("/api/explicit-auth", headers = mapOf("Authorization" to "Basic explicit"))
        }

        assertEquals(0, asked)
        assertEquals(0, told)
        wiremock.verify(1, getRequestedFor(urlEqualTo("/api/explicit-auth")).withHeader("Authorization", equalTo("Basic explicit")))
    }

    @Test
    internal fun `bearer with a lambda sends a fresh token per request and does not resend after a 401`() {
        wiremock.stubFor(get(urlEqualTo("/api/rejected-dynamic")).willReturn(aResponse().withStatus(401)))
        val issued = AtomicInteger(0)
        val config = RequestConfig(
            url = "http://localhost:${wiremock.port()}",
            authorization = Authorization.bearer { "token-${issued.incrementAndGet()}" },
        )

        assertThrows(HttpClientException::class.java) { config.get<Foo>("/api/rejected-dynamic") }

        assertEquals(1, issued.get())
        wiremock.verify(1, getRequestedFor(urlEqualTo("/api/rejected-dynamic")))
    }

    @Test
    internal fun `an Iterable or array in queryParams repeats the parameter`() {
        wiremock.stubFor(get(urlEqualTo("/api/foos?id=1&id=2&tag=a%20b&page=0"))
            .willReturn(aResponse().withStatus(200).withBody("[{\"name\":\"first\"}]")))
        val config = RequestConfig(url = "http://localhost:${wiremock.port()}")

        val foos: List<Foo> = config.get(
            "/api/foos",
            queryParams = mapOf("id" to listOf(1, null, 2), "tag" to arrayOf("a b"), "none" to emptyList<Int>(), "page" to intArrayOf(0)),
        )

        assertEquals(listOf(Foo("first")), foos)
    }

    @Test
    internal fun `static authorization returns the 401 as an exception without resending`() {
        wiremock.stubFor(get(urlEqualTo("/api/rejected")).willReturn(aResponse().withStatus(401)))
        val config = RequestConfig(
            url = "http://localhost:${wiremock.port()}",
            authorization = Authorization.bearer("static"),
        )

        val error = assertThrows(HttpClientException::class.java) { config.get<Foo>("/api/rejected") }

        assertEquals(401, error.status)
        wiremock.verify(1, getRequestedFor(urlEqualTo("/api/rejected")))
    }

    @BeforeEach
    internal fun setUp() {
        wiremock.start()
        wiremock.stubFor(get(urlEqualTo("/api/foo?name=bar"))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Foo("bar")))))

        wiremock.stubFor(post(urlEqualTo("/api/foo?type=bar"))
            .withRequestBody(equalToJson(mapper.writeValueAsString(Foo("bar"))))
            .willReturn(aResponse()
                .withStatus(200)
                .withBody(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Foo("bar")))))

        wiremock.stubFor(get(urlEqualTo("/api/empty"))
            .willReturn(aResponse().withStatus(204)))
    }

    @AfterEach
    internal fun tearDown() {
        wiremock.stop()
    }
}
