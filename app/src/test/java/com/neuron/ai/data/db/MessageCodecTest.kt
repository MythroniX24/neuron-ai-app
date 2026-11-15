package com.neuron.ai.data.db

import com.neuron.ai.core.conversation.Attachment
import com.neuron.ai.core.conversation.MessageMetadata
import com.neuron.ai.core.conversation.RichItem
import com.neuron.ai.core.conversation.RichItemLink
import com.neuron.ai.core.conversation.RichRecommendationSet
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attachments must survive the Room round-trip (encode → decode) intact —
 * the chat UI re-renders thumbnails/tiles from the persisted metadata.
 */
class MessageCodecTest {

    private val codec = MessageCodec(Json { ignoreUnknownKeys = true })

    @Test
    fun `attachment list survives encode-decode round trip`() {
        val attachments = listOf(
            Attachment(
                id = "att-img1",
                displayName = "photo.jpg",
                mimeType = "image/jpeg",
                sizeBytes = 2048,
                localPath = "attachments/att-img1_photo.jpg",
                kind = Attachment.Kind.IMAGE
            ),
            Attachment(
                id = "att-doc1",
                displayName = "notes.md",
                mimeType = "text/markdown",
                sizeBytes = 512,
                localPath = "attachments/att-doc1_notes.md",
                kind = Attachment.Kind.TEXT
            )
        )

        val encoded = codec.encodeAttachments(attachments)
        val decoded = codec.decodeAttachments(encoded)

        assertEquals(attachments, decoded)
    }

    @Test
    fun `rich recommendation cards survive message metadata round trip`() {
        val metadata = MessageMetadata(
            providerId = "prov-1",
            modelId = "model-1",
            richRecommendations = listOf(
                RichRecommendationSet(
                    title = "Your picks",
                    intro = "Three useful tools.",
                    items = listOf(
                        RichItem(
                            title = "Neuron",
                            year = "2026",
                            meta = "Android AI app",
                            badge = "Best match",
                            imageUrl = "https://example.com/icon.png",
                            links = listOf(
                                RichItemLink("Website", "https://example.com")
                            )
                        )
                    )
                )
            )
        )

        val decoded = codec.decodeMetadata(codec.encodeMetadata(metadata))

        assertEquals(metadata, decoded)
    }

    @Test
    fun `empty and null blobs decode to empty list`() {
        assertTrue(codec.decodeAttachments("[]").isEmpty())
        assertTrue(codec.decodeAttachments(null).isEmpty())
        assertTrue(codec.decodeAttachments("").isEmpty())
        // Corrupt blob degrades to empty instead of crashing the chat.
        assertTrue(codec.decodeAttachments("not-json").isEmpty())
    }
}
