package com.neuron.ai.ui.markdown

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.neuron.ai.core.settings.ReplySpacingProfile
import com.neuron.ai.core.settings.ResponseSpacing
import com.neuron.ai.ui.components.FullScreenImage
import com.neuron.ai.ui.components.LocalImageOpener
import com.neuron.ai.ui.theme.Radius
import com.neuron.ai.ui.theme.Spacing

/**
 * Renders parsed markdown blocks with the Neuron design system. Code blocks
 * get language label, copy button, syntax highlighting and horizontal scroll;
 * display math renders via JLatexMath with graceful fallback.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    /** AI-selected spacing, independently for prose and lists. */
    spacing: ReplySpacingProfile = ReplySpacingProfile()
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            BlockView(block, spacing)
            if (index != blocks.lastIndex) {
                // The paragraph-gap choice is its own setting: it never rides
                // along with the line-spacing choice.
                Spacer(Modifier.height(Spacing.sm * spacing.gap.blockGapScale))
            }
        }
    }
}

/**
 * Scales a design-system style's line height (never its font size) and pins
 * [LineHeightStyle] so the extra leading is actually visible. Without it the
 * first/last line's leading is trimmed and "line spacing" appears to do
 * nothing — which is exactly how it read before.
 */
private fun TextStyle.withResponseSpacing(spacing: ResponseSpacing): TextStyle {
    val base = lineHeight
    val scaled = if (base != TextUnit.Unspecified) {
        (base.value * spacing.lineHeightScale).sp
    } else {
        (fontSize.value * DEFAULT_LINE_HEIGHT_RATIO * spacing.lineHeightScale).sp
    }
    return copy(
        lineHeight = scaled,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None
        )
    )
}

private const val DEFAULT_LINE_HEIGHT_RATIO = 1.5f

@Composable
private fun BlockView(block: MdBlock, spacing: ReplySpacingProfile) {
    val paragraph = spacing.paragraph
    val list = spacing.list
    when (block) {
        is MdBlock.Heading -> Text(
            text = InlineMarkdown.toAnnotatedString(block.text),
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall.withResponseSpacing(paragraph)
                2 -> MaterialTheme.typography.titleLarge.withResponseSpacing(paragraph)
                else -> MaterialTheme.typography.titleMedium.withResponseSpacing(paragraph)
            },
            fontWeight = FontWeight.SemiBold
        )

        is MdBlock.Paragraph -> Text(
            text = InlineMarkdown.toAnnotatedString(block.text),
            style = MaterialTheme.typography.bodyLarge.withResponseSpacing(paragraph)
        )

        // A picture from the web or an attachment preview: shown at its real
        // aspect ratio and tappable to open full-screen (cross to close,
        // download at the top right).
        is MdBlock.Image -> {
            val opener = LocalImageOpener.current
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(block.url)
                    .crossfade(true)
                    .build(),
                contentDescription = block.alt.ifBlank { "Image" },
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.md))
                    .clickable { opener(FullScreenImage(block.url, block.alt)) },
                loading = {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator(Modifier.size(22.dp)) }
                },
                error = {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(Radius.md)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = block.alt.ifBlank { "Image could not be loaded" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        }

        // Agent-authored UI (A2UI): native cards, file lists, forms.
        is MdBlock.A2ui -> com.neuron.ai.ui.a2ui.A2uiBlockView(block.json)

        is MdBlock.CodeBlock -> CodeBlockView(block)

        is MdBlock.LatexBlock -> LatexBlockView(block.latex)

        is MdBlock.Quote -> Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .padding(end = Spacing.sm)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.5.dp))
            )
            Text(
                text = InlineMarkdown.toAnnotatedString(block.text),
                style = MaterialTheme.typography.bodyLarge.withResponseSpacing(paragraph),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        is MdBlock.HorizontalRule -> Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline)
        ) {}

        is MdBlock.ListBlock -> Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs * list.blockGapScale)
        ) {
            block.items.forEachIndexed { index, item ->
                Row {
                    Text(
                        // The model's own numbers are kept: a list that gets split
                        // into several blocks (nested bullets do that) then keeps
                        // reading 1, 2, 3 instead of 1, 1, 1.
                        text = if (block.ordered) "${item.number ?: (index + 1)}." else "•",
                        style = MaterialTheme.typography.bodyLarge.withResponseSpacing(list),
                        modifier = Modifier.padding(end = Spacing.sm)
                    )
                    Text(
                        text = InlineMarkdown.toAnnotatedString(item.text),
                        style = MaterialTheme.typography.bodyLarge.withResponseSpacing(list)
                    )
                }
            }
        }

        is MdBlock.Table -> TableView(block)
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    val clipboard = LocalClipboardManager.current
    // Follow the APP theme (LocalAppDarkMode), not the OS flag — app-dark +
    // system-light previously rendered code blocks with the light palette.
    val isDark = com.neuron.ai.ui.theme.LocalAppDarkMode.current
    // Dark code blocks use a slightly deeper panel than surfaceVariant so the
    // lifted-luminance token colors keep contrast; light uses surfaceVariant.
    val codeBackground = if (isDark) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val highlighted = remember(block.code, block.language, isDark) {
        SyntaxHighlighter.highlight(
            code = block.code,
            language = block.language,
            dark = isDark
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                codeBackground,
                RoundedCornerShape(Radius.md)
            )
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                RoundedCornerShape(Radius.md)
            )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = block.language ?: "code",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(block.code)) }) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copy code",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SelectionContainer {
            Text(
                text = highlighted,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.md)
            )
        }
        // Bottom breathing room.
        androidx.compose.foundation.layout.Spacer(Modifier.height(Spacing.sm))
    }
}

@Composable
private fun TableView(table: MdBlock.Table) {
    Column(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                RoundedCornerShape(Radius.sm)
            )
    ) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
            table.headers.forEach { header ->
                Text(
                    text = InlineMarkdown.toAnnotatedString(header),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(Spacing.sm)
                        .widthIn(min = 72.dp)
                )
            }
        }
        table.rows.forEach { row ->
            Row {
                row.forEach { cell ->
                    Text(
                        text = InlineMarkdown.toAnnotatedString(cell),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .padding(Spacing.sm)
                            .widthIn(min = 72.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LatexBlockView(latex: String) {
    val shape = RoundedCornerShape(Radius.md)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .padding(Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LatexText(
            latex = latex,
            textStyle = MaterialTheme.typography.bodyLarge,
            fallback = { Text(latex, style = MaterialTheme.typography.bodyLarge) }
        )
    }
}

@Composable
private fun LatexText(
    latex: String,
    textStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    fallback: @Composable () -> Unit
) {
    val drawable = remember(latex) {
        runCatching {
            ru.noties.jlatexmath.JLatexMathDrawable.builder(latex)
                .textSize(44f)
                .padding(8)
                .build()
        }.getOrNull()
    }

    if (drawable != null) {
        androidx.compose.foundation.Image(
            painter = rememberDrawablePainter(drawable),
            contentDescription = "Math formula",
            modifier = Modifier.padding(Spacing.xs)
        )
    } else {
        fallback()
    }
}
