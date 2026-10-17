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
    @Test fun internalEngineUsesTheSameExplicitSelectionRules() {
        val native = "inkdock.kokoro.zh.v1_1"
        val available = OfflineEngineSelection.resolve(native, "system.engine", engines + native)
        assertEquals(native, available.packageName); assertTrue(available.available)
        val removedModel = OfflineEngineSelection.resolve(native, "system.engine", engines)
        assertEquals(native, removedModel.packageName); assertFalse(removedModel.available)
    }
    @Test fun catalogSignatureIsStableUnderOrderingButTracksChanges() {
        assertEquals(OfflineEngineSelection.catalogSignature(listOf("a", "b")), OfflineEngineSelection.catalogSignature(listOf("b", "a")))
        assertNotEquals(OfflineEngineSelection.catalogSignature(listOf("a")), OfflineEngineSelection.catalogSignature(listOf("b")))
    }
}
