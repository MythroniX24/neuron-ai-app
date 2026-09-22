package com.neuron.ai.data.tool

import com.neuron.ai.core.agent.Tool
import com.neuron.ai.core.agent.ToolResult
import com.neuron.ai.core.permissions.Capability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Phase 1 foundational tools. Each is small, safe, and declares its required
 * capabilities; new tools can be registered without touching the agent loop.
 */
object SafeTools {

    private val json = Json { ignoreUnknownKeys = true }

    private fun arg(args: String, key: String): String? = runCatching {
        json.parseToJsonElement(args).jsonObject[key]?.jsonPrimitive?.content
    }.getOrNull()

    // ---- 1. Current time ------------------------------------------------------------

    class CurrentTime : Tool {
        override val id = "time.now"
        override val title = "Getting current time"
        override val description =
            "Returns the current local date and time, timezone and UTC offset."
        override val requiredCapabilities = emptySet<Capability>()
        override val parametersSchemaJson = """{"type":"object","properties":{} }"""

        override suspend fun execute(argumentsJson: String): ToolResult {
            val now = LocalDateTime.now()
            val zone = ZoneId.systemDefault()
            val offset = now.atZone(zone).offset
            val formatted = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm:ss"))
            return ToolResult.Success(
                "Local time: $formatted ($zone, UTC$offset) " +
                    "Unix seconds: ${System.currentTimeMillis() / 1000}"
            )
        }
    }

    // ---- 2. Calculator ----------------------------------------------------------------

    class Calculator : Tool {
        override val id = "math.evaluate"
        override val title = "Calculating"
        override val description =
            "Evaluates an arithmetic expression. Supports + - * / % parentheses and java.lang.Math functions."
        override val requiredCapabilities = emptySet<Capability>()
        override val parametersSchemaJson =
            """{"type":"object","properties":{"expression":{"type":"string"}},"required":["expression"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult {
            val expression = arg(argumentsJson, "expression")
                ?: return ToolResult.Failure("Missing required argument: expression")
            return try {
                val value = ArithmeticEvaluator.evaluate(expression)
                // Integral results read better in chat ("20", not "20.0").
                val text = if (value == Math.floor(value) && !value.isInfinite() &&
                    Math.abs(value) < 1e15
                ) {
                    value.toLong().toString()
                } else {
                    value.toString()
                }
                ToolResult.Success("$expression = $text")
            } catch (t: Throwable) {
                ToolResult.Failure("Could not evaluate expression: ${t.message}")
            }
        }
    }

    // ---- 3. Text processing --------------------------------------------------------------

    class TextStats : Tool {
        override val id = "text.stats"
        override val title = "Analyzing text"
        override val description =
            "Counts characters, words, lines and paragraphs of the given text."
        override val requiredCapabilities = emptySet<Capability>()
        override val parametersSchemaJson =
            """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult {
            val text = arg(argumentsJson, "text")
                ?: return ToolResult.Failure("Missing required argument: text")
            val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
            val lines = text.lines().size
            val paragraphs = text.split(Regex("\\n\\s*\\n")).count { it.isNotBlank() }
            return ToolResult.Success(
                "Characters: ${text.length}, Words: $words, Lines: $lines, Paragraphs: $paragraphs"
            )
        }
    }

    // ---- 4. File read (workspace-scoped) -----------------------------------------------

    class FileRead(workspaceRoot: File) : Tool {
        private val workspace: File = workspaceRoot.apply { mkdirs() }

        override val id = "fs.read"
        override val title = "Reading file"
        override val description =
            "Reads a text file from the Neuron-AI workspace by relative path."
        override val requiredCapabilities = setOf(Capability.FILESYSTEM_READ)
        override val parametersSchemaJson =
            """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult {
            val path = arg(argumentsJson, "path")
                ?: return ToolResult.Failure("Missing required argument: path")
            val file = resolve(path)
                ?: return ToolResult.Failure("Invalid path: $path")
            if (!file.exists() || !file.isFile) {
                return ToolResult.Failure("File not found: $path")
            }
            return runCatching {
                val bytes = file.readBytes()
                val capped = bytes.size > 100_000
                val text = String(bytes, 0, minOf(bytes.size, 100_000), Charsets.UTF_8)
                ToolResult.Success(if (capped) text + "\n\n… [truncated]" else text)
            }.getOrElse { ToolResult.Failure("Could not read file: ${it.message}") }
        }

        internal fun resolve(path: String): File? {
            val root = workspace.canonicalFile
            val candidate = File(root, path).canonicalFile
            return if (candidate.path.startsWith(root.path)) candidate else null
        }
    }

    // ---- 5. File search (workspace-scoped) ------------------------------------------------

    class FileSearch(workspaceRoot: File) : Tool {
        private val workspace: File = workspaceRoot.apply { mkdirs() }

        override val id = "fs.search"
        override val title = "Searching files"
        override val description =
            "Searches file names and text contents inside the Neuron-AI workspace."
        override val requiredCapabilities = setOf(Capability.FILESYSTEM_READ)
        override val parametersSchemaJson =
            """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""

        override suspend fun execute(argumentsJson: String): ToolResult {
            val query = arg(argumentsJson, "query")
                ?: return ToolResult.Failure("Missing required argument: query")
            if (query.isBlank()) return ToolResult.Failure("Query must not be empty.")

            val matches = mutableListOf<String>()
            val root = workspace.canonicalFile
            root.walkTopDown()
                .filter { it.isFile }
                .take(2_000)
                .forEach { file ->
                    val relative = file.relativeToOrNull(root)?.path ?: return@forEach
                    if (file.name.contains(query, ignoreCase = true)) {
                        matches += relative
                        return@forEach
                    }
                    if (file.length() < 512_000) {
                        runCatching {
                            if (file.readText(Charsets.UTF_8).contains(query, ignoreCase = true)) {
                                matches += "$relative (content match)"
                            }
                        }
                    }
                }

            return if (matches.isEmpty()) {
                ToolResult.Success("No matches for \"$query\" in the workspace.")
            } else {
                ToolResult.Success(
                    matches.take(30).joinToString("\n") { "• $it" } +
                        if (matches.size > 30) "\n… and ${matches.size - 30} more" else ""
                )
            }
        }
    }

    // ---- Arithmetic evaluator (no javax.script on Android) ---------------------------------

    object ArithmeticEvaluator {

        fun evaluate(expression: String): Double {
            val cleaned = expression
                .replace(Regex("[^0-9+\\-*/%(). eE]"), "")
                .replace(Regex("\\s+"), "")
            if (cleaned.isEmpty()) throw IllegalArgumentException("empty expression")
            return Parser(cleaned).parse()
        }

        /** Recursive-descent parser: expr → term → factor → power → unary → primary. */
        private class Parser(private val input: String) {
            private var pos = 0

            fun parse(): Double {
                val value = expression()
                require(pos == input.length) { "unexpected '${input.substring(pos)}'" }
                return value
            }

            private fun expression(): Double {
                var value = term()
                while (pos < input.length && (input[pos] == '+' || input[pos] == '-')) {
                    val op = input[pos++]
                    val rhs = term()
                    value = if (op == '+') value + rhs else value - rhs
                }
                return value
            }

            private fun term(): Double {
                var value = power()
                while (pos < input.length && (input[pos] == '*' || input[pos] == '/' || input[pos] == '%')) {
                    val op = input[pos++]
                    val rhs = power()
                    value = when (op) {
                        '*' -> value * rhs
                        '/' -> value / rhs
                        else -> value % rhs
                    }
                }
                return value
            }

            private fun power(): Double {
                val base = unary()
                if (pos < input.length - 1 && input[pos] == '*' && input[pos + 1] == '*') {
                    pos += 2
                    return Math.pow(base, power())
                }
                return base
            }

            private fun unary(): Double = when {
                pos < input.length && input[pos] == '-' -> { pos++; -unary() }
                pos < input.length && input[pos] == '+' -> { pos++; unary() }
                else -> primary()
            }

            private fun primary(): Double = when {
                pos < input.length && input[pos] == '(' -> {
                    pos++
                    val value = expression()
                    require(pos < input.length && input[pos] == ')') { "missing )" }
                    pos++
                    value
                }
                else -> {
                    val start = pos
                    while (pos < input.length && (input[pos].isDigit() || input[pos] == '.' ||
                            input[pos] == 'e' || input[pos] == 'E' ||
                            ((input[pos] == '+' || input[pos] == '-') && pos > start &&
                                (input[pos - 1] == 'e' || input[pos - 1] == 'E')))
                    ) pos++
                    require(start != pos) { "number expected at $start" }
                    input.substring(start, pos).toDouble()
                }
            }
        }
    }
}
