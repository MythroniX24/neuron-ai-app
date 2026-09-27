package com.neuron.ai.data.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ZDebugRegexTest {
    @Test
    fun debug() {
        val s = "<parameter-expression>6*7</parameter-expression>"
        val a = Regex("(?s)<parameter-([A-Za-z0-9_.\\\\-]+)>(.*?)</parameter>").findAll(s).toList()
        val b = Regex("(?s)<parameter-([A-Za-z0-9_.\\-]+)>(.*?)</parameter>").findAll(s).toList()
        val c = Regex("(?s)<parameter-([A-Za-z0-9_.\\w-]+)>(.*?)</parameter>").findAll(s).toList()
        throw AssertionError("a=${a.size} b=${b.size} c=${c.size}")
    }
}
