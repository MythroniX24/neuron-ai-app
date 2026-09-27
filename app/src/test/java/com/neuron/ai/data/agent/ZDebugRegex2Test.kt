package com.neuron.ai.data.agent

import org.junit.Test

class ZDebugRegex2Test {
    @Test
    fun debug2() {
        val s = "<parameter-expression>6*7</parameter-expression>"
        val v1 = Regex("(?s)<parameter-([A-Za-z0-9_.\\\\-]+)>(.*?)</parameter>").find(s)?.groupValues
        val v2 = Regex("(?s)<parameter-([A-Za-z0-9_.\\-]+)>(.*?)</parameter>").find(s)?.groupValues
        val v3 = Regex("(?s)<parameter-([A-Za-z0-9_.]+)>(.*?)</parameter>").find(s)?.groupValues
        val v4 = Regex("(?s)<parameter-(.+?)>(.*?)</parameter>").find(s)?.groupValues
        // Class contents inspection: what does each compiled pattern look like?
        val p1 = java.util.regex.Pattern.compile("[A-Za-z0-9_.\\\\-]")
        val m1 = p1.matcher("e").matches()
        val m1b = p1.matcher("-").matches()
        val p2 = java.util.regex.Pattern.compile("[A-Za-z0-9_.\\-]")
        val m2 = p2.matcher("e").matches()
        val m2b = p2.matcher("-").matches()
        throw AssertionError(
            "v1=${v1 != null} v2=${v2 != null} v3=${v3 != null} v4=${v4 != null} " +
                "m1=$m1 m1dash=$m1b m2=$m2 m2dash=$m2b p1=${p1.pattern()} p2=${p2.pattern()}"
        )
    }
}
