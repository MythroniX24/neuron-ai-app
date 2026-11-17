package com.neuron.ai.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neuron.ai.core.conversation.RichItem
import com.neuron.ai.core.conversation.RichRecommendationSet
import com.neuron.ai.core.settings.ResponseSpacing
import com.neuron.ai.core.web.ImageQuery
import com.neuron.ai.ui.theme.LocalAppDarkMode
import com.neuron.ai.ui.theme.Spacing

/**
 * Native rendering for any entity-based recommendation payload: films,
 * products, books, tools, repositories, places or recipes all use the same
 * reusable card. The AI chooses this surface by calling `ui.rich_items`; the
 * ViewModel validates its JSON and hands a [RichRecommendationSet] here.
 *
 * Thumbnails go through the same entity-image pipeline as comparison cards:
 * a model-supplied URL is used when present, otherwise the item title is
 * looked up, and a miss settles on a neutral tile instead of shimmering.
 */
@Composable
fun RichRecommendationSection(
    recommendations: List<RichRecommendationSet>,
    responseSpacing: ResponseSpacing = ResponseSpacing.COMFORTABLE,
    imageUrls: Map<String, String> = emptyMap(),
    missingQueries: Set<String> = emptySet(),
    onImagesNeeded: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg * responseSpacing.blockGapScale)
    ) {
        recommendations.forEach { set ->
            RichRecommendationSetBlock(
                set = set,
                responseSpacing = responseSpacing,
                imageUrls = imageUrls,
                missingQueries = missingQueries,
                onImagesNeeded = onImagesNeeded
            )
        }
    }
}

@Composable
private fun RichRecommendationSetBlock(
    set: RichRecommendationSet,
    responseSpacing: ResponseSpacing,
    imageUrls: Map<String, String>,
    missingQueries: Set<String>,
    onImagesNeeded: (List<String>) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        if (set.title.isNotBlank()) {
            Text(
                text = set.title,
                style = MaterialTheme.typography.headlineSmall.withSpacing(responseSpacing),
                fontWeight = FontWeight.Bold
            )
        }
        if (set.intro.isNotBlank()) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = set.intro,
                style = MaterialTheme.typography.bodyLarge.withSpacing(responseSpacing),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(Spacing.md))
        set.items.forEachIndexed { index, item ->
            RichItemRow(
                item = item,
                index = index + 1,
                responseSpacing = responseSpacing,
                imageUrls = imageUrls,
                missingQueries = missingQueries,
                onImagesNeeded = onImagesNeeded
            )
            if (index != set.items.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = Spacing.md),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
                )
            }
        }
    }
}

@Composable
private fun RichItemRow(
    item: RichItem,
    index: Int,
    responseSpacing: ResponseSpacing,
    imageUrls: Map<String, String>,
    missingQueries: Set<String>,
    onImagesNeeded: (List<String>) -> Unit
) {
    val title = item.title.trim()
    LaunchedEffect(title, imageUrls, missingQueries) {
        if (title.isBlank() || !item.imageUrl.isNullOrBlank()) return@LaunchedEffect
        if (imageUrls[title].isNullOrBlank() && ImageQuery.key(title) !in missingQueries) {
            onImagesNeeded(listOf(title))
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top
    ) {
        RichItemThumbnail(
            item = item,
            imageUrls = imageUrls,
            missingQueries = missingQueries
        )
        Column(Modifier.weight(1f)) {
            item.badge?.takeIf { it.isNotBlank() }?.let { badge ->
                RichBadge(badge)
                Spacer(Modifier.height(Spacing.xs))
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "$index.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = buildAnnotatedString {
                        append(item.title)
                        item.year?.takeIf { it.isNotBlank() }?.let { year ->
                            val clean = year.trim().removeSurrounding("(", ")")
                            append(" ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("($clean)")
                            }
                        }
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            item.meta?.takeIf { it.isNotBlank() }?.let { meta ->
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium.withSpacing(responseSpacing),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (item.details.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.sm))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item.details.forEach { detail ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = Spacing.xs)
                            )
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                        append(detail.label)
                                    }
                                    append(": ${detail.value}")
                                },
                                style = MaterialTheme.typography.bodyMedium.withSpacing(responseSpacing),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            item.status?.takeIf { it.isNotBlank() }?.let { status ->
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = statusColor(item.statusTone)
                )
            }

            if (item.links.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item.links.forEach { link ->
                        RichActionLink(link.label, link.url)
                    }
                }
            }
        }
    }
}

@Composable
private fun RichItemThumbnail(
    item: RichItem,
    imageUrls: Map<String, String>,
    missingQueries: Set<String>
) {
    Box(
        Modifier
            .width(92.dp)
            .height(126.dp)
            .clip(RoundedCornerShape(14.dp))
    ) {
        SmartEntityImage(
            imageUrl = item.imageUrl,
            imageQuery = null,
            fallbackQuery = item.title,
            resolvedUrls = imageUrls,
            missingQueries = missingQueries,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
        )
    }
}

@Composable
private fun RichBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = badgeBackground(),
        contentColor = badgeTextColor()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 3.dp)
        )
    }
}

@Composable
private fun RichActionLink(label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { runCatching { uriHandler.openUri(url) } }
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(3.dp))
        Icon(
            Icons.Outlined.OpenInNew,
            contentDescription = "Open link",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
    }
}

/**
 * Line spacing only. Trimming is disabled so the extra leading is visible on
 * the first and last line too — otherwise the setting looks like it does
 * nothing.
 */
private fun TextStyle.withSpacing(spacing: ResponseSpacing): TextStyle {
    val base = lineHeight
    val scaled = if (base != TextUnit.Unspecified) {
        (base.value * spacing.lineHeightScale).sp
    } else {
        (fontSize.value * 1.5f * spacing.lineHeightScale).sp
    }
    return copy(
        lineHeight = scaled,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None
        )
    )
}

@Composable
private fun statusColor(tone: String): Color {
    val dark = LocalAppDarkMode.current
    return when (tone) {
        "warning" -> MaterialTheme.colorScheme.error
        "neutral" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> if (dark) Color(0xFF7DD3A7) else Color(0xFF1F7A4D)
    }
}

@Composable
private fun badgeBackground(): Color {
    val dark = LocalAppDarkMode.current
    return if (dark) Color(0xFF1D3A2D) else Color(0xFFDCF5E7)
}

@Composable
private fun badgeTextColor(): Color {
    val dark = LocalAppDarkMode.current
    return if (dark) Color(0xFF9BE3BC) else Color(0xFF17603D)
}
