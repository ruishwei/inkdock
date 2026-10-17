package org.inkdock.reader

import java.security.MessageDigest

/** Explicit selections never become a different installed engine, including on initialization failure. */
internal object OfflineEngineSelection {
    data class Resolution(val packageName: String, val available: Boolean)
    fun resolve(requested: String, systemDefault: String, declaredEngines: Set<String>): Resolution {
        val selected = if (requested.isNotBlank()) requested else systemDefault
        return Resolution(selected, selected.isNotBlank() && selected in declaredEngines)
    }
    fun catalogSignature(voiceRecords: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(voiceRecords.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
