package com.neuron.ai.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Lightweight, range-based syntax highlighting for chat code blocks.
 * Spans are applied over ranges of the appended code (no re-appending), and
 * overlapping matches are resolved by priority: comments > strings >
 * numbers > keywords > annotations. Unknown languages render plain.
 */
object SyntaxHighlighter {

    private data class Palette(
        val keyword: Color,
        val string: Color,
        val comment: Color,
        val number: Color,
        val annotation: Color
    )

    private val palette = Palette(
        keyword = Color(0xFF7C3AED),
        string = Color(0xFF0F766E),
        comment = Color(0xFF94A3B8),
        number = Color(0xFFB45309),
        annotation = Color(0xFFB45309)
    )

    private val keywordSets: Map<String, Set<String>> = mapOf(
        "kotlin" to setOf(
            "package", "import", "class", "object", "interface", "fun", "val", "var",
            "if", "else", "when", "for", "while", "return", "is", "in", "as", "null",
            "true", "false", "this", "super", "private", "public", "internal", "protected",
            "suspend", "data", "sealed", "enum", "override", "open", "abstract", "companion",
            "lateinit", "const", "try", "catch", "finally", "throw", "do", "break", "continue"
        ),
        "java" to setOf(
            "package", "import", "class", "interface", "enum", "public", "private", "protected",
            "static", "final", "void", "int", "long", "double", "float", "boolean", "char",
            "if", "else", "for", "while", "switch", "case", "break", "continue", "return",
            "new", "this", "super", "extends", "implements", "throws", "try", "catch", "finally",
            "null", "true", "false", "abstract", "synchronized", "volatile"
        ),
        "python" to setOf(
            "def", "class", "import", "from", "as", "if", "elif", "else", "for", "while",
            "return", "yield", "in", "is", "not", "and", "or", "None", "True", "False",
            "try", "except", "finally", "raise", "with", "lambda", "pass", "break",
            "continue", "global", "async", "await"
        ),
        "javascript" to setOf(
            "const", "let", "var", "function", "class", "extends", "import", "export",
            "from", "default", "if", "else", "for", "while", "switch", "case", "break",
            "continue", "return", "try", "catch", "finally", "throw", "new", "this",
            "null", "undefined", "true", "false", "async", "await", "typeof", "instanceof"
        ),
        "go" to setOf(
            "package", "import", "func", "type", "struct", "interface", "map", "chan",
            "go", "defer", "if", "else", "for", "range", "switch", "case", "default",
            "return", "var", "const", "nil", "true", "false"
        ),
        "rust" to setOf(
            "fn", "let", "mut", "struct", "enum", "trait", "impl", "pub", "use", "mod",
            "match", "if", "else", "loop", "while", "for", "in", "return", "self",
            "Some", "None", "Ok", "Err", "true", "false", "async", "await", "move"
        )
    )

    private val aliases = mapOf(
        "kt" to "kotlin", "kts" to "kotlin",
        "js" to "javascript", "ts" to "javascript", "tsx" to "javascript", "jsx" to "javascript",
        "py" to "python", "golang" to "go", "rs" to "rust"
    )

    fun highlight(code: String, language: String?): AnnotatedString {
        val key = aliases[language?.lowercase()] ?: language?.lowercase()
        val keywords = keywordSets[key] ?: emptySet()

        return buildAnnotatedString {
            append(code)

            // Priority order: comments first win their ranges.
            val claimed = mutableListOf<IntRange>()

            fun claim(range: IntRange): Boolean {
                if (claimed.any { it overlaps range }) return false
                claimed += range
                return true
            }

            fun style(start: Int, end: Int, style: SpanStyle) = addStyle(style, start, end)

            // Comments.
            val commentStyle = SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic)
            if (key == "python") {
                allMatches(code, Regex("""#[^\n]*""")) { style(it.first, it.last + 1, commentStyle) }
            } else {
                allMatches(code, Regex("""//[^\n]*""")) { style(it.first, it.last + 1, commentStyle) }
                allMatches(code, Regex("""/\*[\s\S]*?\*/""")) { style(it.first, it.last + 1, commentStyle) }
            }

            // Strings (multi-line triple quotes, then single-line).
            val stringStyle = SpanStyle(color = palette.string)
            allMatches(code, Regex("\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''")) { range ->
                if (claim(range)) style(range.first, range.last + 1, stringStyle)
            }
            allMatches(code, Regex("\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'")) { range ->
                if (claim(range)) style(range.first, range.last + 1, stringStyle)
            }

            // Numbers.
            val numberStyle = SpanStyle(color = palette.number)
            allMatches(code, Regex("""\b\d+(?:\.\d+)?[fLdD]?\b""")) { range ->
                if (claim(range)) style(range.first, range.last + 1, numberStyle)
            }

            // Keywords.
            if (keywords.isNotEmpty()) {
                val keywordStyle = SpanStyle(color = palette.keyword, fontWeight = FontWeight.SemiBold)
                allMatches(code, Regex("""\b(?:${keywords.joinToString("|")})\b""")) { range ->
                    if (claim(range)) style(range.first, range.last + 1, keywordStyle)
                }
            }

            // Annotations.
            if (key != "python") {
                val annotationStyle = SpanStyle(color = palette.annotation)
                allMatches(code, Regex("""@\w+""")) { range ->
                    if (claim(range)) style(range.first, range.last + 1, annotationStyle)
                }
            }
        }
    }

    private inline fun allMatches(code: String, pattern: Regex, onMatch: (IntRange) -> Unit) {
        runCatching {
            pattern.findAll(code).forEach { onMatch(it.range) }
        }
    }

    private infix fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && other.first <= last
}
