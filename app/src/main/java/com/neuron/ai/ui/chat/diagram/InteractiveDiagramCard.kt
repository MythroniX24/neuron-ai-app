package com.neuron.ai.ui.chat.diagram

import android.graphics.Paint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatShapes
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neuron.ai.ui.theme.Spacing
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@Composable
fun InteractiveDiagramCard(
    typeHint: String,
    rawContent: String,
    modifier: Modifier = Modifier
) {
    val initialSpec = remember(rawContent) {
        DiagramParser.parse(typeHint, rawContent)
    }

    if (initialSpec == null) {
        Text(
            text = rawContent,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    // Mutable state for live drag and editing
    val points = remember(initialSpec) {
        mutableStateListOf<DiagramPoint>().apply {
            addAll(initialSpec.points.map { it.copy() })
        }
    }

    var title by remember(initialSpec) { mutableStateOf(initialSpec.title) }
    var showEditDialog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.tertiary
    val outlineColor = MaterialTheme.colorScheme.outline
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    fun resetDiagram() {
        points.clear()
        points.addAll(initialSpec.points.map { it.copy() })
        title = initialSpec.title
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = primaryColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (initialSpec.type == "tree") Icons.Outlined.AccountTree else Icons.Outlined.FormatShapes,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.width(Spacing.sm))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = onSurface
                    )
                    Text(
                        text = if (initialSpec.type == "tree") "Interactive Tree Graph" else "Interactive Geometry (Drag Vertices)",
                        style = MaterialTheme.typography.labelSmall,
                        color = onSurfaceVariant
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = primaryColor.copy(alpha = 0.1f),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(
                        text = "Editable",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = primaryColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                IconButton(
                    onClick = { showEditDialog = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Edit diagram",
                        tint = onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = { resetDiagram() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Reset",
                        tint = onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(rawContent)) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy spec",
                        tint = onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Canvas Content
            if (initialSpec.type == "tree" && initialSpec.treeRoot != null) {
                TreeCanvas(
                    root = initialSpec.treeRoot,
                    primaryColor = primaryColor,
                    textColor = onSurface,
                    surfaceColor = surfaceColor
                )
            } else {
                GeometryCanvas(
                    spec = initialSpec,
                    points = points,
                    primaryColor = primaryColor,
                    secondaryColor = secondaryColor,
                    outlineColor = outlineColor,
                    onSurface = onSurface
                )
            }
        }
    }

    // Edit Dialog
    if (showEditDialog) {
        AlertDialog(
            shape = RoundedCornerShape(18.dp),
            onDismissRequest = { showEditDialog = false },
            title = { Text("Edit Diagram") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Vertex Labels & Coordinates:",
                        style = MaterialTheme.typography.labelMedium,
                        color = onSurfaceVariant
                    )

                    points.forEachIndexed { index, pt ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            OutlinedTextField(
                                value = pt.label,
                                onValueChange = { pt.label = it },
                                label = { Text("Point") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Text("(${pt.x.toInt()}, ${pt.y.toInt()})", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showEditDialog = false }) {
                    Text("Done")
                }
            }
        )
    }
}

@Composable
private fun GeometryCanvas(
    spec: DiagramSpec,
    points: List<DiagramPoint>,
    primaryColor: Color,
    secondaryColor: Color,
    outlineColor: Color,
    onSurface: Color
) {
    var draggedPointIndex by remember { mutableStateOf<Int?>(null) }

    // Live calculated stats
    val pointMap = remember(points.map { "${it.id}_${it.x}_${it.y}" }) {
        points.associateBy { it.id }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(Color.Black.copy(alpha = 0.08f))
                .pointerInput(points) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val touchRadius = 45f
                            val foundIndex = points.indexOfFirst { pt ->
                                hypot(pt.x - offset.x, pt.y - offset.y) <= touchRadius
                            }
                            if (foundIndex != -1) {
                                draggedPointIndex = foundIndex
                            }
                        },
                        onDrag = { change, dragAmount ->
                            draggedPointIndex?.let { index ->
                                change.consume()
                                val pt = points[index]
                                val newX = (pt.x + dragAmount.x).coerceIn(30f, size.width.toFloat() - 30f)
                                val newY = (pt.y + dragAmount.y).coerceIn(30f, size.height.toFloat() - 30f)
                                pt.x = newX
                                pt.y = newY
                            }
                        },
                        onDragEnd = { draggedPointIndex = null },
                        onDragCancel = { draggedPointIndex = null }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                // 1. Draw subtle grid
                val gridSpacing = 40f
                for (x in 0..(canvasWidth / gridSpacing).toInt()) {
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.12f),
                        start = Offset(x * gridSpacing, 0f),
                        end = Offset(x * gridSpacing, canvasHeight),
                        strokeWidth = 1f
                    )
                }
                for (y in 0..(canvasHeight / gridSpacing).toInt()) {
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.12f),
                        start = Offset(0f, y * gridSpacing),
                        end = Offset(canvasWidth, y * gridSpacing),
                        strokeWidth = 1f
                    )
                }

                // 2. Draw Circle if present
                spec.circle?.let { c ->
                    val centerPt = pointMap[c.center] ?: points.firstOrNull()
                    if (centerPt != null) {
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.15f),
                            radius = c.radius * 1.5f,
                            center = Offset(centerPt.x, centerPt.y)
                        )
                        drawCircle(
                            color = primaryColor,
                            radius = c.radius * 1.5f,
                            center = Offset(centerPt.x, centerPt.y),
                            style = Stroke(width = 2.5f)
                        )
                    }
                }

                // 3. Draw Lines between points
                spec.lines.forEach { line ->
                    val p1 = pointMap[line.from]
                    val p2 = pointMap[line.to]
                    if (p1 != null && p2 != null) {
                        val pathEffect = if (line.isDashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null
                        drawLine(
                            color = primaryColor,
                            start = Offset(p1.x, p1.y),
                            end = Offset(p2.x, p2.y),
                            strokeWidth = 3f,
                            cap = StrokeCap.Round,
                            pathEffect = pathEffect
                        )

                        // Midpoint label
                        val midX = (p1.x + p2.x) / 2
                        val midY = (p1.y + p2.y) / 2
                        val dist = hypot(p2.x - p1.x, p2.y - p1.y) / 10f
                        val labelText = line.label ?: line.length ?: String.format("%.1f", dist)

                        drawContext.canvas.nativeCanvas.drawText(
                            labelText,
                            midX + 8f,
                            midY - 8f,
                            Paint().apply {
                                color = onSurface.toArgb()
                                textSize = 26f
                                isFakeBoldText = true
                            }
                        )
                    }
                }

                // 4. Draw Angle Arcs & Degree Values
                spec.angles.forEach { angle ->
                    val v = pointMap[angle.vertex]
                    val p1 = pointMap[angle.p1]
                    val p2 = pointMap[angle.p2]
                    if (v != null && p1 != null && p2 != null) {
                        val deg = DiagramParser.calculateAngle(v, p1, p2)
                        val startAngle1 = (atan2(p1.y - v.y, p1.x - v.x) * 180.0 / Math.PI).toFloat()
                        val startAngle2 = (atan2(p2.y - v.y, p2.x - v.x) * 180.0 / Math.PI).toFloat()

                        var sweep = startAngle2 - startAngle1
                        if (sweep < 0f) sweep += 360f
                        if (sweep > 180f) sweep -= 360f

                        val arcRadius = 36f
                        drawArc(
                            color = secondaryColor.copy(alpha = 0.8f),
                            startAngle = startAngle1,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = Offset(v.x - arcRadius, v.y - arcRadius),
                            size = Size(arcRadius * 2, arcRadius * 2),
                            style = Stroke(width = 2.5f)
                        )

                        val midAngleRad = (startAngle1 + sweep / 2) * (Math.PI / 180.0)
                        val textX = (v.x + (arcRadius + 16f) * cos(midAngleRad)).toFloat()
                        val textY = (v.y + (arcRadius + 16f) * sin(midAngleRad)).toFloat()

                        val angleLabel = angle.label?.let { "$it: " } ?: ""
                        drawContext.canvas.nativeCanvas.drawText(
                            "$angleLabel${deg.toInt()}°",
                            textX,
                            textY,
                            Paint().apply {
                                color = secondaryColor.toArgb()
                                textSize = 24f
                                isFakeBoldText = true
                            }
                        )
                    }
                }

                // 5. Draw Draggable Points
                points.forEachIndexed { idx, pt ->
                    val isDragged = idx == draggedPointIndex

                    if (isDragged) {
                        drawCircle(
                            color = primaryColor.copy(alpha = 0.25f),
                            radius = 24f,
                            center = Offset(pt.x, pt.y)
                        )
                    }

                    // Outer stroke
                    drawCircle(
                        color = Color.White,
                        radius = 9f,
                        center = Offset(pt.x, pt.y)
                    )

                    // Inner dot
                    drawCircle(
                        color = primaryColor,
                        radius = 7f,
                        center = Offset(pt.x, pt.y)
                    )

                    // Point Label
                    drawContext.canvas.nativeCanvas.drawText(
                        pt.label,
                        pt.x + 12f,
                        pt.y - 12f,
                        Paint().apply {
                            color = onSurface.toArgb()
                            textSize = 30f
                            isFakeBoldText = true
                        }
                    )
                }
            }
        }

        // Live measurements summary bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            spec.lines.forEach { line ->
                val p1 = pointMap[line.from]
                val p2 = pointMap[line.to]
                if (p1 != null && p2 != null) {
                    val dist = hypot(p2.x - p1.x, p2.y - p1.y) / 10f
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "${p1.label}${p2.label} = ${String.format("%.1f", dist)} cm",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = onSurface,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            spec.angles.forEach { angle ->
                val v = pointMap[angle.vertex]
                val p1 = pointMap[angle.p1]
                val p2 = pointMap[angle.p2]
                if (v != null && p1 != null && p2 != null) {
                    val deg = DiagramParser.calculateAngle(v, p1, p2).toInt()
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = secondaryColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "∠${v.label} = $deg°",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = secondaryColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TreeCanvas(
    root: DiagramTreeNode,
    primaryColor: Color,
    textColor: Color,
    surfaceColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(Color.Black.copy(alpha = 0.05f))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            // Render root at top center, children spaced horizontally
            val rootPos = Offset(canvasWidth / 2, 45f)
            drawTreeNode(
                node = root,
                center = rootPos,
                availableWidth = canvasWidth,
                level = 0,
                primaryColor = primaryColor,
                textColor = textColor,
                surfaceColor = surfaceColor
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTreeNode(
    node: DiagramTreeNode,
    center: Offset,
    availableWidth: Float,
    level: Int,
    primaryColor: Color,
    textColor: Color,
    surfaceColor: Color
) {
    val nodeWidth = 72f
    val nodeHeight = 36f

    // Draw node card
    drawRoundRect(
        color = primaryColor,
        topLeft = Offset(center.x - nodeWidth / 2, center.y - nodeHeight / 2),
        size = Size(nodeWidth, nodeHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f),
        style = Stroke(width = 2f)
    )

    drawRoundRect(
        color = surfaceColor,
        topLeft = Offset(center.x - nodeWidth / 2 + 1, center.y - nodeHeight / 2 + 1),
        size = Size(nodeWidth - 2, nodeHeight - 2),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(9f, 9f)
    )

    // Node text
    drawContext.canvas.nativeCanvas.drawText(
        node.label,
        center.x - (node.label.length * 6f),
        center.y + 6f,
        Paint().apply {
            color = textColor.toArgb()
            textSize = 22f
            isFakeBoldText = true
        }
    )

    val childrenCount = node.children.size
    if (childrenCount > 0) {
        val childY = center.y + 70f
        val sliceWidth = availableWidth / childrenCount

        node.children.forEachIndexed { i, child ->
            val childX = (center.x - availableWidth / 2) + (sliceWidth * i) + (sliceWidth / 2)
            val childPos = Offset(childX, childY)

            // Draw branch line
            drawLine(
                color = primaryColor.copy(alpha = 0.6f),
                start = Offset(center.x, center.y + nodeHeight / 2),
                end = Offset(childPos.x, childPos.y - nodeHeight / 2),
                strokeWidth = 2f,
                cap = StrokeCap.Round
            )

            drawTreeNode(
                node = child,
                center = childPos,
                availableWidth = sliceWidth,
                level = level + 1,
                primaryColor = primaryColor,
                textColor = textColor,
                surfaceColor = surfaceColor
            )
        }
    }
}
