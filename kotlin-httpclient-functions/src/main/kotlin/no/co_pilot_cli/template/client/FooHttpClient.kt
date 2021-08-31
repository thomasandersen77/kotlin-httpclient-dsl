package no.co_pilot_cli.template.client

import no.co_pilot_cli.template.client.config.RequestConfig
import no.co_pilot_cli.template.client.config.asJson
import no.co_pilot_cli.template.client.config.httpGet
import no.co_pilot_cli.template.client.config.httpPost
import no.co_pilot_cli.template.client.domain.Foo

class FooHttpClient(private val config: RequestConfig) {

    fun <T> getType(name: String, responseType: Class<T>): T =
        httpGet(config, "/api/foo?name=${name}", responseType)

    fun getTypeByName(type: String, body: Foo): Foo =
        httpPost(config, body, "/api/foo?type=${type}", Foo::class.java)
}