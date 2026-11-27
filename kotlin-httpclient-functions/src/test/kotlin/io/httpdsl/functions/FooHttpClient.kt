package io.httpdsl.functions


class FooHttpClient(private val config: RequestConfig) {

    fun getFoo(name: String): Foo =
        config.get("/api/foo", queryParams = mapOf("name" to name))

    fun getFooOrNull(name: String): Foo? =
        config.getOrNull("/api/foo", queryParams = mapOf("name" to name))

    fun getAllFoos(): List<Foo> =
        config.get("/api/foos")

    fun getTypeByName(type: String, body: Foo): Foo =
        config.post("/api/foo", body = body, queryParams = mapOf("type" to type))

    fun updateFoo(id: String, body: Foo): Foo =
        config.put("/api/foo/{id}", body = body, pathParams = mapOf("id" to id))

    fun deleteFoo(id: String) =
        config.delete<Unit>("/api/foo/{id}", pathParams = mapOf("id" to id))
}
