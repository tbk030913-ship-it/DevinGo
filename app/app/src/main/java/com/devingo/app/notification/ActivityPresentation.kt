package com.devingo.app.notification

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.devingo.app.LiveStatus
import com.devingo.app.R

/**
 * Multi-session aggregate card, ported from t3code's t3-agent-notifications
 * (AgentActivityPresentation.kt, MIT). Rows arrive as relay-shaped string
 * extras — `activity_line_N` = "status\ttitle\tproject" — so a service
 * restart rebuilds the identical card without the session stores.
 */
internal const val ACTIVITY_MAX_ROWS = 5

internal const val KEY_ACTIVE = "active"
internal const val KEY_PHASE = "activity_phase"
internal const val KEY_ACTIVE_COUNT = "activity_active_count"
internal const val KEY_ATTENTION_COUNT = "activity_attention_count"
internal const val KEY_FAILED_COUNT = "activity_failed_count"
internal const val KEY_THREAD_COUNT = "activity_thread_count"

internal fun activityLineKey(i: Int) = "activity_line_$i"

/** Non-line keys the extras map may carry — everything the rebuild reads. */
internal val ACTIVITY_DATA_KEYS = listOf(
    KEY_PHASE, KEY_ACTIVE,
    KEY_ACTIVE_COUNT, KEY_ATTENTION_COUNT, KEY_FAILED_COUNT, KEY_THREAD_COUNT,
)

internal data class ActivityRow(val status: String, val title: String, val project: String)

internal enum class ActivityPhase(
    /** Row label — also the status string encoded in `activity_line_N`. */
    val status: String,
    /** `shortCriticalText` while this phase leads the card. */
    val chip: String,
    val color: Int,
    /** Relay-shaped phase key (t3's `activity_phase` vocabulary). */
    val key: String,
) {
    STARTING("[启动中]", "启动中", R.color.agent_activity_working, "starting"),
    RUNNING("[工作中]", "工作中", R.color.agent_activity_working, "running"),
    APPROVAL("[待授权]", "待授权", R.color.agent_activity_attention, "waiting_for_approval"),
    INPUT("[待回答]", "待回答", R.color.agent_activity_input, "waiting_for_input"),
    STALE("[等待中]", "等待中", R.color.agent_activity_waiting, "stale"),
    COMPLETED("[已完成]", "已完成", R.color.agent_activity_done, "completed"),
    FAILED("[失败]", "失败", R.color.agent_activity_failed, "failed");

    val needsUser get() = this == APPROVAL || this == INPUT
    val finished get() = this == COMPLETED || this == FAILED

    companion object {
        fun forStatus(status: String) = entries.firstOrNull { it.status == status }

        fun forKey(key: String?) = entries.firstOrNull { it.key == key }

        /**
         * DevinGo session states → phases. INPUT is reserved — A7.2's
         * elicitation surface maps onto it when it lands.
         */
        fun forState(state: LiveStatus.State): ActivityPhase = when (state) {
            LiveStatus.State.NeedsPermission -> APPROVAL
            LiveStatus.State.Working -> RUNNING
            LiveStatus.State.Reconnecting, LiveStatus.State.Idle -> STALE
            LiveStatus.State.Done -> COMPLETED
            LiveStatus.State.Failed -> FAILED
        }
    }
}

/** Row order is fixed at build time; the renderer never reorders it. */
internal fun activityRows(data: Map<String, String>): List<ActivityRow> =
    (0 until ACTIVITY_MAX_ROWS).mapNotNull {
        val parts = data[activityLineKey(it)]?.split('\t', limit = 3) ?: return@mapNotNull null
        if (parts.size != 3 || parts[1].isBlank()) {
            null
        } else {
            ActivityRow(parts[0].take(40), parts[1].take(120), parts[2].take(120))
        }
    }

internal fun activityPhase(data: Map<String, String>, rows: List<ActivityRow>): ActivityPhase? =
    ActivityPhase.forKey(data[KEY_PHASE]?.takeIf { it.isNotBlank() })
        ?: rows.firstOrNull()?.let { ActivityPhase.forStatus(it.status) }

internal class ActivityPresentation(data: Map<String, String>, private val active: Boolean) {
    private val rows = activityRows(data)
    private val hero = rows.firstOrNull()
    val phase = activityPhase(data, rows)
    private val activeCount = data[KEY_ACTIVE_COUNT]?.toIntOrNull()?.coerceAtLeast(0)
        ?: rows.count { ActivityPhase.forStatus(it.status)?.finished != true }
    private val attentionCount = data[KEY_ATTENTION_COUNT]?.toIntOrNull()?.coerceAtLeast(0)
        ?: rows.count { ActivityPhase.forStatus(it.status)?.needsUser == true }
    private val failedCount = data[KEY_FAILED_COUNT]?.toIntOrNull()?.coerceAtLeast(0)
        ?: rows.count { it.status == ActivityPhase.FAILED.status }
    private val threadCount = data[KEY_THREAD_COUNT]?.toIntOrNull()?.coerceAtLeast(0)
        ?: activeCount + rows.count { ActivityPhase.forStatus(it.status)?.finished == true }
    private val singleProject = rows.map { it.project }.distinct().size == 1
    private val legacyBody = (0 until ACTIVITY_MAX_ROWS)
        .mapNotNull { data[activityLineKey(it)]?.take(300) }
        .takeIf { it.isNotEmpty() }?.joinToString("\n")
        .orEmpty()

    /**
     * The card title: what needs the user first, then work, then outcome.
     * Priority: attention > working+failed > working > failed > all finished.
     */
    val summary = when {
        hero == null -> "DevinGo"
        rows.size == 1 -> hero.title
        attentionCount == 1 -> "1 个会话需要你"
        attentionCount > 1 -> "$attentionCount 个会话需要你"
        activeCount > 0 && failedCount > 0 ->
            "$activeCount 个会话工作中 · $failedCount 个失败"
        activeCount > 0 -> "$activeCount 个会话工作中"
        failedCount > 0 -> "已结束 · $failedCount 个失败"
        else -> "全部完成"
    }

    val chip = when {
        !active -> if (phase == ActivityPhase.FAILED) "失败" else "已完成"
        phase == null -> "进行中"
        phase == ActivityPhase.RUNNING && activeCount > 1 ->
            "${if (activeCount > 9) "9+" else activeCount} 个活跃"
        else -> phase.chip
    }

    fun applyTo(builder: NotificationCompat.Builder, context: Context) {
        val tint = phase?.let { ContextCompat.getColor(context, it.color) }
        if (tint != null) builder.setColor(tint)
        // Tint the summary only when it names an outcome or a request; a plain
        // "3 个会话工作中" stays neutral so the accent keeps meaning something.
        val tintedSummary = tint != null && rows.size > 1 && phase != ActivityPhase.RUNNING &&
            phase != ActivityPhase.STARTING
        builder.setContentTitle(if (tintedSummary) tinted(summary, tint!!) else summary)
        if (rows.size > 1) {
            builder.setSubText(
                listOfNotNull(
                    hero!!.project.takeIf { singleProject && it.isNotBlank() },
                    if (activeCount > 0) "$activeCount 个进行中" else "$threadCount 个会话"
                ).joinToString(" · ")
            )
        }
        val body = body(context)
        val lineBreak = body.indexOf('\n')
        val firstLine = if (lineBreak >= 0) body.subSequence(0, lineBreak) else body
        builder.setContentText(firstLine).setStyle(NotificationCompat.BigTextStyle().bigText(body))
        builder.setShowWhen(false).setUsesChronometer(false)
    }

    private fun body(context: Context): CharSequence = when {
        hero == null -> legacyBody
        rows.size == 1 -> statusLine(context, hero.copy(title = hero.project), "")
        else -> SpannableStringBuilder().apply {
            rows.forEachIndexed { index, row ->
                if (index > 0) append("\n")
                append(statusLine(context, row, row.project.takeUnless { singleProject }.orEmpty()))
            }
        }
    }

    private fun statusLine(context: Context, row: ActivityRow, trailing: String): CharSequence =
        SpannableStringBuilder().apply {
            val status = ActivityPhase.forStatus(row.status)
            val color = ContextCompat.getColor(context, status?.color ?: R.color.agent_activity_waiting)
            append(tinted(row.status, color, bold = true))
            append(" ").append(row.title)
            // Promoted cards drop text color, so the separator has to do the work of the dimming.
            if (trailing.isNotBlank()) {
                val start = length
                append(" · ").append(trailing)
                setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(context, R.color.agent_activity_waiting)),
                    start,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

    private fun tinted(text: String, color: Int, bold: Boolean = false) =
        SpannableStringBuilder(text).apply {
            setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
}
