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
 *
 * `draftrefs_<sessionId>` rides alongside: the verbatim `@{kind}:ref`
 * mentions whose spaced paths the send-time scanner can't re-derive —
 * without it a restored draft's `@file:my dir/a.kt` ships a truncated ref.
 */
private val Context.draftDataStore by preferencesDataStore(name = "drafts")

class DraftStore(private val context: Context) {

    suspend fun load(sessionId: String): String? =
        context.draftDataStore.data.first()[keyFor(sessionId)]

    suspend fun loadRefs(sessionId: String): List<String> =
        context.draftDataStore.data.first()[refsKeyFor(sessionId)]
            ?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    /** Empty drafts remove both keys — a sent message leaves nothing behind. */
    suspend fun save(sessionId: String, text: String, refs: List<String> = emptyList()) {
        val key = keyFor(sessionId)
        val refsKey = refsKeyFor(sessionId)
        context.draftDataStore.edit { prefs ->
            if (text.isEmpty()) {
                prefs.remove(key)
                prefs.remove(refsKey)
            } else {
                prefs[key] = text
                val clean = refs.filter { '\n' !in it && it.isNotEmpty() }
                if (clean.isEmpty()) {
                    prefs.remove(refsKey)
                } else {
                    prefs[refsKey] = clean.joinToString("\n")
                }
            }
        }
    }

    suspend fun remove(sessionId: String) {
        val key = keyFor(sessionId)
        val refsKey = refsKeyFor(sessionId)
        context.draftDataStore.edit {
            it.remove(key)
            it.remove(refsKey)
        }
    }

    private fun keyFor(sessionId: String) = stringPreferencesKey("draft_$sessionId")
    private fun refsKeyFor(sessionId: String) = stringPreferencesKey("draftrefs_$sessionId")
}
