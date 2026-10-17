package org.inkdock.reader

import org.junit.Assert.*
import org.junit.Test

class OfflineEngineSelectionTest {
    private val engines = setOf("system.engine", "selected.engine")
    @Test fun explicitChoiceNeverBecomesSystemDefault() {
        val result = OfflineEngineSelection.resolve("selected.engine", "system.engine", engines)
        assertEquals("selected.engine", result.packageName); assertTrue(result.available)
    }
    @Test fun missingSavedChoiceIsRetainedAsUnavailable() {
        val result = OfflineEngineSelection.resolve("removed.engine", "system.engine", engines)
        assertEquals("removed.engine", result.packageName); assertFalse(result.available)
    }
    @Test fun systemDefaultIsUsedOnlyWhenNoExplicitChoiceExists() {
        val result = OfflineEngineSelection.resolve("", "system.engine", engines)
        assertEquals("system.engine", result.packageName); assertTrue(result.available)
    }
    @Test fun missingDefaultDoesNotBecomeArbitraryInstalledEngine() {
        val result = OfflineEngineSelection.resolve("", "missing.default", engines)
        assertEquals("missing.default", result.packageName); assertFalse(result.available)
    }
    @Test fun undeclaredPackageCannotBecomeAvailable() {
        assertFalse(OfflineEngineSelection.resolve("ordinary.app", "system.engine", engines).available)
    }
    @Test fun catalogSignatureIsStableUnderOrderingButTracksChanges() {
        assertEquals(OfflineEngineSelection.catalogSignature(listOf("a", "b")), OfflineEngineSelection.catalogSignature(listOf("b", "a")))
        assertNotEquals(OfflineEngineSelection.catalogSignature(listOf("a")), OfflineEngineSelection.catalogSignature(listOf("b")))
    }
}
