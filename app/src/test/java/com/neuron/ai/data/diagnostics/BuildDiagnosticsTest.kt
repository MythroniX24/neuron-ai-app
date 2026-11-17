package com.neuron.ai.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildDiagnosticsTest {

    @Test
    fun `fingerprint is colon separated uppercase hex`() {
        val value = BuildDiagnostics.fingerprint(byteArrayOf(0x19, 0x83.toByte(), 0x00), "SHA-1")
        assertTrue(Regex("^([0-9A-F]{2}:){19}[0-9A-F]{2}$").matches(value))
        assertEquals(value, BuildDiagnostics.fingerprint(byteArrayOf(0x19, 0x83.toByte(), 0x00), "SHA-1"))
    }

    @Test
    fun `fingerprint length matches the algorithm digest`() {
        val sha1 = BuildDiagnostics.fingerprint(ByteArray(64) { it.toByte() }, "SHA-1")
        val sha256 = BuildDiagnostics.fingerprint(ByteArray(64) { it.toByte() }, "SHA-256")
        assertEquals(20, sha1.split(":").size)
        assertEquals(32, sha256.split(":").size)
    }

    @Test
    fun `report includes package and signing line for copy-paste`() {
        val info = BuildInfo(
            packageName = "com.neuron.ai",
            versionName = "1.2.5",
            versionCode = 13,
            signingSha1 = "19:83:6E",
            signingSha256 = "AA:BB",
            debuggable = true,
            androidRelease = "14",
            sdkInt = 34
        )
        val report = info.report()
        assertTrue(report.contains("package: com.neuron.ai"))
        assertTrue(report.contains("signing SHA-1: 19:83:6E"))
        assertTrue(report.contains("version: 1.2.5 (13)"))
    }
}
