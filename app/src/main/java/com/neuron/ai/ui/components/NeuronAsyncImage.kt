package com.neuron.ai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.neuron.ai.core.web.EntityImageResolver
import com.neuron.ai.ui.theme.Radius
import com.neuron.ai.ui.theme.Spacing

val LocalEntityImageResolver = staticCompositionLocalOf<EntityImageResolver?> { null }
val LocalComparisonImages = staticCompositionLocalOf<Map<String, String>> { emptyMap() }
val LocalMissingImages = staticCompositionLocalOf<Set<String>> { emptySet() }
val LocalOnImagesNeeded = staticCompositionLocalOf<(List<String>) -> Unit> { {} }

/**
 * High-performance, low-latency image composable for chat and UI cards.
 *
 * Configured with standard browser headers to prevent CDN 403 Forbidden drops
 * (e.g. Unsplash, Wikimedia), with automatic entity query resolution, smooth
 * loading placeholders and graceful error fallbacks.
 */
@Composable
fun NeuronAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    minHeight: Dp = 140.dp,
    onClick: (() -> Unit)? = null,
    placeholder: @Composable (BoxScope.() -> Unit)? = null,
    error: @Composable (BoxScope.() -> Unit)? = null
) {
    val context = LocalContext.current
    val imageResolver = LocalEntityImageResolver.current
    val comparisonImages = LocalComparisonImages.current
    val onImagesNeeded = LocalOnImagesNeeded.current

    var resolvedUrl by remember(model) { mutableStateOf<String?>(null) }
    var resolutionAttempted by remember(model) { mutableStateOf(false) }

    fun isDirectUrl(value: String): Boolean {
        val t = value.trim()
        return t.startsWith("http://", ignoreCase = true) ||
            t.startsWith("https://", ignoreCase = true) ||
            t.startsWith("content://", ignoreCase = true) ||
            t.startsWith("file://", ignoreCase = true)
    }

    val sanitizedModel = remember(model, resolvedUrl) {
        val target = resolvedUrl ?: model
        if (target is String) {
            val trimmed = target.trim().trim('`', '"', '\'')
            when {
                trimmed.startsWith("http://localhost", ignoreCase = true) ||
                    trimmed.startsWith("http://127.0.0.1", ignoreCase = true) ||
                    trimmed.startsWith("http://10.", ignoreCase = true) ||
                    trimmed.startsWith("http://192.168.", ignoreCase = true) -> trimmed
                trimmed.startsWith("http://", ignoreCase = true) -> "https://${trimmed.substring(7)}"
                isDirectUrl(trimmed) -> trimmed
                // Non-URL entity query: wait until resolvedUrl is obtained so Coil does not error immediately
                else -> null
            }
        } else {
            target
        }
    }

    // Auto-resolve non-URL entity queries (e.g. "Talk to Me (2022) Movie Poster")
    LaunchedEffect(model, comparisonImages) {
        if (model is String && resolvedUrl == null) {
            val trimmed = model.trim().trim('`', '"', '\'')
            if (!isDirectUrl(trimmed) && trimmed.isNotBlank()) {
                val cached = comparisonImages[trimmed]
                if (!cached.isNullOrBlank()) {
                    resolvedUrl = cached
                } else if (imageResolver != null && !resolutionAttempted) {
                    resolutionAttempted = true
                    onImagesNeeded(listOf(trimmed))
                    runCatching {
                        val cleanQuery = trimmed.replace(
                            Regex("(?i)\\s+(movie\\s+poster|official\\s+poster|poster|photo|picture|image|wallpaper|fan\\s+art)$"),
                            ""
                        ).trim()
                        val fetched = imageResolver.resolve(cleanQuery)
                        if (!fetched.isNullOrBlank()) {
                            resolvedUrl = fetched
                        }
                    }
                }
            }
        }
    }

    val imageRequest = remember(sanitizedModel) {
        ImageRequest.Builder(context)
            .data(sanitizedModel)
            .crossfade(150)
            .setHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .setHeader("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .build()
    }

    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    SubcomposeAsyncImage(
        model = imageRequest,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.then(clickModifier),
        loading = {
            if (placeholder != null) {
                placeholder()
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
            }
        },
        error = {
            // When an image fails to load, attempt fallback resolution using contentDescription
            LaunchedEffect(contentDescription) {
                if (!resolutionAttempted && resolvedUrl == null && !contentDescription.isNullOrBlank() && imageResolver != null) {
                    resolutionAttempted = true
                    runCatching {
                        val fetched = imageResolver.resolve(contentDescription.trim())
                        if (!fetched.isNullOrBlank()) {
                            resolvedUrl = fetched
                        }
                    }
                }
            }

            if (error != null) {
                error()
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(Spacing.sm),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Image,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        if (!contentDescription.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = contentDescription,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        success = {
            SubcomposeAsyncImageContent()
        }
    )
}
