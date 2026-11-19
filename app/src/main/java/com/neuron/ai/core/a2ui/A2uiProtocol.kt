package com.neuron.ai.core.a2ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A2UI core — a faithful port of the Agent-to-UI protocol
 * (https://github.com/a2ui-project/a2ui, Apache-2.0) to plain Kotlin.
 *
 * The upstream project ships a core library per platform (web, Dart, Swift,
 * Python) plus a framework adapter and a component catalog. This file is the
 * core: it parses the streamed agent messages, keeps a surface's component map
 * and data model, resolves `Dynamic*` bindings (literals, `@path` data
 * bindings and `@call` functions) and expands `ChildList` templates. Nothing
 * here touches Android, so it is unit-testable; the Compose adapter lives in
 * `ui/a2ui/`.
 *
 * Wire compatibility (deliberate, so real-world model output renders):
 *  - v1.0: `createSurface`, `updateComponents`, `updateDataModel`,
 *    `deleteSurface`, flat components (`"component": "Text"`).
 *  - v0.8/0.9: `beginRendering`, `surfaceUpdate`, `dataModelUpdate`, nested
 *    components (`"component": {"Text": {...}}`) and `literal*`/`path` values.
 */

/** One node of a surface: its id, its component type and its raw props. */
data class A2uiComponent(
    val id: String,
    val type: String,
    val props: JsonObject
)

/** A surface: the component map plus the data model its bindings read from. */
data class A2uiSurface(
    val id: String,
    val rootId: String = "root",
    val components: Map<String, A2uiComponent> = emptyMap(),
    val dataModel: JsonElement = JsonObject(emptyMap())
) {
    fun component(id: String): A2uiComponent? = components[id]

    /** The component to mount: `root` by contract, or the top-level card/container. */
    val root: A2uiComponent?
        get() {
            components[rootId]?.let { return it }
            components["root"]?.let { return it }
            // Find component not referenced as child by other components
            val childIds = components.values.flatMap { comp ->
                val list = mutableListOf<String>()
                (comp.props["child"] as? JsonPrimitive)?.content?.let { list.add(it) }
                (comp.props["children"] as? JsonArray)?.forEach { elem ->
                    (elem as? JsonPrimitive)?.content?.let { list.add(it) }
                }
                list
            }.toSet()
            val cards = components.values.filter { it.type == "Card" && it.id !in childIds }
            if (cards.isNotEmpty()) return cards.first()
            val topLevel = components.values.filter { it.id !in childIds }
            if (topLevel.isNotEmpty()) return topLevel.first()
            return components.values.firstOrNull()
        }
}

object A2uiProtocol {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /** Parses a fenced A2UI block and folds its messages into surfaces. */
    fun render(source: String): List<A2uiSurface> = build(parseMessages(source))

    /**
     * Messages arrive either as one JSON document (a single message, an array
     * of messages, or `{"messages":[…]}`) or as newline-delimited JSON, where
     * one message may still span several lines.
     */
    fun parseMessages(source: String): List<JsonObject> {
        val text = source.trim()
            .replaceFirst(Regex("""^```[a-zA-Z0-9_-]*\s*"""), "")
            .replaceFirst(Regex("""\s*```$"""), "")
            .trim()
        if (text.isEmpty()) return emptyList()

        val whole = runCatching { json.parseToJsonElement(text) }.getOrNull()
        if (whole != null) {
            when (whole) {
                is JsonArray -> {
                    val isComponentArray = whole.isNotEmpty() && whole.all {
                        it is JsonObject && (it.containsKey("component") || it.containsKey("id") || it.containsKey("type"))
                    }
                    if (isComponentArray) {
                        return listOf(
                            JsonObject(
                                mapOf(
                                    "version" to JsonPrimitive("1.0"),
                                    "createSurface" to JsonObject(
                                        mapOf(
                                            "surfaceId" to JsonPrimitive("s1"),
                                            "components" to whole
                                        )
                                    )
                                )
                            )
                        )
                    }
                    return whole.mapNotNull { it as? JsonObject }
                }
                is JsonObject -> {
                    val nested = whole["messages"]
                    if (nested is JsonArray) return nested.mapNotNull { it as? JsonObject }

                    if (whole.containsKey("createSurface") && whole.containsKey("updateComponents")) {
                        return listOf(
                            JsonObject(mapOf("createSurface" to whole["createSurface"]!!)),
                            JsonObject(mapOf("updateComponents" to whole["updateComponents"]!!))
                        )
                    }

                    if (whole.containsKey("component") || whole.containsKey("components") || whole.containsKey("type")) {
                        val componentsElement = whole["components"] ?: JsonArray(listOf(whole))
                        return listOf(
                            JsonObject(
                                mapOf(
                                    "version" to JsonPrimitive("1.0"),
                                    "createSurface" to JsonObject(
                                        mapOf(
                                            "surfaceId" to (whole.str("surfaceId")?.let { JsonPrimitive(it) } ?: JsonPrimitive("s1")),
                                            "components" to componentsElement
                                        )
                                    )
                                )
                            )
                        )
                    }

                    return listOf(whole)
                }
                else -> Unit
            }
        }

        return splitObjects(text).mapNotNull { chunk ->
            runCatching { json.parseToJsonElement(chunk) as? JsonObject }.getOrNull()
        }
    }

    /** Splits concatenated JSON objects without caring about line breaks. */
    internal fun splitObjects(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escaped = false
        var start = -1
        text.forEachIndexed { index, ch ->
            when {
                escaped -> escaped = false
                inString && ch == '\\' -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                ch == '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        parts += text.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }
        return parts
    }

    /** Applies messages in order; later messages update what earlier ones built. */
    fun build(messages: List<JsonObject>): List<A2uiSurface> {
        val surfaces = LinkedHashMap<String, A2uiSurface>()

        fun surfaceOf(id: String): A2uiSurface =
            surfaces[id] ?: A2uiSurface(id = id)

        messages.forEach { message ->
            message.obj("createSurface")?.let { create ->
                val id = create.str("surfaceId") ?: return@let
                val components = readComponents(create["components"])
                surfaces[id] = A2uiSurface(
                    id = id,
                    rootId = "root",
                    components = components,
                    dataModel = create["dataModel"] ?: JsonObject(emptyMap())
                )
            }

            message.obj("updateComponents")?.let { update ->
                val id = update.str("surfaceId") ?: "surface"
                val current = surfaceOf(id)
                val merged = current.components.toMutableMap()
                readComponents(update["components"]).forEach { (key, value) -> merged[key] = value }
                surfaces[id] = current.copy(components = merged)
            }

            message.obj("updateDataModel")?.let { update ->
                val id = update.str("surfaceId") ?: "surface"
                val current = surfaceOf(id)
                val path = update.str("path") ?: "/"
                val value = update["value"] ?: JsonNull
                surfaces[id] = current.copy(
                    dataModel = A2uiValue.setPointer(current.dataModel, path, value)
                )
            }

            message.obj("deleteSurface")?.let { delete ->
                delete.str("surfaceId")?.let { surfaces.remove(it) }
            }

            // ---- v0.8 / v0.9 wire format -------------------------------------
            message.obj("beginRendering")?.let { begin ->
                val id = begin.str("surfaceId") ?: return@let
                val existing = surfaceOf(id)
                surfaces[id] = existing.copy(rootId = begin.str("root") ?: existing.rootId)
            }

            message.obj("surfaceUpdate")?.let { update ->
                val id = update.str("surfaceId") ?: "surface"
                val current = surfaceOf(id)
                val merged = current.components.toMutableMap()
                readComponents(update["components"]).forEach { (key, value) -> merged[key] = value }
                surfaces[id] = current.copy(components = merged)
            }

            message.obj("dataModelUpdate")?.let { update ->
                val id = update.str("surfaceId") ?: "surface"
                val current = surfaceOf(id)
                val path = update.str("path") ?: "/"
                val value: JsonElement = update["value"]
                    ?: update["contents"]?.let { A2uiValue.legacyContents(it) }
                    ?: JsonNull
                surfaces[id] = current.copy(
                    dataModel = A2uiValue.setPointer(current.dataModel, path, value)
                )
            }

            // A bare component or component list (models sometimes drop the envelope).
            val loose = message["components"] ?: if (message.containsKey("component")) message else null
            if (loose != null) {
                val id = message.str("surfaceId") ?: "surface"
                val current = surfaceOf(id)
                val merged = current.components.toMutableMap()
                readComponents(loose).forEach { (key, value) -> merged[key] = value }
                surfaces[id] = current.copy(
                    rootId = if (current.components.isEmpty() && merged.containsKey("root")) "root" else current.rootId,
                    components = merged
                )
            }
        }

        return surfaces.values.filter { it.components.isNotEmpty() }
    }

    /**
     * Reads a component list in either wire shape. v1.0 is flat
     * (`{"id":"x","component":"Text","text":"hi"}`), v0.8 nests the props under
     * the component name (`{"id":"x","component":{"Text":{"text":{…}}}}`).
     */
    private fun readComponents(element: JsonElement?): Map<String, A2uiComponent> {
        val list: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> {
                if (element.containsKey("components") && element["components"] is JsonArray) {
                    element["components"] as JsonArray
                } else {
                    JsonArray(listOf(element))
                }
            }
            else -> JsonArray(emptyList())
        }
        val out = LinkedHashMap<String, A2uiComponent>()
        list.forEachIndexed { index, entry ->
            val obj = entry as? JsonObject ?: return@forEachIndexed
            val id = obj.str("id") ?: if (out.isEmpty()) "root" else "c_${index + 1}"
            val declaration = obj["component"]
            val type: String?
            val props: JsonObject
            when {
                declaration is JsonPrimitive -> {
                    type = declaration.str()
                    props = JsonObject(obj.filterKeys { it != "id" && it != "component" })
                }
                declaration is JsonObject -> {
                    type = declaration.keys.firstOrNull()
                    val nested = (type?.let { declaration[it] } as? JsonObject) ?: JsonObject(emptyMap())
                    props = JsonObject(
                        obj.filterKeys { it != "id" && it != "component" } + nested
                    )
                }
                else -> {
                    // Tolerate "type": "Text" and objects that only carry props.
                    type = obj.str("type") ?: if (obj.containsKey("title") || obj.containsKey("text")) "Card" else obj.keys.firstOrNull { it != "id" }
                    props = JsonObject(obj.filterKeys { it != "id" && it != "type" })
                }
            }
            if (type == null) return@forEachIndexed
            out[id] = A2uiComponent(id = id, type = type, props = props)
        }
        return out
    }
}

/** Value resolution shared by the parser and the renderer. */
object A2uiValue {

    /**
     * Resolves a `Dynamic*` value: a literal, `{"@path": "/x"}` data binding
     * (or the v0.8 `{"path": "/x"}` / `literalString` shapes) or a `@call`
     * function. [scope] is the current list item for template children; the
     * surface data model is the fallback for absolute paths.
     */
    fun resolve(
        element: JsonElement?,
        scope: JsonElement,
        root: JsonElement
    ): JsonElement? {
        val value = element ?: return null
        return when (value) {
            is JsonNull -> null
            is JsonPrimitive -> value
            is JsonArray -> JsonArray(value.map { resolve(it, scope, root) ?: JsonNull })
            is JsonObject -> when {
                value.containsKey("@path") -> {
                    val path = value.str("@path").orEmpty()
                    pointer(scope, path) ?: pointer(root, path)
                }
                value.containsKey("@call") ->
                    A2uiFunctions.evaluate(value.str("@call").orEmpty(), value["args"], scope, root)
                value.containsKey("path") -> {
                    val path = value.str("path").orEmpty()
                    pointer(scope, path) ?: pointer(root, path)
                }
                value.containsKey("call") ->
                    A2uiFunctions.evaluate(
                        value.str("call").orEmpty(),
                        value["args"] ?: value["argsJson"],
                        scope,
                        root
                    )
                value.containsKey("literalString") -> value["literalString"]
                value.containsKey("literalNumber") -> value["literalNumber"]
                value.containsKey("literalBoolean") -> value["literalBoolean"]
                value.containsKey("literalArray") -> JsonArray(
                    (value["literalArray"] as? JsonArray)
                        ?.map { resolve(it, scope, root) ?: JsonNull }
                        ?: emptyList()
                )
                else -> value
            }
        }
    }

    fun asString(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull ?: element.toString()
        else -> null
    }

    fun asNumber(element: JsonElement?): Double? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.toDoubleOrNull()
        else -> null
    }

    fun asBoolean(element: JsonElement?): Boolean? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.toBooleanStrictOrNull()
            ?: (element.contentOrNull == "1")
        // A validation result object coerces to its `valid` flag.
        is JsonObject -> asBoolean(element["valid"])
        else -> null
    }

    /** JSON Pointer (`/a/b/0`) read. `""` and `"/"` mean the whole document. */
    fun pointer(root: JsonElement, path: String): JsonElement? {
        if (path.isEmpty() || path == "/") return root
        var current: JsonElement = root
        for (part in path.trim().removePrefix("/").split('/')) {
            if (part.isEmpty()) continue
            val token = part.replace("~1", "/").replace("~0", "~")
            current = when (current) {
                is JsonObject -> current[token] ?: return null
                is JsonArray -> token.toIntOrNull()?.let { current.getOrNull(it) } ?: return null
                else -> return null
            }
        }
        return current
    }

    /** JSON Pointer write; a null value removes the key. */
    fun setPointer(root: JsonElement, path: String, value: JsonElement): JsonElement {
        if (path.isEmpty() || path == "/") return value
        if (root !is JsonObject) return root
        val parts = path.trim().removePrefix("/").split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return value
        val head = parts.first().replace("~1", "/").replace("~0", "~")
        val rest = parts.drop(1)
        return if (rest.isEmpty()) {
            JsonObject(
                buildMap {
                    putAll(root)
                    if (value is JsonNull) remove(head) else put(head, value)
                }
            )
        } else {
            val child = root[head] ?: JsonObject(emptyMap())
            val updated = setPointer(child, "/" + rest.joinToString("/"), value)
            JsonObject(buildMap { putAll(root); put(head, updated) })
        }
    }

    /** v0.8 `dataModelUpdate.contents` -> a JSON object. */
    fun legacyContents(element: JsonElement): JsonElement {
        val list = element as? JsonArray ?: return element
        return JsonObject(
            buildMap {
                list.forEach { entry ->
                    val obj = entry as? JsonObject ?: return@forEach
                    val key = obj.str("key") ?: return@forEach
                    val value = obj["valueString"]
                        ?: obj["valueNumber"]
                        ?: obj["valueBoolean"]
                        ?: obj["valueMap"]?.let { legacyContents(it) }
                        ?: obj["value"]
                        ?: JsonNull
                    put(key, value)
                }
            }
        )
    }
}

/** The catalog functions the basic catalog defines for checks and formatting. */
object A2uiFunctions {

    fun evaluate(
        name: String,
        args: JsonElement?,
        scope: JsonElement,
        root: JsonElement
    ): JsonElement? {
        val obj = args as? JsonObject
        fun arg(key: String): JsonElement? = A2uiValue.resolve(obj?.get(key), scope, root)
        fun text(key: String): String? = A2uiValue.asString(arg(key))

        return when (name.removePrefix("@")) {
            "required" -> valid(arg("value")?.let { A2uiValue.asString(it)?.isNotBlank() } ?: false)
            "email" -> valid(
                text("value")?.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) == true
            )
            "numeric" -> valid(text("value")?.toDoubleOrNull() != null)
            "length" -> {
                val length = text("value").orEmpty().length
                val min = A2uiValue.asNumber(arg("min"))?.toInt() ?: 0
                val max = A2uiValue.asNumber(arg("max"))?.toInt() ?: Int.MAX_VALUE
                valid(length in min..max)
            }
            "regex" -> runCatching {
                valid(
                    Regex(text("pattern").orEmpty())
                        .containsMatchIn(text("value").orEmpty())
                )
            }.getOrNull()
            "not" -> valid(!(A2uiValue.asBoolean(arg("value")) ?: false))
            "and" -> {
                val values = (arg("values") as? JsonArray)?.map { A2uiValue.asBoolean(it) == true }
                    ?: emptyList()
                valid(values.isNotEmpty() && values.all { it })
            }
            "or" -> {
                val values = (arg("values") as? JsonArray)?.map { A2uiValue.asBoolean(it) == true }
                    ?: emptyList()
                valid(values.any { it })
            }
            "concat" -> JsonPrimitive(
                (arg("values") as? JsonArray)
                    ?.joinToString("") { A2uiValue.asString(it).orEmpty() }
                    .orEmpty()
            )
            else -> null
        }
    }

    private fun valid(value: Boolean): JsonElement =
        JsonObject(mapOf("valid" to JsonPrimitive(value)))
}

/** Convenience accessors over the resolved surface state. */
class A2uiResolver(val surface: A2uiSurface) {

    fun resolve(scope: JsonElement, element: JsonElement?): JsonElement? =
        A2uiValue.resolve(element, scope, surface.dataModel)

    fun string(component: A2uiComponent, key: String, scope: JsonElement): String? =
        A2uiValue.asString(resolve(scope, component.props[key]))

    fun number(component: A2uiComponent, key: String, scope: JsonElement): Double? =
        A2uiValue.asNumber(resolve(scope, component.props[key]))

    fun boolean(component: A2uiComponent, key: String, scope: JsonElement): Boolean? =
        A2uiValue.asBoolean(resolve(scope, component.props[key]))

    fun strings(component: A2uiComponent, key: String, scope: JsonElement): List<String> {
        val value = resolve(scope, component.props[key])
        return when (value) {
            is JsonArray -> value.mapNotNull { A2uiValue.asString(it) }
            is JsonPrimitive -> listOfNotNull(A2uiValue.asString(value))
            else -> emptyList()
        }
    }

    fun objectValue(component: A2uiComponent, key: String): JsonObject? =
        component.props[key] as? JsonObject

    /**
     * Children of a container: a single `child`, a static id array, or a
     * template that repeats one component over a data-model list. A template
     * child is rendered with its list item as the binding scope.
     */
    fun children(component: A2uiComponent, scope: JsonElement): List<A2uiChild> {
        component.props.str("child")?.let { return listOf(A2uiChild(it, scope)) }

        when (val list = component.props["children"]) {
            is JsonArray -> return list.mapNotNull { entry ->
                when (entry) {
                    is JsonPrimitive -> entry.contentOrNull?.let { A2uiChild(it, scope) }
                    is JsonObject -> entry.str("id")?.let { A2uiChild(it, scope) }
                    else -> null
                }
            }
            is JsonObject -> {
                val template = list.str("componentId") ?: return emptyList()
                val path = list.str("path") ?: list.str("dataBinding") ?: return emptyList()
                val items = (A2uiValue.pointer(scope, path)
                    ?: A2uiValue.pointer(surface.dataModel, path)) as? JsonArray
                    ?: return emptyList()
                return items.map { A2uiChild(template, it) }
            }
            else -> Unit
        }

        // v0.8 "children": [{"id": "x"}]
        return emptyList()
    }
}

data class A2uiChild(val id: String, val scope: JsonElement)

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonPrimitive.str(): String? = contentOrNull
