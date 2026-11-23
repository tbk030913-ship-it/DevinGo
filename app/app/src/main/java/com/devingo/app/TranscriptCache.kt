package com.devingo.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-session transcript cache — one JSON file per session under
 * `cacheDir/transcripts/`, LRU-capped at [MAX_SESSIONS]. Opening a session
 * renders the cached transcript instantly (plus its turn timings and the
 * journal cursor), then `_devingo/attach`/`session/load` aligns it with the
 * hub. Image bytes are never cached — [ChatItem.attachmentCount] keeps the
 * "[N 张图片]" badge honest.
 *
 * The journal cursor is saved with the transcript so attach replays only
 * frames the cache missed; if the journal no longer reaches back, the hub
 * answers needLoad and the fresh replay replaces the snapshot wholesale.
 */
class TranscriptCache(context: Context, private val json: Json) {

    @Serializable
    data class CachedDiff(
        val path: String,
        val oldText: String? = null,
        val newText: String = "",
    )

    @Serializable
    data class CachedPlanEntry(
        val content: String = "",
        val status: String? = null,
        val priority: String? = null,
    )

    @Serializable
    data class CachedItem(
        val id: Long,
        val kind: String,
        val text: String = "",
        val status: String? = null,
        val toolCallId: String? = null,
        val toolKind: String? = null,
        val detail: String? = null,
        val detailTruncated: Boolean = false,
        val exitCode: Int? = null,
        val diffs: List<CachedDiff> = emptyList(),
        val planEntries: List<CachedPlanEntry> = emptyList(),
        val failed: Boolean = false,
        val attachmentCount: Int = 0,
        /** U5.4: the mid-turn "↪ 已转向" badge survives cache restores too. */
        val steered: Boolean = false,
    )

    @Serializable
    data class CachedTiming(val start: Long? = null, val end: Long? = null)

    @Serializable
    data class CachedTranscript(
        val v: Int = 1,
        val sessionId: String,
        /** The bridge host the snapshot came from — a stale-epoch file is ignored. */
        val host: String,
        val savedAt: Long = 0,
        val lastSeq: Long = 0,
        val nextItemId: Long = 0,
        val title: String? = null,
        val items: List<CachedItem> = emptyList(),
        val turnTimes: Map<Long, CachedTiming> = emptyMap(),
    )

    private val dir = File(context.cacheDir, "transcripts")
    private val ioMutex = Mutex()

    private fun fileFor(sessionId: String): File {
        val safe = sessionId.map { c ->
            if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_'
        }.joinToString("")
        return File(dir, "$safe.json")
    }

    suspend fun load(sessionId: String): CachedTranscript? = withContext(Dispatchers.IO) {
        runCatching {
            val f = fileFor(sessionId)
            if (!f.isFile) return@runCatching null
            // The filename is sanitized, so distinct ids can collide onto
            // one file — the stored id is the only trustworthy check.
            json.decodeFromString<CachedTranscript>(f.readText())
                .takeIf { it.v == 1 && it.sessionId == sessionId }
        }.getOrNull()
    }

    suspend fun save(t: CachedTranscript) {
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                runCatching {
                    dir.mkdirs()
                    val f = fileFor(t.sessionId)
                    val tmp = File(dir, "${f.name}.tmp")
                    tmp.writeText(json.encodeToString(t))
                    if (!tmp.renameTo(f)) {
                        f.writeText(tmp.readText())
                        tmp.delete()
                    }
                    // A save killed mid-write leaves its .tmp behind —
                    // sweep strays under the same lock.
                    dir.listFiles { _, name -> name.endsWith(".tmp") }
                        ?.forEach { it.delete() }
                    // LRU: drop everything past the newest MAX_SESSIONS files.
                    dir.listFiles { _, name -> name.endsWith(".json") }
                        ?.sortedByDescending { it.lastModified() }
                        ?.drop(MAX_SESSIONS)
                        ?.forEach { it.delete() }
                }
            }
        }
    }

    suspend fun remove(sessionId: String) {
        withContext(Dispatchers.IO) {
            runCatching { fileFor(sessionId).delete() }
        }
    }

    companion object {
        /** Sessions kept on disk; oldest beyond this are deleted on save. */
        const val MAX_SESSIONS = 20
        /** Items kept per session (the newest tail of the transcript). */
        const val MAX_ITEMS = 120
        const val TEXT_CAP = 8_000
        const val DETAIL_CAP = 2_000
        const val DIFF_TEXT_CAP = 4_000
    }
}
