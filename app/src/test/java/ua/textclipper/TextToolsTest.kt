package ua.textclipper

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class TextToolsTest {
    @Test fun unicodeAndDeduplication() {
        assertEquals(setOf("android", "vet", "ветеринарія", "штучний_інтелект", "machine-learning"),
            hashtags("#android #vet #VET #ветеринарія #штучний_інтелект #machine-learning").keys)
    }
    @Test fun excludesMarkdownCodeAndUrls() {
        val text = """
            # Заголовок
            ## Другий заголовок
            #real [посилання](https://example.org/#hidden) https://site/#no www.site/#skip
            `#inline` ``#another ` code``
            ```kotlin
            #code
            ```
            ~~~~
            #tilde
            ~~~~
                #indented
            \#escaped C#sharp foo#bar
            > #цитата
        """.trimIndent()
        assertEquals(setOf("real", "цитата"), hashtags(text).keys)
    }
    @Test fun fenceMustBeClosedWithSameCharacterAndLength() {
        assertEquals(setOf("outside"), hashtags("````\n#hidden\n```\n#alsohidden\n````\n#outside").keys)
        assertTrue(hashtags("~~~\n#hidden").isEmpty())
    }
    @Test fun nestedCodeFencesAreExcluded() {
        assertEquals(setOf("real"), hashtags("> ```\n> #quoteCode\n> ```\n- ~~~\n  #listCode\n  ~~~\n#real").keys)
    }
    @Test fun normalizationDoesNotDependOnDeviceLocale() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("vet", tagKey(" #VET "))
            assertEquals("інтелект", tagKey("#ІНТЕЛЕКТ"))
            assertEquals("ai", tagKey("ＡＩ"))
            assertEquals("café", tagKey("cafe\u0301"))
            assertEquals(tagKey("#vet"), tagKey("vet"))
        } finally { Locale.setDefault(old) }
        assertFalse(validTag("two words")); assertTrue(validTag("#машинне-навчання"))
    }
    @Test fun fuzzyRankingAndTypos() {
        assertTrue(fuzzyScore("вет", "вет")!! < fuzzyScore("ветеринарія", "вет")!!)
        assertTrue(fuzzyScore("android", "and")!! < fuzzyScore("my-android", "and")!!)
        assertNotNull(fuzzyScore("ветеринарія", "ветерінарія"))
        assertNotNull(fuzzyScore("android", "androd"))
        assertNotNull(fuzzyScore("vet", "vte"))
        assertNull(fuzzyScore("android", "книга"))
        assertEquals(0, fuzzyScore("VET", "#vet"))
    }
    @Test fun stableOrderingIgnoresUpdatedAt() {
        val clips = listOf(Clip(1, contentMarkdown = "a", rating = 1, createdAt = 10, updatedAt = 999),
            Clip(2, contentMarkdown = "b", rating = 5, createdAt = 1),
            Clip(3, contentMarkdown = "c", rating = 1, createdAt = 10),
            Clip(4, contentMarkdown = "d", rating = 1, createdAt = 11),
            Clip(5, contentMarkdown = "e", rating = 0, createdAt = 100))
        assertEquals(listOf(2L, 4L, 3L, 1L, 5L), clips.sortedWith(clipOrder).map { it.id })
    }
    @Test fun ftsEscapesOperatorsAndKeepsCyrillic() {
        assertEquals("title:ветеринарія* AND title:or* AND title:android*", ftsQuery("Ветеринарія OR android*", "title"))
        assertNull(ftsQuery("\"*-", "title"))
    }
    @Test fun longMarkdownAndCodeWhitespace() {
        val body = "    fun hello() {\n        println(\"Привіт\")\n    }"
        val markdown = "# Заголовок\n```kotlin\n$body\n```\n" + "Довгий текст\n".repeat(10000)
        val code = markdownBlocks(markdown).filterIsInstance<MarkdownBlock.Code>().single()
        assertEquals(body, code.text)
        assertEquals("kotlin", code.language)
        assertEquals("Заголовок", suggestedTitle(markdown))
    }
}
