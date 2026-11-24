package com.neuron.ai.ui.chat.diagram

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class DiagramPoint(
    val id: String,
    var label: String,
    var x: Float,
    var y: Float,
    val color: String? = null
)

data class DiagramLine(
    val from: String,
    val to: String,
    var label: String? = null,
    var length: String? = null,
    val isDashed: Boolean = false
)

data class DiagramAngle(
    val vertex: String,
    val p1: String,
    val p2: String,
    var label: String? = null,
    var degrees: Float? = null,
    val isRightAngle: Boolean = false
)

data class DiagramCircle(
    val center: String,
    var radius: Float = 90f,
    var radiusLabel: String? = null,
    val showChord: Boolean = false,
    val chordP1: String? = null,
    val chordP2: String? = null
)

data class DiagramTreeNode(
    val id: String,
    var label: String,
    var value: String? = null,
    val children: MutableList<DiagramTreeNode> = mutableListOf()
)

data class DiagramSpec(
    val type: String, // "triangle", "circle", "geometry", "tree", "coordinate_plane"
    var title: String,
    val description: String? = null,
    val points: MutableList<DiagramPoint> = mutableListOf(),
    val lines: MutableList<DiagramLine> = mutableListOf(),
    val angles: MutableList<DiagramAngle> = mutableListOf(),
    val circle: DiagramCircle? = null,
    val treeRoot: DiagramTreeNode? = null
)

object DiagramParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(typeHint: String, rawContent: String): DiagramSpec? = runCatching {
        val trimmed = rawContent.trim()
        val root = json.parseToJsonElement(trimmed).jsonObject

        val type = root["type"]?.jsonPrimitive?.contentOrNull
            ?: root["shapeType"]?.jsonPrimitive?.contentOrNull
            ?: typeHint.lowercase()

        val title = root["title"]?.jsonPrimitive?.contentOrNull ?: when (type) {
            "triangle" -> "Interactive Triangle"
            "circle" -> "Interactive Circle"
            "tree" -> "Hierarchical Tree"
            else -> "Mathematical Diagram"
        }

        val description = root["description"]?.jsonPrimitive?.contentOrNull

        // Parse points
        val points = mutableListOf<DiagramPoint>()
        root["points"]?.jsonArray?.forEach { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "p${points.size}"
            val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: id
            val x = obj["x"]?.jsonPrimitive?.floatOrNull ?: 100f
            val y = obj["y"]?.jsonPrimitive?.floatOrNull ?: 100f
            val color = obj["color"]?.jsonPrimitive?.contentOrNull
            points.add(DiagramPoint(id, label, x, y, color))
        }

        // Default points for triangle if not provided
        if ((type == "triangle" || type == "geometry") && points.isEmpty()) {
            points.add(DiagramPoint("A", "A", 180f, 60f))
            points.add(DiagramPoint("B", "B", 70f, 260f))
            points.add(DiagramPoint("C", "C", 290f, 260f))
        } else if (type == "circle" && points.isEmpty()) {
            points.add(DiagramPoint("O", "O", 180f, 160f))
            points.add(DiagramPoint("A", "A", 270f, 160f))
            points.add(DiagramPoint("B", "B", 180f, 70f))
        }

        // Parse lines
        val lines = mutableListOf<DiagramLine>()
        root["lines"]?.jsonArray?.forEach { el ->
            val obj = el.jsonObject
            val from = obj["from"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val to = obj["to"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val label = obj["label"]?.jsonPrimitive?.contentOrNull
            val length = obj["length"]?.jsonPrimitive?.contentOrNull
            val isDashed = obj["isDashed"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            lines.add(DiagramLine(from, to, label, length, isDashed))
        }

        // Default lines for triangle
        if (type == "triangle" && lines.isEmpty() && points.size >= 3) {
            lines.add(DiagramLine(points[0].id, points[1].id, label = "c"))
            lines.add(DiagramLine(points[1].id, points[2].id, label = "a"))
            lines.add(DiagramLine(points[2].id, points[0].id, label = "b"))
        }

        // Parse angles
        val angles = mutableListOf<DiagramAngle>()
        root["angles"]?.jsonArray?.forEach { el ->
            val obj = el.jsonObject
            val vertex = obj["vertex"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val p1 = obj["p1"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val p2 = obj["p2"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val label = obj["label"]?.jsonPrimitive?.contentOrNull
            val degrees = obj["degrees"]?.jsonPrimitive?.floatOrNull
            val isRight = obj["isRightAngle"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            angles.add(DiagramAngle(vertex, p1, p2, label, degrees, isRight))
        }

        // Default angles for triangle
        if (type == "triangle" && angles.isEmpty() && points.size >= 3) {
            angles.add(DiagramAngle(points[0].id, points[1].id, points[2].id, label = "A"))
            angles.add(DiagramAngle(points[1].id, points[0].id, points[2].id, label = "B"))
            angles.add(DiagramAngle(points[2].id, points[0].id, points[1].id, label = "C"))
        }

        // Parse circle
        val circleObj = root["circle"]?.jsonObject
        val circle = if (circleObj != null || type == "circle") {
            val center = circleObj?.get("center")?.jsonPrimitive?.contentOrNull ?: points.firstOrNull()?.id ?: "O"
            val radius = circleObj?.get("radius")?.jsonPrimitive?.floatOrNull ?: 90f
            val radiusLabel = circleObj?.get("radiusLabel")?.jsonPrimitive?.contentOrNull ?: "r"
            val showChord = circleObj?.get("showChord")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val chordP1 = circleObj?.get("chordP1")?.jsonPrimitive?.contentOrNull
            val chordP2 = circleObj?.get("chordP2")?.jsonPrimitive?.contentOrNull
            DiagramCircle(center, radius, radiusLabel, showChord, chordP1, chordP2)
        } else null

        // Parse tree
        val treeRootObj = root["treeRoot"]?.jsonObject ?: root["root"]?.jsonObject
        val treeRoot = if (treeRootObj != null || type == "tree") {
            parseTreeNode(treeRootObj ?: root)
        } else null

        DiagramSpec(
            type = type,
            title = title,
            description = description,
            points = points,
            lines = lines,
            angles = angles,
            circle = circle,
            treeRoot = treeRoot
        )
    }.getOrNull()

    private fun parseTreeNode(obj: JsonObject): DiagramTreeNode {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "node_${System.nanoTime()}"
        val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: obj["name"]?.jsonPrimitive?.contentOrNull ?: "Root"
        val value = obj["value"]?.jsonPrimitive?.contentOrNull
        val children = mutableListOf<DiagramTreeNode>()
        obj["children"]?.jsonArray?.forEach { el ->
            if (el is JsonObject) {
                children.add(parseTreeNode(el))
            }
        }
        return DiagramTreeNode(id, label, value, children)
    }

    /** Calculates angle in degrees at vertex between p1 and p2 */
    fun calculateAngle(vertex: DiagramPoint, p1: DiagramPoint, p2: DiagramPoint): Float {
        val v1x = p1.x - vertex.x
        val v1y = p1.y - vertex.y
        val v2x = p2.x - vertex.x
        val v2y = p2.y - vertex.y

        val dot = v1x * v2x + v1y * v2y
        val mag1 = sqrt(v1x * v1x + v1y * v1y)
        val mag2 = sqrt(v2x * v2x + v2y * v2y)
        if (mag1 == 0f || mag2 == 0f) return 0f

        val cosTheta = (dot / (mag1 * mag2)).coerceIn(-1f, 1f)
        return (acos(cosTheta) * (180.0 / Math.PI)).toFloat()
    }

    /** Calculates distance between two points */
    fun calculateDistance(p1: DiagramPoint, p2: DiagramPoint): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return sqrt(dx * dx + dy * dy)
    }
}
