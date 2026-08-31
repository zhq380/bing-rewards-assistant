package com.ripple.script.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.logStore by preferencesDataStore(name = "run_logs")

/** 运行日志：记录最近 N 次脚本执行结果，供「我的」页查看 */
object RunLogStore {
    private const val MAX_ENTRIES = 20
    private val KEY_LOGS = stringSetPreferencesKey("log_entries")

    @Serializable
    data class Entry(
        val scriptName: String,
        val timestamp: Long,
        val success: Boolean,
        val executedSteps: Int,
        val message: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun decodeEntry(s: String): Entry? =
        runCatching { json.decodeFromString(Entry.serializer(), s) }.getOrNull()

    private fun encodeEntry(e: Entry): String =
        json.encodeToString(Entry.serializer(), e)

    suspend fun load(context: Context): List<Entry> {
        val prefs = context.logStore.data.first()
        return prefs[KEY_LOGS]?.mapNotNull { decodeEntry(it) }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    suspend fun add(context: Context, entry: Entry) {
        context.logStore.edit { prefs ->
            val current = prefs[KEY_LOGS]?.mapNotNull { decodeEntry(it) } ?: emptyList()
            val updated = (current + entry)
                .sortedByDescending { it.timestamp }
                .take(MAX_ENTRIES)
            prefs[KEY_LOGS] = updated.map { encodeEntry(it) }.toSet()
        }
    }

    suspend fun clear(context: Context) {
        context.logStore.edit { it.remove(KEY_LOGS) }
    }
}
