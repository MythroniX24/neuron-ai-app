package com.neuron.ai.data

import com.neuron.ai.core.conversation.Message
import com.neuron.ai.core.conversation.MessageMetadata
import com.neuron.ai.data.db.MessageCodec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Round-trips attachments and metadata through the persisted JSON blobs. */
class MessageCodecTest {

    private val codec = MessageCodec(Json { ignoreUnknownKeys = true })

    @Test
    fun `empty attachments encode to empty array`() {
        assertEquals("[]", codec.encodeAttachments(emptyList()))
        assertEquals(emptyList<Any>(), codec.decodeAttachments("[]"))
        assertEquals(emptyList<Any>(), codec.decodeAttachments(null))
        assertEquals(emptyList<Any>(), codec.decodeAttachments("garbage"))
    }

    @Test
    fun `attachment blob round-trips`() {
        val attachment = com.neuron.ai.core.conversation.Attachment(
            id = "att-1",
            displayName = "notes.txt",
            mimeType = "text/plain",
            sizeBytes = 42,
            localPath = "attachments/att-1",
            kind = com.neuron.ai.core.conversation.Attachment.Kind.TEXT
        )

        val raw = codec.encodeAttachments(listOf(attachment))
        val decoded = codec.decodeAttachments(raw)

        assertEquals(listOf(attachment), decoded)
    }

    @Test
    fun `metadata blob round-trips with unknown keys tolerated`() {
        val metadata = MessageMetadata(
            providerId = "prov-1",
            modelId = "gpt-test",
            generationMs = 1234,
            toolCallId = "call-1",
            toolName = "time.now"
        )

        val raw = codec.encodeMetadata(metadata)
        assertEquals(metadata, codec.decodeMetadata(raw))
    }

    @Test
    fun `malformed metadata decodes to null`() {
        assertNull(codec.decodeMetadata("not-json"))
        assertNull(codec.decodeMetadata(null))
        assertNull(codec.decodeMetadata(""))
    }

    @Test
    fun `unknown role strings fall back to USER`() {
        val entity = com.neuron.ai.data.db.MessageEntity(
            id = "m1",
            conversationId = "c1",
            role = "SOMETHING_NEW",
            content = "hi",
            createdAtEpochMs = 0,
            attachmentsJson = "[]",
            metadataJson = null
        )
        val message = entity.toDomain(codec)

        assertEquals(Message.Role.USER, message.role)
    }
}
