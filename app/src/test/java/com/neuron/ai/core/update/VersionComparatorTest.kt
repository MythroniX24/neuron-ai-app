package com.neuron.ai.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun `detects newer patch release`() {
        assertTrue(VersionComparator.isUpdateAvailable("1.2.7", "v1.2.8"))
        assertTrue(VersionComparator.isUpdateAvailable("1.2.7", "1.2.8"))
    }

    @Test
    fun `detects newer minor release`() {
        assertTrue(VersionComparator.isUpdateAvailable("1.2.7", "v1.3.0"))
        assertTrue(VersionComparator.isUpdateAvailable("1.2.7", "1.3.0"))
    }

    @Test
    fun `detects newer major release`() {
        assertTrue(VersionComparator.isUpdateAvailable("1.2.7", "v2.0.0"))
        assertTrue(VersionComparator.isUpdateAvailable("1.9.9", "2.0.0"))
    }

    @Test
    fun `reports false when versions are identical`() {
        assertFalse(VersionComparator.isUpdateAvailable("1.2.7", "v1.2.7"))
        assertFalse(VersionComparator.isUpdateAvailable("1.2.7", "1.2.7"))
        assertFalse(VersionComparator.isUpdateAvailable("v1.2.7", "1.2.7"))
    }

    @Test
    fun `reports false when installed version is newer`() {
        assertFalse(VersionComparator.isUpdateAvailable("1.2.8", "v1.2.7"))
        assertFalse(VersionComparator.isUpdateAvailable("2.0.0", "v1.9.9"))
        assertFalse(VersionComparator.isUpdateAvailable("1.3.0", "1.2.9"))
    }

    @Test
    fun `handles different segment counts`() {
        assertEquals(0, VersionComparator.compare("1.2", "1.2.0"))
        assertTrue(VersionComparator.isUpdateAvailable("1.2", "1.2.1"))
        assertTrue(VersionComparator.isUpdateAvailable("1.2.7", "1.2.7.1"))
        assertFalse(VersionComparator.isUpdateAvailable("1.2.7.1", "1.2.7"))
    }

    @Test
    fun `handles prerelease tags gracefully`() {
        // A release with a higher base version is newer than an older version
        assertTrue(VersionComparator.isUpdateAvailable("1.2.7", "v1.2.8-beta1"))
        // Full release 1.2.8 has higher precedence than pre-release 1.2.8-beta1
        assertTrue(VersionComparator.compare("1.2.8", "1.2.8-beta1") > 0)
        assertFalse(VersionComparator.isUpdateAvailable("1.2.8", "v1.2.8-beta1"))
    }

    @Test
    fun `cleanVersion strips v and whitespace`() {
        assertEquals("1.2.8", VersionComparator.cleanVersion("  v1.2.8  "))
        assertEquals("1.2.8", VersionComparator.cleanVersion("V1.2.8"))
        assertEquals("2.0.0", VersionComparator.cleanVersion("2.0.0"))
    }
}
