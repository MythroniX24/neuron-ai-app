package com.neuron.ai.core.agent

import com.neuron.ai.core.conversation.AgentStepRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapping-layer tests for the live activity timeline: UI-safe summaries,
 * correct type classification, and the chain-of-thought guard.
 */
class AgentStepMapperTest {

    private fun activity(
        stepId: String = "call-1",
        title: String = "Searching web",
        state: AgentActivity.State = AgentActivity.State.RUNNING,
        toolId: String? = "web.search",
        actionDetail: String? = null
    ) = AgentActivity(
        stepId = stepId,
        title = title,
        state = state,
        toolId = toolId,
        actionDetail = actionDetail,
        startedAtEpochMs = 1_000L
    )

    @Test
    fun `web search maps to web_search type with query detail`() {
        val record = AgentStepMapper.toRecord(
            activity(actionDetail = "Elon Musk net worth 2025")
        )
        assertEquals(AgentStepRecord.TYPE_WEB_SEARCH, record.type)
        assertEquals("Elon Musk net worth 2025", record.detail)
        assertEquals(AgentStepRecord.STATUS_RUNNING, record.status)
    }

    @Test
    fun `terminal maps to command with command detail`() {
        val record = AgentStepMapper.toRecord(
            activity(toolId = "terminal.run", actionDetail = "./gradlew test")
        )
        assertEquals(AgentStepRecord.TYPE_COMMAND, record.type)
        assertTrue(record.detail!!.contains("gradlew"))
    }

    @Test
    fun `fs read and code edit classify separately`() {
        val read = AgentStepMapper.toRecord(
            activity(toolId = "fs.read", actionDetail = "notes.txt")
        )
        assertEquals(AgentStepRecord.TYPE_FILE_READ, read.type)
        val edit = AgentStepMapper.toRecord(
            activity(toolId = "code.edit", actionDetail = "app/Main.kt")
        )
        assertEquals(AgentStepRecord.TYPE_FILE_EDIT, edit.type)
        val build = AgentStepMapper.toRecord(
            activity(toolId = "code.test", actionDetail = "--tests X")
        )
        assertEquals(AgentStepRecord.TYPE_BUILD_TEST, build.type)
    }

    @Test
    fun `thinking steps never carry detail - hard CoT guard`() {
        val record = AgentStepMapper.toRecord(
            activity(
                stepId = "think-1",
                title = "Understanding request",
                toolId = null,
                actionDetail = "SECRET INTERNAL REASONING TRACE"
            )
        )
        assertEquals(AgentStepRecord.TYPE_THINKING, record.type)
        assertNull(record.detail)
        assertTrue(record.label.isNotBlank())
    }

    @Test
    fun `status mapping covers done and failed`() {
        val done = AgentStepMapper.toRecord(activity(state = AgentActivity.State.DONE))
        assertEquals(AgentStepRecord.STATUS_DONE, done.status)
        val failed = AgentStepMapper.toRecord(activity(state = AgentActivity.State.FAILED))
        assertEquals(AgentStepRecord.STATUS_FAILED, failed.status)
    }

    @Test
    fun `label combines tool title and action`() {
        val record = AgentStepMapper.toRecord(
            activity(title = "Reading page", actionDetail = "https://example.com")
        )
        assertTrue(record.label.contains("Reading page"))
        assertTrue(record.label.contains("example.com"))
    }

    @Test
    fun `action detail extraction picks the right argument per tool`() {
        assertEquals(
            "6*7",
            AgentStepMapper.extractActionDetail("math.evaluate", "{\"expression\":\"6*7\"}")
        )
        assertEquals(
            "ls -la",
            AgentStepMapper.extractActionDetail("terminal.run", "{\"command\":\"ls -la\"}")
        )
        assertEquals(
            "kotlin",
            AgentStepMapper.extractActionDetail("web.search", "{\"query\":\"kotlin\"}")
        )
        assertEquals(
            null,
            AgentStepMapper.extractActionDetail("web.search", "not-json")
        )
    }
}
