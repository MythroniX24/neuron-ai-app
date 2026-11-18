package com.neuron.ai.ui.a2ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import com.neuron.ai.core.a2ui.A2uiChild
import com.neuron.ai.core.a2ui.A2uiComponent
import com.neuron.ai.core.a2ui.A2uiProtocol
import com.neuron.ai.core.a2ui.A2uiResolver
import com.neuron.ai.core.a2ui.A2uiSurface
import com.neuron.ai.core.a2ui.A2uiValue
import com.neuron.ai.ui.components.FullScreenImage
import com.neuron.ai.ui.components.LocalImageOpener
import com.neuron.ai.ui.files.FileRef
import com.neuron.ai.ui.files.LocalFileOpener
import com.neuron.ai.ui.files.OpenFileRequest
import com.neuron.ai.ui.files.downloadToDownloads
import com.neuron.ai.ui.markdown.MarkdownText
import com.neuron.ai.ui.markdown.SyntaxHighlighter
import com.neuron.ai.ui.theme.LocalAppDarkMode
import com.neuron.ai.ui.theme.Radius
import com.neuron.ai.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Compose adapter for the A2UI protocol.
 *
 * Upstream A2UI ships the core library plus an adapter per framework (React,
 * Flutter, SwiftUI). This is the Jetpack Compose adapter for the basic
 * catalog, written against our design system, plus the Neuron file components
 * so the agent can present files, code, HTML previews and documents as real
 * chat cards. Component → composable mapping mirrors
 * `catalogs/basic/v1/basic_catalog_implementation_guide.md`.
 */

/** An interaction the surface reports back to the chat. */
data class A2uiActionRequest(
    val name: String,
    val context: String,
    val userMessage: String?
) {
    /** The message a click becomes for the agent. */
    fun toUserMessage(): String = userMessage?.takeIf { it.isNotBlank() }
        ?: if (context.isBlank() || context == "{}") {
            "Selected: $name"
        } else {
            "Selected: $name ($context)"
        }
}

/** Buttons dispatch here; the chat screen forwards them to the agent. */
val LocalA2uiAction = compositionLocalOf<(A2uiActionRequest) -> Unit> { {} }

/** Writes a component's bound value back into its surface's data model. */
private val LocalA2uiWrite =
    compositionLocalOf<(surfaceId: String, path: String, value: JsonElement) -> Unit> { { _, _, _ -> } }

/** A Modal installs this so its trigger button opens the dialog. */
private val LocalModalTrigger = compositionLocalOf<(() -> Unit)?> { null }

/**
 * Renders one fenced `a2ui` block. Surfaces keep their own data model, so a
 * form's typed values are visible to the buttons of the same block.
 */
@Composable
fun A2uiBlockView(source: String, modifier: Modifier = Modifier) {
    val parsed = remember(source) { A2uiProtocol.render(source) }
    if (parsed.isEmpty()) {
        val trimmed = source.trim()
        val isStreaming = !trimmed.endsWith("}") && !trimmed.endsWith("]")
        if (isStreaming) {
            A2uiBuildingSkeleton(modifier)
        } else {
            A2uiCardFallback(trimmed, modifier)
        }
        return
    }
    var surfaces by remember(parsed) { mutableStateOf(parsed) }
    val write: (String, String, JsonElement) -> Unit = { surfaceId, path, value ->
        surfaces = surfaces.map { surface ->
            if (surface.id == surfaceId) {
                surface.copy(dataModel = A2uiValue.setPointer(surface.dataModel, path, value))
            } else {
                surface
            }
        }
    }

    CompositionLocalProvider(LocalA2uiWrite provides write) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            surfaces.forEach { surface -> A2uiSurfaceView(surface) }
        }
    }
}

@Composable
private fun A2uiCardFallback(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Text(
                text = text.take(600),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun A2uiBuildingSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = "Building card…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Mounts a surface's `root` component. */
@Composable
fun A2uiSurfaceView(surface: A2uiSurface, modifier: Modifier = Modifier) {
    val resolver = remember(surface) { A2uiResolver(surface) }
    val root = surface.root ?: return
    Column(modifier.fillMaxWidth()) {
        A2uiNode(surface, resolver, root.id, surface.dataModel, depth = 0)
    }
}

@Composable
private fun A2uiNode(
    surface: A2uiSurface,
    resolver: A2uiResolver,
    id: String,
    scope: JsonElement,
    depth: Int
) {
    if (depth > 32) return
    val component = surface.components[id] ?: return
    A2uiComponentView(component, surface, resolver, scope, depth)
}

@Composable
private fun A2uiComponentView(
    component: A2uiComponent,
    surface: A2uiSurface,
    resolver: A2uiResolver,
    scope: JsonElement,
    depth: Int
) {
    when (component.type) {
        "Text" -> {
            val text = resolver.string(component, "text", scope).orEmpty()
            if (text.isBlank()) return
            if (resolver.string(component, "variant", scope) == "caption") {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                MarkdownText(markdown = text)
            }
        }

        "Image" -> A2uiImage(component, surface, resolver, scope)

        "Icon" -> {
            val name = resolver.string(component, "name", scope).orEmpty()
            Icon(
                imageVector = a2uiIcon(name),
                contentDescription = name,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        "Row" -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = rowArrangement(resolver.string(component, "justify", scope)),
            verticalAlignment = rowAlignment(resolver.string(component, "align", scope))
        ) {
            Children(component, surface, resolver, scope, depth)
        }

        "Column" -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = columnArrangement(
                resolver.string(component, "justify", scope)
            ),
            horizontalAlignment = columnAlignment(resolver.string(component, "align", scope))
        ) {
            Children(component, surface, resolver, scope, depth)
        }

        "List" -> {
            val horizontal = resolver.string(component, "direction", scope) == "horizontal"
            if (horizontal) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Children(component, surface, resolver, scope, depth)
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    horizontalAlignment = columnAlignment(resolver.string(component, "align", scope))
                ) {
                    Children(component, surface, resolver, scope, depth)
                }
            }
        }

        "Card" -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.md),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(Modifier.padding(Spacing.md)) {
                Children(component, surface, resolver, scope, depth)
            }
        }

        "Divider" -> if (resolver.string(component, "axis", scope) == "vertical") {
            Box(
                Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(MaterialTheme.colorScheme.outline)
            )
        } else {
            HorizontalDivider()
        }

        "Button" -> A2uiButton(component, surface, resolver, scope)

        "TextField" -> A2uiTextField(component, surface, resolver, scope)

        "CheckBox" -> A2uiCheckBox(component, surface, resolver, scope)

        "ChoicePicker" -> A2uiChoicePicker(component, surface, resolver, scope)

        "Slider" -> A2uiSlider(component, surface, resolver, scope)

        "Tabs" -> A2uiTabs(component, surface, resolver, scope, depth)

        "Modal" -> A2uiModal(component, surface, resolver, scope, depth)

        "Video", "AudioPlayer" -> A2uiMediaLink(component, surface, resolver, scope)

        // ---- Neuron extensions: files, documents, code -------------------
        "FileCard" -> A2uiFileCard(component, resolver, scope)

        "FileList" -> A2uiFileList(component, resolver, scope)

        "CodeCard" -> A2uiCodeCard(component, resolver, scope)

        "DocumentPreview" -> A2uiDocumentPreview(component, resolver, scope)

        else -> Text(
            text = "Unsupported component: ${component.type}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Children(
    component: A2uiComponent,
    surface: A2uiSurface,
    resolver: A2uiResolver,
    scope: JsonElement,
    depth: Int
) {
    val children: List<A2uiChild> = resolver.children(component, scope)
    children.forEach { child ->
        A2uiNode(surface, resolver, child.id, child.scope, depth + 1)
    }
}

// ---------------------------------------------------------------------------
// Basic catalog widgets
// ---------------------------------------------------------------------------

@Composable
private fun A2uiImage(
    component: A2uiComponent,
    surface: A2uiSurface,
    resolver: A2uiResolver,
    scope: JsonElement
) {
    val context = LocalContext.current
    val raw = resolver.string(component, "url", scope).orEmpty()
    if (raw.isBlank()) return
    val model = remember(raw) {
        if (raw.startsWith("http") || raw.startsWith("content:") || raw.startsWith("file:")) {
            raw
        } else {
            FileRef.resolve(context, raw)?.uri?.toString() ?: raw
        }
    }
    val description = resolver.string(component, "description", scope)
    val variant = resolver.string(component, "variant", scope) ?: "mediumFeature"
    val opener = LocalImageOpener.current

    val height = when (variant) {
        "icon" -> 40.dp
        "avatar" -> 48.dp
        "smallFeature" -> 120.dp
        "largeFeature" -> 240.dp
        "header" -> 160.dp
        else -> 180.dp
    }
    val shape = when (variant) {
        "avatar" -> CircleShape
        "icon" -> RoundedCornerShape(Radius.sm)
        else -> RoundedCornerShape(Radius.md)
    }
    val scale = when (resolver.string(component, "fit", scope)) {
        "contain" -> ContentScale.Fit
        "cover" -> ContentScale.Crop
        else -> ContentScale.Crop
    }
    SubcomposeAsyncImage(
        model = model,
        contentDescription = description ?: "Image",
        contentScale = scale,
        modifier = Modifier
            .then(if (variant == "icon" || variant == "avatar") Modifier.size(height) else Modifier.fillMaxWidth())
            .then(if (variant == "icon" || variant == "avatar") Modifier else Modifier.height(height))
            .clip(shape)
            .clickable { opener(FullScreenImage(model, description)) },
        loading = {
            Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp))
            }
        },
        error = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .background(MaterialTheme.colorScheme.surfaceVariant, shape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = description?.takeIf { it.isNotBlank() } ?: "Image unavailable",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun A2uiButton(
    component: A2uiComponent,
    surface: A2uiSurface,
    resolver: A2uiResolver,
    scope: JsonElement
) {
    val childId = resolver.string(component, "child", scope) ?: return
    val child = surface.components[childId]
    val label = child?.let { resolver.string(it, "text", scope) }.orEmpty()
    val action = LocalA2uiAction.current
    val modal = LocalModalTrigger.current
    val variant = resolver.string(component, "variant", scope) ?: "default"
    val event = (component.props["action"] as? JsonObject)?.get("event") as? JsonObject

    val onClick: () -> Unit = {
        actionMessage(event, resolver, scope)?.let(action)
        modal?.invoke()
    }

    val content: @Composable () -> Unit = {
        if (label.isNotBlank()) {
            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else {
            A2uiNode(surface, resolver, childId, scope, depth = 1)
        }
    }

    when (variant) {
        "borderless" -> TextButton(onClick = onClick) { content() }
        "primary" -> Button(onClick = onClick) { content() }
        else -> OutlinedButton(onClick = onClick) { content() }
    }
}

@Composable
private fun A2uiTextField(
    component: A2uiComponent,
    surface: A2uiSurface,
    resolver: A2uiResolver,
    scope: JsonElement
) {
    val label = resolver.string(component, "label", scope).orEmpty()
    val placeholder = resolver.string(component, "placeholder", scope)
    val variant = resolver.string(component, "variant", scope) ?: "shortText"
    val bindingPath = (component.props["value"] as? JsonObject)?.string("@path")
    val bound = resolver.string(component, "value", scope).orEmpty()
    var local by remember(bound) { mutableStateOf(bound) }
    val write = LocalA2uiWrite.current

    OutlinedTextField(
        value = local,
        onValueChange = { updated ->
            local = updated
            bindingPath?.let { path ->
                write(surface.id, path, JsonPrimitive(updated))
            }
        },
        modifier = Modifier.fillMaxWidth(),
        label = if (label.isBlank()) null else ({ Text(label) }),
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = variant != "longText",
        minLines = if (variant == "longText") 3 else 1,
        visualTransformation = if (variant == "obscured") {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (variant == "number") KeyboardType.Number else KeyboardType.Text
        )
    )
}

@Composable
private fun A2uiCheckBox(
    component: A2uiComponent,
    surface: A2uiSurface,
    resolver: A2uiResolver,
    scope: JsonElement
) {
    val label = resolver.string(component, "label", scope).orEmpty()
    val checked = resolver.boolean(component, "value", scope) ?: false
    val bindingPath = (component.props["value"] as? JsonObject)?.string("@path")
    val write = LocalA2uiWrite.current

    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                bindingPath?.let { write(surface.id, it, JsonPrimitive(!checked)) }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { next ->
                bindingPath?.let { write(surface.id, it, JsonPrimitive(next)) }
            }
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun A2uiChoicePicker(
    component: A2uiComponent,
    surface: A2uiSurface,
    resolver: A2uiResolver,
    scope: JsonElement
) {
    val label = resolver.string(component, "label", scope).orEmpty()
    val multiple = resolver.string(component, "variant", scope) == "multipleSelection"
    val selected = resolver.strings(component, "value", scope).toMutableSet()
    val bindingPath = (component.props["value"] as? JsonObject)?.string("@path")
    val write = LocalA2uiWrite.current
    val options = (component.props["options"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()

    Column(Modifier.fillMaxWidth()) {
        if (label.isNotBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
        }
        options.forEach { option ->
            val value = option.string("value") ?: return@forEach
            val optionLabel = A2uiValue.asString(
                A2uiValue.resolve(option["label"], scope, surface.dataModel)
            ) ?: value
            val isSelected = value in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (multiple) {
                            if (isSelected) selected.remove(value) else selected.add(value)
                        } else {
                            selected.clear()
                            selected.add(value)
                        }
                        bindingPath?.let { path ->
                            write(
                                surface.id,
                                path,
                                JsonArray(selected.map { JsonPrimitive(it) })
                            )
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = isSelected, onCheckedChange = null)
                Text(optionLabel, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun A2uiSlider(
    component: A2uiComponent,
    surface: A2uiSurface,
    resolver: A2uiResolver,
    scope: JsonElement
) {
    val min = resolver.number(component, "min", scope) ?: 0.0
    val max = resolver.number(component, "max", scope) ?: 100.0
    val bound = resolver.number(component, "value", scope) ?: min
    val label = resolver.string(component, "label", scope)
    val bindingPath = (component.props["value"] as? JsonObject)?.string("@path")
    val write = LocalA2uiWrite.current
    var local by remember(bound) { mutableStateOf(bound.toFloat()) }

    Column(Modifier.fillMaxWidth()) {
        label?.let {
            Text(it, style = MaterialTheme.typography.labelMedium)
        }
        if (max <= min) return@Column
        Slider(
            value = local.coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { local = it },
            valueRange = min.toFloat()..max.toFloat(),
            onValueChangeFinished = {
                bindingPath?.let { path ->
                    write(surface.id, path, JsonPrimitive(local.toDouble()))
                }
            }
        )
    }
}

@Composable
private fun A2uiTabs(
    component: A2uiComponent,
    surface: A2uiSurface,
    resolver: A2uiResolver,
    scope: JsonElement,
    depth: Int
) {
    val tabs = (component.props["tabs"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()
    if (tabs.isEmpty()) return
    var selected by remember { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, tabs.lastIndex)

    Column(Modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = index) {
            tabs.forEachIndexed { tabIndex, tab ->
                val title = A2uiValue.asString(
                    A2uiValue.resolve(tab["title"], scope, surface.dataModel)
                ).orEmpty()
                Tab(
                    selected = tabIndex == index,
                    onClick = { selected = tabIndex },
                    text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        tabs.getOrNull(index)?.let { tab -> tab.string("child") }?.let { childId ->
            A2uiNode(surface, resolver, childId, scope, depth + 1)
        }
    }
}

@Composable
private fun A2uiModal(
    component: A2uiComponent,
    surface: A2uiSurface,
    resolver: A2uiResolver,
    scope: JsonElement,
    depth: Int
) {
    var open by remember { mutableStateOf(false) }
    val trigger = component.props.string("trigger")
    val content = component.props.string("content")

    if (trigger != null) {
        CompositionLocalProvider(LocalModalTrigger provides { open = true }) {
            A2uiNode(surface, resolver, trigger, scope, depth + 1)
        }
    }
    if (!open || content == null) return

    Dialog(
        onDismissRequest = { open = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
                .heightIn(max = 560.dp)
        ) {
            Column(Modifier.padding(Spacing.md)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { open = false }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    A2uiNode(surface, resolver, content, scope, depth + 1)
                }
            }
        }
    }
}

@Composable
private fun A2uiMediaLink(
    component: A2uiComponent,
    surface: A2uiSurface,
    resolver: A2uiResolver,
    scope: JsonElement
) {
    val context = LocalContext.current
    val url = resolver.string(component, "url", scope).orEmpty()
    val description = resolver.string(component, "description", scope)
        ?: if (component.type == "Video") "Video" else "Audio"

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
            .padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(Spacing.sm))
        Text(description, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------
// Neuron extensions: files and documents
// ---------------------------------------------------------------------------

@Composable
private fun A2uiFileCard(
    component: A2uiComponent,
    resolver: A2uiResolver,
    scope: JsonElement
) {
    val context = LocalContext.current
    val opener = LocalFileOpener.current
    val path = resolver.string(component, "path", scope).orEmpty()
    if (path.isBlank()) return
    val name = resolver.string(component, "name", scope)?.takeIf { it.isNotBlank() }
        ?: path.substringAfterLast('/')
    val ref = remember(path, name) { FileRef.resolve(context, path, name) }
    val detail = resolver.string(component, "detail", scope)
    val status = resolver.string(component, "status", scope)
    val subtitle = detail?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(
            ref?.extension?.uppercase()?.ifBlank { null },
            ref?.sizeLabel,
            if (ref == null && !path.startsWith("http")) "file not found" else null
        ).joinToString(" · ")
    val tint = fileTint(ref?.extension?.ifBlank { null } ?: name.substringAfterLast('.', ""))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (ref != null) {
                    opener(OpenFileRequest(path, name))
                } else if (path.startsWith("http")) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(path))) }
                }
            },
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FileTile(extension = ref?.extension ?: name.substringAfterLast('.', ""))
                Spacer(Modifier.width(Spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = {
                    if (ref != null) {
                        opener(OpenFileRequest(path, name))
                    } else if (path.startsWith("http")) {
                        downloadUrl(context, path, name)
                    }
                }) {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = "Download $name",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(Modifier.height(Spacing.xs))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!status.isNullOrBlank()) {
                    StatusChip(status, tint)
                    Spacer(Modifier.width(Spacing.sm))
                }
                if (ref?.isHtml == true) {
                    TextButton(onClick = { opener(OpenFileRequest(path, name)) }) {
                        Text("Preview")
                    }
                }
                TextButton(onClick = {
                    if (ref != null) {
                        opener(OpenFileRequest(path, name))
                    } else if (path.startsWith("http")) {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(path)))
                        }
                    } else {
                        Toast.makeText(context, "This file was not found", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Open")
                }
            }
        }
    }
}

@Composable
private fun A2uiFileList(
    component: A2uiComponent,
    resolver: A2uiResolver,
    scope: JsonElement
) {
    val context = LocalContext.current
    val opener = LocalFileOpener.current
    val title = resolver.string(component, "title", scope)
    val files = (component.props["files"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        .orEmpty()
    if (files.isEmpty()) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(vertical = Spacing.sm)) {
            title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
                )
            }
            files.forEach { file ->
                val path = file.string("path") ?: return@forEach
                val name = file.string("name")?.takeIf { it.isNotBlank() }
                    ?: path.substringAfterLast('/')
                val ref = remember(path) { FileRef.resolve(context, path, name) }
                val detail = file.string("detail")
                    ?: listOfNotNull(
                        ref?.extension?.uppercase()?.ifBlank { null },
                        ref?.sizeLabel
                    ).joinToString(" · ")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (ref != null) {
                                opener(OpenFileRequest(path, name))
                            } else if (path.startsWith("http")) {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(path)))
                                }
                            }
                        }
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FileTile(
                        extension = ref?.extension ?: name.substringAfterLast('.', ""),
                        small = true
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (detail.isNotBlank()) {
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(onClick = {
                        if (ref != null) {
                            downloadToDownloads(context, ref)
                        } else if (path.startsWith("http")) {
                            downloadUrl(context, path, name)
                        }
                    }) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = "Download $name",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun A2uiCodeCard(
    component: A2uiComponent,
    resolver: A2uiResolver,
    scope: JsonElement
) {
    val context = LocalContext.current
    val opener = LocalFileOpener.current
    val clipboard = LocalClipboardManager.current
    val dark = LocalAppDarkMode.current
    val path = resolver.string(component, "path", scope)?.takeIf { it.isNotBlank() }
    val inline = resolver.string(component, "code", scope)
    val ref = remember(path) { path?.let { FileRef.resolve(context, it) } }
    val language = resolver.string(component, "language", scope)
        ?.takeIf { it.isNotBlank() }
        ?: ref?.language
    val title = resolver.string(component, "title", scope)
        ?: ref?.name
        ?: language?.let { "Code ($it)" }
        ?: "Code"

    val code = produceState<String?>(initialValue = inline, inline, path) {
        if (inline != null) {
            value = inline
        } else if (path != null) {
            value = withContext(Dispatchers.IO) {
                FileRef.resolve(context, path)?.readText(context, limitChars = 40_000)
            }
        }
    }.value

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        color = if (dark) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language?.let { "$title · $it" } ?: title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (code != null) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = "Copy code",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (path != null) {
                    IconButton(onClick = { opener(OpenFileRequest(path, ref?.name)) }) {
                        Icon(
                            Icons.Outlined.OpenInNew,
                            contentDescription = "Open file",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (code == null) {
                Text(
                    text = "Loading…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.md)
                )
            } else {
                val highlighted = remember(code, language, dark) {
                    SyntaxHighlighter.highlight(code, language, dark)
                }
                Text(
                    text = highlighted,
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .horizontalScroll(rememberScrollState())
                        .padding(Spacing.md)
                )
            }
        }
    }
}

@Composable
private fun A2uiDocumentPreview(
    component: A2uiComponent,
    resolver: A2uiResolver,
    scope: JsonElement
) {
    val context = LocalContext.current
    val opener = LocalFileOpener.current
    val path = resolver.string(component, "path", scope).orEmpty()
    val ref = remember(path) { if (path.isBlank()) null else FileRef.resolve(context, path) }
    val title = resolver.string(component, "title", scope)
        ?: ref?.name
        ?: "Document"
    val body = resolver.string(component, "text", scope)
    val footer = resolver.string(component, "footer", scope)
    val badge = ref?.extension?.uppercase()?.ifBlank { null }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = ref != null) {
                ref?.let { opener(OpenFileRequest(path, it.name)) }
            },
        shape = RoundedCornerShape(Radius.md),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (badge != null) {
                    Surface(
                        shape = RoundedCornerShape(Radius.sm),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Spacer(Modifier.height(Spacing.sm))
            if (!body.isNullOrBlank()) {
                MarkdownText(markdown = body)
            }
            footer?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FileTile(extension: String, small: Boolean = false) {
    val tint = fileTint(extension)
    Box(
        Modifier
            .size(if (small) 32.dp else 44.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = fileIcon(extension),
            contentDescription = extension,
            tint = tint,
            modifier = Modifier.size(if (small) 18.dp else 24.dp)
        )
    }
}

@Composable
private fun StatusChip(label: String, tint: Color) {
    Surface(
        shape = RoundedCornerShape(Radius.sm),
        color = tint.copy(alpha = 0.14f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = tint,
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = 2.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun actionMessage(
    event: JsonObject?,
    resolver: A2uiResolver,
    scope: JsonElement
): A2uiActionRequest? {
    event ?: return null
    val name = event.string("name") ?: return null
    val userMessage = A2uiValue.asString(resolver.resolve(scope, event["userMessage"]))
    val contextObject = event["context"] as? JsonObject
    val resolved = contextObject?.let { context ->
        JsonObject(
            context.mapValues { (_, value) -> resolver.resolve(scope, value) ?: JsonNull }
        )
    }
    return A2uiActionRequest(
        name = name,
        context = resolved?.toString() ?: "{}",
        userMessage = userMessage
    )
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun a2uiIcon(name: String): ImageVector = when (name) {
    "add" -> Icons.Outlined.Add
    "arrowBack" -> Icons.Outlined.ArrowBack
    "arrowForward" -> Icons.Outlined.ArrowForward
    "attachFile" -> Icons.Outlined.AttachFile
    "calendarToday" -> Icons.Outlined.CalendarToday
    "check" -> Icons.Outlined.Check
    "close" -> Icons.Outlined.Close
    "delete" -> Icons.Outlined.Delete
    "download" -> Icons.Outlined.Download
    "edit" -> Icons.Outlined.Edit
    "folder" -> Icons.Outlined.Folder
    "lock" -> Icons.Outlined.Lock
    "mail" -> Icons.Outlined.MailOutline
    "menu" -> Icons.Outlined.Menu
    "moreHoriz" -> Icons.Outlined.MoreHoriz
    "moreVert" -> Icons.Outlined.MoreVert
    "person" -> Icons.Outlined.Person
    "play" -> Icons.Outlined.PlayArrow
    "refresh" -> Icons.Outlined.Refresh
    "search" -> Icons.Outlined.Search
    "send" -> Icons.Outlined.Send
    "settings" -> Icons.Outlined.Settings
    "share" -> Icons.Outlined.Share
    "shoppingCart" -> Icons.Outlined.ShoppingCart
    "star" -> Icons.Outlined.Star
    "upload" -> Icons.Outlined.Upload
    "warning" -> Icons.Outlined.WarningAmber
    "error" -> Icons.Outlined.ErrorOutline
    "info" -> Icons.Outlined.Info
    else -> Icons.Outlined.Info
}

private fun fileIcon(extension: String): ImageVector = when (extension.lowercase()) {
    "png", "jpg", "jpeg", "webp", "gif", "svg", "bmp", "heic" -> Icons.Outlined.Image
    "pdf", "doc", "docx", "txt", "md", "rtf" -> Icons.Outlined.Description
    "zip", "tar", "gz", "rar", "7z" -> Icons.Outlined.Folder
    "kt", "kts", "java", "py", "js", "ts", "json", "xml", "html", "css", "sh" ->
        Icons.Outlined.Code
    else -> Icons.Outlined.Description
}

private fun fileTint(extension: String): Color = when (extension.lowercase()) {
    "pdf" -> Color(0xFFDC2626)
    "doc", "docx", "txt", "md", "rtf" -> Color(0xFF2563EB)
    "xls", "xlsx", "csv" -> Color(0xFF15803D)
    "ppt", "pptx" -> Color(0xFFEA580C)
    "png", "jpg", "jpeg", "webp", "gif", "svg", "bmp", "heic" -> Color(0xFF7C3AED)
    "kt", "kts", "java", "py", "js", "ts", "json", "xml", "html", "css", "sh" ->
        Color(0xFF0F766E)
    "zip", "tar", "gz", "rar", "7z" -> Color(0xFFB45309)
    else -> Color(0xFF64748B)
}

private fun rowArrangement(justify: String?): Arrangement.Horizontal = when (justify) {
    "center" -> Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally)
    "end" -> Arrangement.spacedBy(Spacing.sm, Alignment.End)
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceAround" -> Arrangement.SpaceAround
    "spaceEvenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.spacedBy(Spacing.sm, Alignment.Start)
}

private fun rowAlignment(align: String?): Alignment.Vertical = when (align) {
    "start" -> Alignment.Top
    "center" -> Alignment.CenterVertically
    "end" -> Alignment.Bottom
    else -> Alignment.CenterVertically
}

private fun columnArrangement(justify: String?): Arrangement.Vertical = when (justify) {
    "center" -> Arrangement.spacedBy(Spacing.xs, Alignment.CenterVertically)
    "end" -> Arrangement.spacedBy(Spacing.xs, Alignment.Bottom)
    "spaceBetween" -> Arrangement.SpaceBetween
    "spaceAround" -> Arrangement.SpaceAround
    "spaceEvenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.spacedBy(Spacing.xs)
}

private fun columnAlignment(align: String?): Alignment.Horizontal = when (align) {
    "center" -> Alignment.CenterHorizontally
    "end" -> Alignment.End
    else -> Alignment.Start
}

/** Downloads an https card target with DownloadManager (survives the app). */
private fun downloadUrl(context: Context, url: String, name: String) {
    runCatching {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(context, "Downloading $name", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, "Could not download $name", Toast.LENGTH_SHORT).show()
    }
}
