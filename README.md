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
