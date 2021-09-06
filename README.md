# kotlin-httpclient-dsl

A simple DSL on top of Java's HttpClient for easy, structured integration with other http-services from your Kotlin-application 

```kotlin
        val supplierFunction : () -> String =  { http.get("/admin/ping").body().toString() }

        val response = http.get("/admin/{uriVariable}", "ping") {
            request {
                requestTimeout { 20 }
                headers {
                    header("header1", "value1")
                    header("supplier", supplierFunction)
                    authorization {
                        login("user", "password") // provide your function for authorization
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
```

```kotlin

    @Test
    internal fun postRequestForFoo() {
        val requestConfig = RequestConfig(
            url = "http://localhost",
            port = wiremock.port(),
            username = "user",
            password = "pass"

        )
        val result = FooHttpClient(config = requestConfig).getTypeByName("bar", Foo("bar"))

        assertNotNull(result)
        assertEquals("bar", result.name)

        wiremock.verify(1, postRequestedFor(
            urlEqualTo("/api/foo?type=bar"))
            .withHeader("Authorization", equalTo("dXNlcjpwYXNz")))
    }
````
