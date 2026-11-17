package com.neuron.ai.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.neuron.ai.core.conversation.ComparisonLayout
import com.neuron.ai.core.conversation.ComparisonOption
import com.neuron.ai.core.conversation.ComparisonPlacement
import com.neuron.ai.core.conversation.ComparisonSegment
import com.neuron.ai.core.conversation.ComparisonUiBlock
import com.neuron.ai.core.conversation.FeatureRow
import com.neuron.ai.core.conversation.PriorityOption
import com.neuron.ai.core.conversation.ScoreOption
import com.neuron.ai.core.settings.ReplySpacingProfile
import com.neuron.ai.core.web.ImageQuery
import com.neuron.ai.ui.markdown.MarkdownText
import com.neuron.ai.ui.theme.LocalAppDarkMode
import com.neuron.ai.ui.theme.Spacing
import java.util.Locale

/**
 * One assistant answer as it actually reads: prose, then a comparison card,
 * then more prose, then the next card. `imageUrls` maps a card's `imageQuery`
 * to a URL the ViewModel resolved, so both live and persisted messages show
 * real images without the model ever inventing a link.
 */
@Composable
fun ComparisonContent(
    markdown: String,
    placements: List<ComparisonPlacement>,
    spacing: ReplySpacingProfile,
    imageUrls: Map<String, String> = emptyMap(),
    missingQueries: Set<String> = emptySet(),
    onImagesNeeded: (List<String>) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val segments = remember(markdown, placements) {
        ComparisonLayout.segments(markdown, placements)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md * spacing.gap.blockGapScale)
    ) {
        segments.forEach { segment ->
            when (segment) {
                is ComparisonSegment.Text -> MarkdownText(markdown = segment.text, spacing = spacing)
                is ComparisonSegment.Block -> ComparisonBlockWidget(
                    block = segment.block,
                    imageUrls = imageUrls,
                    missingQueries = missingQueries,
                    onImagesNeeded = onImagesNeeded
                )
            }
        }
    }
}

/** Dispatches one validated block to its surface. */
@Composable
fun ComparisonBlockWidget(
    block: ComparisonUiBlock,
    imageUrls: Map<String, String> = emptyMap(),
    missingQueries: Set<String> = emptySet(),
    onImagesNeeded: (List<String>) -> Unit = {}
) {
    // The model's phrase first, then the entity title as a simpler fallback;
    // asking for both is what turns a generic phrase into a real picture.
    val pairs = remember(block) { block.imageLookupPairs() }
    LaunchedEffect(pairs, imageUrls, missingQueries) {
        val needed = pairs
            .flatMap { (query, title) -> listOfNotNull(query, title) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { imageUrls[it].isNullOrBlank() }
            .filter { ImageQuery.key(it) !in missingQueries }
            .distinct()
        if (needed.isNotEmpty()) onImagesNeeded(needed)
    }
    when (block) {
        is ComparisonUiBlock.SideBySide -> SideBySideWidget(block, imageUrls, missingQueries)
        is ComparisonUiBlock.FeatureTable -> FeatureTableWidget(block)
        is ComparisonUiBlock.Score -> ScoreComparisonWidget(block)
        is ComparisonUiBlock.ProsCons -> ProsConsWidget(block)
        is ComparisonUiBlock.Priority -> PriorityComparisonWidget(block)
    }
}

/** (imageQuery, title) per option — the title is the fallback lookup phrase. */
private fun ComparisonUiBlock.imageLookupPairs(): List<Pair<String?, String?>> = when (this) {
    is ComparisonUiBlock.SideBySide -> listOf(
        optionA.imageQuery to optionA.title,
        optionB.imageQuery to optionB.title
    )
    else -> emptyList()
}

// ---- 1. Side-by-side -------------------------------------------------------

@Composable
private fun SideBySideWidget(
    block: ComparisonUiBlock.SideBySide,
    imageUrls: Map<String, String>,
    missingQueries: Set<String>
) {
    Column(Modifier.fillMaxWidth()) {
        block.title?.takeIf { it.isNotBlank() }?.let { SectionTitle(it) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            OptionCard(
                option = block.optionA,
                tone = PillTone.PRIMARY,
                imageUrls = imageUrls,
                missingQueries = missingQueries,
                modifier = Modifier.weight(1f)
            )
            OptionCard(
                option = block.optionB,
                tone = PillTone.SECONDARY,
                imageUrls = imageUrls,
                missingQueries = missingQueries,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun OptionCard(
    option: ComparisonOption,
    tone: PillTone,
    imageUrls: Map<String, String>,
    missingQueries: Set<String>,
    modifier: Modifier = Modifier
) {
    ComparisonCard(modifier = modifier) {
        SmartEntityImage(
            imageUrl = option.imageUrl,
            imageQuery = option.imageQuery,
            fallbackQuery = option.title,
            resolvedUrls = imageUrls,
            missingQueries = missingQueries,
            contentDescription = option.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = option.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        option.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (option.label.isNotBlank()) {
            Spacer(Modifier.height(Spacing.sm))
            ComparisonPill(option.label, tone)
        }
        if (option.specs.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                option.specs.forEach { spec ->
                    BulletLine(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(spec.label) }
                            append(": ")
                            append(spec.value)
                        }
                    )
                }
            }
        }
    }
}

// ---- 2. Feature table ------------------------------------------------------

@Composable
private fun FeatureTableWidget(block: ComparisonUiBlock.FeatureTable) {
    Column(Modifier.fillMaxWidth()) {
        block.title?.takeIf { it.isNotBlank() }?.let { SectionTitle(it) }
        Row(Modifier.fillMaxWidth().padding(bottom = Spacing.xs)) {
            TableCell("Feature", Modifier.weight(1.2f), bold = true)
            TableCell(block.optionALabel, Modifier.weight(1f), bold = true)
            TableCell(block.optionBLabel, Modifier.weight(1f), bold = true)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        block.rows.forEach { row ->
            FeatureTableRow(row)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun FeatureTableRow(row: FeatureRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
        TableCell(row.feature, Modifier.weight(1.2f))
        TableCell(row.a, Modifier.weight(1f))
        TableCell(row.b, Modifier.weight(1f))
    }
}

@Composable
private fun TableCell(text: String, modifier: Modifier, bold: Boolean = false) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurface
    )
}

// ---- 3. Score --------------------------------------------------------------

@Composable
private fun ScoreComparisonWidget(block: ComparisonUiBlock.Score) {
    val aWins = block.optionA.score > block.optionB.score
    val bWins = block.optionB.score > block.optionA.score
    Column(Modifier.fillMaxWidth()) {
        block.title?.takeIf { it.isNotBlank() }?.let { SectionTitle(it) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            ScoreCard(block.optionA, block.metric, aWins, Modifier.weight(1f))
            ScoreCard(block.optionB, block.metric, bWins, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ScoreCard(
    option: ScoreOption,
    metric: String,
    winner: Boolean,
    modifier: Modifier = Modifier
) {
    ComparisonCard(modifier = modifier) {
        Text(
            text = option.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = formatScore(option),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = if (winner) positiveColor() else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = metric,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.sm))
        if (winner) {
            ComparisonPill("Winner", PillTone.PRIMARY)
        } else {
            // Reserve the pill's height so both cards line up.
            Spacer(Modifier.height(22.dp))
        }
    }
}

private fun formatScore(option: ScoreOption): String {
    fun fmt(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString()
        else String.format(Locale.US, "%.1f", value)

    return "${fmt(option.score)}/${fmt(option.maxScore)}"
}

// ---- 4. Pros & cons --------------------------------------------------------

@Composable
private fun ProsConsWidget(block: ComparisonUiBlock.ProsCons) {
    Column(Modifier.fillMaxWidth()) {
        block.title?.takeIf { it.isNotBlank() }?.let { SectionTitle(it) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            ProsConsCard(
                title = block.prosTitle,
                items = block.pros,
                positive = true,
                modifier = Modifier.weight(1f)
            )
            ProsConsCard(
                title = block.consTitle,
                items = block.cons,
                positive = false,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ProsConsCard(
    title: String,
    items: List<String>,
    positive: Boolean,
    modifier: Modifier = Modifier
) {
    val tone = if (positive) positiveColor() else negativeColor()
    ComparisonCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (positive) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = tone
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        if (items.isEmpty()) {
            Text(
                text = "—",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                items.forEach { item -> BulletLine(buildAnnotatedString { append(item) }) }
            }
        }
    }
}

// ---- 5. Priority (interactive) --------------------------------------------

@Composable
private fun PriorityComparisonWidget(block: ComparisonUiBlock.Priority) {
    if (block.priorities.isEmpty()) return
    var selectedIndex by remember(block) { mutableStateOf(0) }
    val selected: PriorityOption = block.priorities[selectedIndex.coerceIn(0, block.priorities.lastIndex)]
    ComparisonCard {
        Text(
            text = block.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Spacing.xs))
        block.priorities.forEachIndexed { index, priority ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = index == selectedIndex,
                        role = Role.RadioButton,
                        onClick = { selectedIndex = index }
                    )
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = index == selectedIndex,
                    onClick = null
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = priority.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "Selected category: ${selected.label}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (selected.recommendation.isNotBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = selected.recommendation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ---- Image pipeline --------------------------------------------------------

/**
 * Fixed-height entity image: a real picture when one exists, a shimmer while
 * a lookup is in flight, and a neutral icon once the pipeline is done with no
 * result. The last part matters: shimmering for a phrase that will never
 * resolve is indistinguishable from a broken image, which is exactly how it
 * looked before.
 *
 * [imageQuery] is the model's phrase, [fallbackQuery] the entity title used
 * when the phrase does not resolve.
 */
@Composable
fun SmartEntityImage(
    imageUrl: String?,
    imageQuery: String?,
    fallbackQuery: String? = null,
    resolvedUrls: Map<String, String> = emptyMap(),
    missingQueries: Set<String> = emptySet(),
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    contentScale: ContentScale = ContentScale.Fit
) {
    val lookups = listOfNotNull(imageQuery, fallbackQuery).filter { it.isNotBlank() }
    val resolved = lookups.firstNotNullOfOrNull { key ->
        resolvedUrls[key]?.takeIf { it.isNotBlank() }
    }
    val url = imageUrl?.takeIf { it.isNotBlank() } ?: resolved
    val settled = lookups.isNotEmpty() &&
        lookups.all { ImageQuery.key(it) in missingQueries }
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        when {
            url != null -> SubcomposeAsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                loading = { ShimmerFill() },
                error = { FallbackGlyph() },
                success = {
                    SubcomposeAsyncImageContent(
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
            // A lookup is still in flight for this entity.
            !settled && lookups.isNotEmpty() -> ShimmerFill()
            else -> FallbackGlyph()
        }
    }
}

@Composable
private fun ShimmerFill() {
    val transition = rememberInfiniteTransition(label = "comparisonImageShimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "comparisonImageShimmerAlpha"
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
    )
}

@Composable
private fun FallbackGlyph() {
    Icon(
        imageVector = Icons.Outlined.Image,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.size(30.dp)
    )
}

/** Compact placeholder while a comparison block is still streaming in. */
@Composable
fun ComparisonStreamSkeleton(spacing: ReplySpacingProfile) {
    val barHeight = (12f * spacing.paragraph.lineHeightScale).dp
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        val transition = rememberInfiniteTransition(label = "comparisonSkeleton")
        val alpha by transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 0.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "comparisonSkeletonAlpha"
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
        )
        Box(
            Modifier
                .fillMaxWidth(0.7f)
                .height(barHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
        )
    }
}

// ---- Shared pieces ---------------------------------------------------------

@Composable
private fun ComparisonCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = Spacing.sm)
    )
}

@Composable
private fun BulletLine(text: androidx.compose.ui.text.AnnotatedString) {
    Row {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private enum class PillTone { PRIMARY, SECONDARY }

@Composable
private fun ComparisonPill(label: String, tone: PillTone) {
    val background = when (tone) {
        PillTone.PRIMARY -> softGreenBackground()
        PillTone.SECONDARY -> softBlueBackground()
    }
    val foreground = when (tone) {
        PillTone.PRIMARY -> softGreenText()
        PillTone.SECONDARY -> softBlueText()
    }
    Surface(shape = RoundedCornerShape(8.dp), color = background, contentColor = foreground) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 3.dp)
        )
    }
}

@Composable
private fun positiveColor(): Color =
    if (LocalAppDarkMode.current) Color(0xFF7DD3A7) else Color(0xFF1F7A4D)

@Composable
private fun negativeColor(): Color =
    if (LocalAppDarkMode.current) Color(0xFFF2B8B5) else Color(0xFFB3261E)

@Composable
private fun softGreenBackground(): Color =
    if (LocalAppDarkMode.current) Color(0xFF1D3A2D) else Color(0xFFDCF5E7)

@Composable
private fun softGreenText(): Color =
    if (LocalAppDarkMode.current) Color(0xFF9BE3BC) else Color(0xFF17603D)

@Composable
private fun softBlueBackground(): Color =
    if (LocalAppDarkMode.current) Color(0xFF1E2C42) else Color(0xFFE1ECFC)

@Composable
private fun softBlueText(): Color =
    if (LocalAppDarkMode.current) Color(0xFFA9C7F5) else Color(0xFF1A4C8C)
