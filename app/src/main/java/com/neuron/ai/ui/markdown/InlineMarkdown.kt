package com.neuron.ai.ui.markdown

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * Inline Markdown → [AnnotatedString]: **bold**, *italic*, `code`,
 * [links](url), and inline math placeholders rendered via inline content.
 */
object InlineMarkdown {

    const val MATH_ID = "inline-math"
    private const val LINK_TAG = "LINK"

    private data class Token(
        val range: IntRange,
        val contentType: ContentType,
        val payload: String
    )

    private enum class ContentType { BOLD, ITALIC, CODE, LINK, MATH }

    @Composable
    fun toAnnotatedString(
        text: String,
        mathContent: Map<String, InlineTextContent> = emptyMap()
    ): AnnotatedString {
        // Theme-aware inline colors, read once per call.
        val codeBackground = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
        val linkColor = MaterialTheme.colorScheme.primary
        return buildAnnotatedString {
        val tokens = collectTokens(text)
        var cursor = 0

        for (token in tokens.sortedBy { it.range.first }) {
            if (token.range.first < cursor) continue // overlapping earlier token wins
            if (token.range.first > cursor) append(text.substring(cursor, token.range.first))

            when (token.contentType) {
                ContentType.BOLD -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    append(token.payload)
                }

                ContentType.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(token.payload)
                }

                ContentType.CODE -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)
                ) {
                    append(token.payload)
                }

                ContentType.LINK -> withLink(LinkAnnotation.Url(token.payload)) {
                    withStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                        )
                    ) {
                        append(token.payload)
                    }
                }

                ContentType.MATH -> appendInlineContent(MATH_ID, token.payload)
            }
            cursor = token.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }

    private fun collectTokens(text: String): List<Token> {
        val tokens = mutableListOf<Token>()

        fun add(range: IntRange, type: ContentType, payload: String) {
            if (range.first >= 0 && range.last < text.length) {
                tokens += Token(range, type, payload)
            }
        }

        // Bold before italic so ** wins.
        Regex("""\*\*(.+?)\*\*""").findAll(text).forEach {
            add(it.range, ContentType.BOLD, it.groupValues[1])
        }
        Regex("""(?<!\*)\*([^*\n]+)\*(?!\*)""").findAll(text).forEach {
            add(it.range, ContentType.ITALIC, it.groupValues[1])
        }
        Regex("""`([^`\n]+)`""").findAll(text).forEach {
            add(it.range, ContentType.CODE, it.groupValues[1])
        }
        Regex("""\[([^\]]+)]\(([^)\s]+)\)""").findAll(text).forEach {
            add(it.range, ContentType.LINK, it.groupValues[2])
        }
        Regex("""\$([^$\n]+?)\$""").findAll(text).forEach {
            add(it.range, ContentType.MATH, it.groupValues[1])
        }

        return tokens
        }
    }
}
