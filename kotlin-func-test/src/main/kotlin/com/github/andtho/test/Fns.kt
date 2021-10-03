package com.github.andtho.test

suspend fun <T> runBlocking(f: suspend () -> T): T = f.invoke()

suspend fun runPromise(f: suspend () -> Unit) = f.invoke()
