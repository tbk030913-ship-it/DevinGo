package com.devingo.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * Composer draft TEXT per session, persisted to DataStore (C6) — a killed
 * or recycled process still reopens the composer mid-thought. Keys are
 * `draft_<sessionId>`; image attachments stay memory-only (compressed
 * bytes never enter this store, a Bundle, or the transcript cache).
 */
private val Context.draftDataStore by preferencesDataStore(name = "drafts")

class DraftStore(private val context: Context) {

    suspend fun load(sessionId: String): String? =
        context.draftDataStore.data.first()[keyFor(sessionId)]

    /** Empty drafts remove the key — a sent message leaves nothing behind. */
    suspend fun save(sessionId: String, text: String) {
        val key = keyFor(sessionId)
        context.draftDataStore.edit { prefs ->
            if (text.isEmpty()) prefs.remove(key) else prefs[key] = text
        }
    }

    suspend fun remove(sessionId: String) {
        val key = keyFor(sessionId)
        context.draftDataStore.edit { it.remove(key) }
    }

    private fun keyFor(sessionId: String) = stringPreferencesKey("draft_$sessionId")
}
