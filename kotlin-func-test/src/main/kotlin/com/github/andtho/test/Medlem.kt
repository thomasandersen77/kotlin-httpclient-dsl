package com.github.andtho.test

import org.junit.jupiter.api.Assertions


class Medlem(val fnr: String) {

    infix fun harEnEllerFlereYtelser(antall: List<Ytelse>): Boolean =
        if (antall.isNotEmpty()) true
        else Assertions.fail("Ingen ytelser")

    infix fun harKapittel20(ytelser: List<Ytelse>): Boolean {
        val any = ytelser.any { ytelse -> ytelse.kap20Info.isNotEmpty() }
        return if (any) return true
        else Assertions.fail("har ikke kapittel 20")
    }


}