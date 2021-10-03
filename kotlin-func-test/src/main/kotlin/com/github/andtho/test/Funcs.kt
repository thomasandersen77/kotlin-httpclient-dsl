package com.github.andtho.test


infix fun <T> Any.test(block: () -> T): T {
    return block.invoke()
}


fun description(txt : String, block : () -> Unit) {
    println(txt)
    block.invoke()
}

infix fun <T> T.`given`(block: () -> T) : T {
    return block.invoke()
}

abstract class StringSpec(body: StringSpec.() -> Unit = {}) {

    init {
        body()
    }
}