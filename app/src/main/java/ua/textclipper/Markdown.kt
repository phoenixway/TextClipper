package ua.textclipper

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface MarkdownBlock {
    data class Paragraph(val text: String, val heading: Int = 0, val quote: Boolean = false) : MarkdownBlock
    data class Code(val text: String, val language: String) : MarkdownBlock
}
fun markdownBlocks(text: String): List<MarkdownBlock> {
    val lines = text.split('\n'); val result = mutableListOf<MarkdownBlock>(); var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").find(line)
        if (fence != null) {
            val marker = fence.groupValues[1]; val body = mutableListOf<String>(); i++
            while (i < lines.size && !Regex("^ {0,3}${Regex.escape(marker[0].toString())}{${marker.length},}\\s*$").matches(lines[i])) body.add(lines[i++])
            result.add(MarkdownBlock.Code(body.joinToString("\n"), fence.groupValues[2].trim())); i++
        } else if (line.startsWith("    ") || line.startsWith('\t')) {
            val body = mutableListOf<String>()
            while (i < lines.size && (lines[i].startsWith("    ") || lines[i].startsWith('\t'))) {
                body.add(if (lines[i].startsWith('\t')) lines[i].drop(1) else lines[i].drop(4)); i++
            }
            result.add(MarkdownBlock.Code(body.joinToString("\n"), ""))
        } else {
            val heading = Regex("^ {0,3}(#{1,6})\\s+(.*)$").find(line)
            val quote = Regex("^ {0,3}> ?(.*)$").find(line)
            val bullet = Regex("^\\s*[-+*]\\s+(.*)$").find(line)
            val value = heading?.groupValues?.get(2) ?: quote?.groupValues?.get(1) ?: bullet?.let { "• " + it.groupValues[1] } ?: line
            result.add(MarkdownBlock.Paragraph(value, heading?.groupValues?.get(1)?.length ?: 0, quote != null)); i++
        }
    }
    return result
}
fun markdownInline(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    fun appendRange(value: String, depth: Int = 0) {
        if (depth > 12) { append(value); return }
        var i = 0
        while (i < value.length) {
            if (value[i] == '\\' && i + 1 < value.length) { append(value[i+1]); i += 2; continue }
            if (value[i] == '`') {
                val count = value.drop(i).takeWhile { it == '`' }.length
                val mark = "`".repeat(count); val end = value.indexOf(mark, i + count)
                if (end >= 0) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color.Gray.copy(alpha = .15f))) { append(value.substring(i+count, end)) }
                    i = end + count; continue
                }
            }
            if (value[i] == '[') {
                val endLabel = value.indexOf("](", i + 1)
                val endUrl = if (endLabel >= 0) value.indexOf(')', endLabel + 2) else -1
                if (endUrl >= 0) {
                    val url = value.substring(endLabel+2, endUrl).trim().trim('<', '>')
                    pushStringAnnotation("URL", url)
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { appendRange(value.substring(i+1, endLabel), depth+1) }
                    pop(); i = endUrl+1; continue
                }
            }
            val mark = when {
                value.startsWith("**", i) -> "**"
                value.startsWith("__", i) -> "__"
                value[i] == '*' -> "*"
                value[i] == '_' && (i == 0 || !value[i-1].isLetterOrDigit()) -> "_"
                else -> null
            }
            if (mark != null) {
                val end = value.indexOf(mark, i + mark.length)
                if (end > i + mark.length) {
                    withStyle(if (mark.length == 2) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendRange(value.substring(i+mark.length, end), depth+1)
                    }
                    i = end + mark.length; continue
                }
            }
            append(value[i++])
        }
    }
    appendRange(text)
}

@Composable
fun MarkdownPiece(block: MarkdownBlock) {
    val colors = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    when (block) {
        is MarkdownBlock.Code -> Column(Modifier.fillMaxWidth().background(colors.surfaceContainer).padding(12.dp)) {
            if (block.language.isNotEmpty()) Text(block.language, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Text(block.text, modifier = Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        }
        is MarkdownBlock.Paragraph -> {
            val styled = remember(block.text, colors.primary) { markdownInline(block.text, colors.primary) }
            val style = when (block.heading) {
                1 -> MaterialTheme.typography.headlineMedium
                2 -> MaterialTheme.typography.headlineSmall
                3, 4 -> MaterialTheme.typography.titleLarge
                5, 6 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.bodyLarge
            }.copy(color = if (block.quote) colors.onSurfaceVariant else colors.onSurface)
            ClickableText(styled, Modifier.fillMaxWidth().then(if (block.quote) Modifier.background(colors.surfaceContainer).padding(12.dp) else Modifier), style = style,
                onClick = { pos -> styled.getStringAnnotations("URL", pos, pos).firstOrNull()?.let {
                    if (it.item.startsWith("https://") || it.item.startsWith("http://") || it.item.startsWith("mailto:")) runCatching { uri.openUri(it.item) }
                } })
        }
    }
}
