package com.github.andtho.test

import org.junit.jupiter.api.Test

internal class TestBaseKtTest {

    @Test
    internal fun name() {
        description("hent samordningsdata og opprett sak") {
            test {
                val medlem = Medlem(fnr = "09077745367")
                val ytelser = ytelser(fnr = "09077745367")

                given {
                    medlem harEnEllerFlereYtelser ytelser
                    medlem harKapittel20 ytelser
                }

            }
        }
    }


    private fun ytelser(fnr : String) : List<Ytelse>{
        return listOf(Ytelse())
        // return emptyList()
    }


}