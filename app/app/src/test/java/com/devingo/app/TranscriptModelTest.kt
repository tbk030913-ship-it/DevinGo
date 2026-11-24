package com.devingo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptModelTest {

    private fun item(
        id: Long,
        kind: Kind,
        transport: Boolean = false,
    ) = ChatItem(id, kind, "t$id", transport = transport)

    @Test
    fun `transport banners render below the work fold`() {
        val model = buildTranscriptModel(
            listOf(
                item(1, Kind.User),
                item(2, Kind.Agent),
                item(3, Kind.System, transport = true),
                item(4, Kind.Thought),
            ),
            running = false,
        )
        // Rows are bottom-up (reverseLayout): index 0 is the visual bottom.
        assertTrue(model.rows[0] is RowKind.Status)
        assertEquals(RowKind.Body(3, 1L), model.rows[1])
        assertEquals(RowKind.Work(1, live = false), model.rows[2])
        assertEquals(RowKind.Body(2, 1L), model.rows[3])
        assertEquals(RowKind.User(1), model.rows[4])
    }

    @Test
    fun `non-transport system lines stay inline`() {
        val model = buildTranscriptModel(
            listOf(
                item(1, Kind.User),
                item(2, Kind.Agent),
                item(3, Kind.System),
                item(4, Kind.Thought),
            ),
            running = false,
        )
        assertTrue(model.rows[0] is RowKind.Status)
        assertEquals(RowKind.Work(1, live = false), model.rows[1])
        assertEquals(RowKind.Body(3, 1L), model.rows[2])
        assertEquals(RowKind.Body(2, 1L), model.rows[3])
        assertEquals(RowKind.User(1), model.rows[4])
    }

    @Test
    fun `running detail carries the live turn's step count`() {
        val model = buildTranscriptModel(
            listOf(
                item(1, Kind.User),
                item(2, Kind.Thought),
                ChatItem(3, Kind.Tool, "读取文件", status = "in_progress"),
            ),
            running = true,
        )
        assertEquals("读取文件 · 2 步", model.runningDetail)
    }
}
