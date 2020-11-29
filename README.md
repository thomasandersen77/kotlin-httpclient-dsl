# kotlin-httpclient-dsl

A simple DSL on top of Java's HttpClient for easy, structured integration with other http-services from your Kotlin-application 

```kotlin
        val supplierFunction : () -> String =  { http.get("/someurl").bodyAsString() }

        val response = http.put("/person/{id}", "12345") {
            request {
                requestTimeout { 20 }
                headers {
                    header("header1", "value1")
                    header("supplier", supplierFunction)
                    authorization {
                        login("user", "password") // plug in your own function for authentication
                    }
                }
                body {
                    contentType { arrayOf("application/json") }
                    json { PersonDto( firstame = "Bill", lastname = "Anderson") }
                }
            }
        }
```
