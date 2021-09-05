package no.co_pilot_cli.template.client.domain

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

data class Foo @JsonCreator constructor (@JsonProperty("name") val name: String)