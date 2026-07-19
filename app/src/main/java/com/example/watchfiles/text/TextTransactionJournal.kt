package com.example.watchfiles.text

import android.content.SharedPreferences

interface TextTransactionJournal {
    fun upsert(record: TextTransactionRecord)
    fun remove(id: String)
    fun list(): List<TextTransactionRecord>
}

class SharedPreferencesTextTransactionJournal(
    private val preferences: SharedPreferences,
) : TextTransactionJournal {
    override fun upsert(record: TextTransactionRecord) {
        val prefix = prefix(record.id)
        preferences.edit()
            .putString(prefix + "target", record.target.toString())
            .putString(prefix + "temp", record.temp.toString())
            .putString(prefix + "backup", record.backup?.toString())
            .putString(prefix + "expectedTargetDigest", record.expectedTargetDigest)
            .putString(prefix + "phase", record.phase.name)
            .commit()
    }

    override fun remove(id: String) {
        val prefix = prefix(id)
        preferences.edit()
            .remove(prefix + "target")
            .remove(prefix + "temp")
            .remove(prefix + "backup")
            .remove(prefix + "expectedTargetDigest")
            .remove(prefix + "phase")
            .commit()
    }

    override fun list(): List<TextTransactionRecord> {
        val ids = preferences.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX).substringBefore('.') }
            .toSet()

        return ids.mapNotNull { id ->
            val prefix = prefix(id)
            val target = preferences.getString(prefix + "target", null)
            val temp = preferences.getString(prefix + "temp", null)
            val expected = preferences.getString(prefix + "expectedTargetDigest", null)
            val phase = preferences.getString(prefix + "phase", null)
                ?.let { runCatching { TextTransactionPhase.valueOf(it) }.getOrNull() }
            if (target == null || temp == null || expected == null || phase == null) {
                null
            } else {
                TextTransactionRecord(
                    id = id,
                    target = java.nio.file.Paths.get(target),
                    temp = java.nio.file.Paths.get(temp),
                    backup = preferences.getString(prefix + "backup", null)
                        ?.takeIf { it.isNotEmpty() }
                        ?.let(java.nio.file.Paths::get),
                    expectedTargetDigest = expected,
                    phase = phase,
                )
            }
        }
    }

    private fun prefix(id: String): String = "$KEY_PREFIX$id."

    private companion object {
        const val KEY_PREFIX = "watchfiles.text.tx."
    }
}
