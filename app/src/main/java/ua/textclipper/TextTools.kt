package ua.textclipper

import java.text.Normalizer
import java.util.Locale

fun tagKey(value: String): String = Normalizer.normalize(value.trim().trimStart('#').trim(), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
fun tagName(value: String): String = Normalizer.normalize(value.trim().trimStart('#').trim(), Normalizer.Form.NFKC)
fun validTag(value: String) = tagKey(value).matches(Regex("[\\p{L}\\p{N}_][\\p{L}\\p{M}\\p{N}_-]{0,79}"))

/** Mask syntax without changing offsets, so content itself is never rewritten. */
fun hashtags(markdown: String): Map<String, String> {
    val masked = markdown.toCharArray()
    fun mask(start: Int, end: Int) { for (i in start until end) if (masked[i] != '\n') masked[i] = ' ' }
    var fenceChar: Char? = null
    var fenceSize = 0
    var offset = 0
    markdown.splitToSequence('\n').forEach { line ->
        val logicalLine = line.replace(Regex("^ {0,3}(?:> ?)+"), "")
            .replace(Regex("^ {0,3}(?:[-+*]|\\d+[.)])\\s+"), "")
        val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").find(logicalLine)
        if (fenceChar != null) {
            mask(offset, offset + line.length)
            if (fence != null && fence.groupValues[1][0] == fenceChar &&
                fence.groupValues[1].length >= fenceSize && fence.groupValues[2].isBlank()) fenceChar = null
        } else if (fence != null) {
            fenceChar = fence.groupValues[1][0]; fenceSize = fence.groupValues[1].length
            mask(offset, offset + line.length)
        } else if (line.startsWith("    ") || line.startsWith('\t')) mask(offset, offset + line.length)
        offset += line.length + 1
    }
    val inlineSource = String(masked)
    var i = 0
    while (i < masked.size) {
        if (masked[i] == '`') {
            val start = i
            while (i < masked.size && masked[i] == '`') i++
            val marker = "`".repeat(i - start)
            var close = inlineSource.indexOf(marker, i)
            while (close >= 0 && ((close > 0 && inlineSource[close - 1] == '`') ||
                    (close + marker.length < inlineSource.length && inlineSource[close + marker.length] == '`'))) {
                close = inlineSource.indexOf(marker, close + marker.length)
            }
            if (close >= 0) { mask(start, close + marker.length); i = close + marker.length }
        } else i++
    }
    Regex("(?i)(?:[a-z][a-z0-9+.-]*://|www\\.)[^\\s<>]+|\\]\\([^\\s)]*\\)|<[^>]+>")
        .findAll(String(masked)).toList().forEach { mask(it.range.first, it.range.last + 1) }
    val result = linkedMapOf<String, String>()
    Regex("(?<![\\p{L}\\p{M}\\p{N}_/#\\\\])#([\\p{L}\\p{N}_][\\p{L}\\p{M}\\p{N}_-]*)")
        .findAll(String(masked)).forEach { match ->
            val name = tagName(match.groupValues[1])
            if (validTag(name)) result.putIfAbsent(tagKey(name), name)
        }
    return result
}

fun fuzzyScore(name: String, query: String): Int? {
    val n = tagKey(name); val q = tagKey(query)
    if (q.isEmpty()) return 0
    if (n == q) return 0
    if (n.startsWith(q)) return 10 + n.length - q.length
    if (n.contains(q)) return 100 + n.indexOf(q)
    val limit = if (q.length < 5) 1 else 2
    if (kotlin.math.abs(n.length - q.length) > limit) return null
    var previous = IntArray(n.length + 1) { it }
    var beforePrevious = previous
    q.forEachIndexed { row, ch ->
        val current = IntArray(n.length + 1); current[0] = row + 1
        n.forEachIndexed { col, other ->
            current[col + 1] = minOf(current[col] + 1, previous[col + 1] + 1, previous[col] + if (ch == other) 0 else 1)
            if (row > 0 && col > 0 && ch == n[col - 1] && q[row - 1] == other)
                current[col + 1] = minOf(current[col + 1], beforePrevious[col - 1] + 1)
        }
        beforePrevious = previous
        previous = current
    }
    return previous.last().takeIf { it <= limit }?.let { 200 + it }
}

/** Only lowercase Unicode word tokens reach MATCH; FTS operators and punctuation are stripped. */
fun ftsQuery(query: String, column: String): String? = Regex("[\\p{L}\\p{M}\\p{N}_]+")
    .findAll(query.lowercase(Locale.ROOT)).map { "$column:${it.value}*" }
    .toList().takeIf { it.isNotEmpty() }?.joinToString(" AND ")

fun suggestedTitle(content: String): String = content.lineSequence().map { it.trim().trimStart('#', '>', '-', '*', ' ') }
    .firstOrNull { it.isNotBlank() }?.take(100).orEmpty()

val clipOrder: Comparator<Clip> = compareByDescending<Clip> { it.rating }.thenByDescending { it.createdAt }.thenByDescending { it.id }
