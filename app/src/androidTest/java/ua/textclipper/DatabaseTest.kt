package ua.textclipper

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: ClipperDatabase
    private lateinit var repo: ClipRepository
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, ClipperDatabase::class.java).build(); repo = ClipRepository(db) }
    @After fun close() { db.close() }
    @Test fun crudLongMarkdownFtsAndStableOrder() = runBlocking {
        val markdown = "## Тест\n" + "Промпт ветеринарія\n".repeat(10000) + "```\n  #ignored\n```\n#vet"
        val a = repo.save(Draft(title = "Український заголовок", content = markdown, manual = setOf("VET"), rating = 5))
        val b = repo.save(Draft(content = "інший", rating = 0))
        val first = db.dao().get(a)!!
        assertEquals(markdown, first.clip.contentMarkdown)
        assertEquals(1, first.tags.size)
        assertTrue(first.tags.single().manualAssigned); assertTrue(first.tags.single().textDetected)
        assertEquals(listOf(a), repo.observe("ветеринарія").first().map { it.clip.id })
        assertTrue(repo.observe("український").first().single().titleMatch)
        assertTrue(repo.observe("ветеринарія").first().single().bodyMatch)
        assertTrue(repo.observe("#VET").first().single().tagMatch)
        assertEquals(listOf(a,b), repo.observe().first().map { it.clip.id })
        repo.save(Draft(clipId = a, content = "оновлено", manual = setOf("vet"), rating = 5))
        assertEquals(first.clip.createdAt, db.dao().get(a)!!.clip.createdAt)
        assertTrue(repo.observe("ветеринарія").first().isEmpty())
        assertFalse(db.dao().get(a)!!.tags.single().textDetected)
        assertTrue(db.dao().get(a)!!.tags.single().manualAssigned)
        db.dao().deleteClip(a)
        assertNull(db.dao().get(a)); assertTrue(db.dao().links(first.tags.single().id).isEmpty())
        assertTrue(repo.observe("оновлено").first().isEmpty())
    }
    @Test fun automaticTagsSuppressionAndRenameMerge() = runBlocking {
        val id = repo.save(Draft(content = "#vet #android", manual = setOf("VET", "pet")))
        val tags = db.dao().get(id)!!.tags
        val vet = tags.single { it.normalizedName == "vet" }
        val pet = tags.single { it.normalizedName == "pet" }
        repo.renameTag(pet.id, "VET")
        assertEquals(2, db.dao().get(id)!!.tags.size)
        assertTrue(db.dao().get(id)!!.tags.single { it.id == vet.id }.manualAssigned)
        val android = tags.single { it.normalizedName == "android" }
        assertEquals(1, repo.observe(tagIds = setOf(vet.id, android.id)).first().size)
        repo.save(Draft(clipId = id, content = "#vet", manual = setOf("vet"), suppressed = setOf("vet")))
        val retained = db.dao().get(id)!!.tags.single()
        assertTrue(retained.manualAssigned); assertFalse(retained.textDetected)
        assertEquals(listOf("vet"), db.dao().suppressed(id))
        repo.save(Draft(clipId = id, content = "без тегів"))
        assertTrue(db.dao().get(id)!!.tags.isEmpty())
    }
    @Test fun sqlOrderingAllSearchAndFilterModes() = runBlocking {
        val values = listOf(Clip(id = 10, contentMarkdown = "пошук #vet", title = "пошук", rating = 4, createdAt = 50),
            Clip(id = 11, contentMarkdown = "пошук #vet", title = "пошук", rating = 4, createdAt = 50),
            Clip(id = 12, contentMarkdown = "пошук #vet", title = "пошук", rating = 5, createdAt = 1))
        values.forEach { c -> db.dao().insert(c); db.dao().insertTag(Tag(name = "vet", normalizedName = "vet")); db.dao().bind(ClipTag(c.id, db.dao().tag("vet")!!.id, false, true)) }
        val expected = listOf(12L,11L,10L)
        assertEquals(expected, repo.observe().first().map { it.clip.id })
        assertEquals(expected, repo.observe("пошук").first().map { it.clip.id })
        assertEquals(expected, repo.observe("vet", setOf(db.dao().tag("vet")!!.id)).first().map { it.clip.id })
        val groups = repo.observe("пошук").first()
        assertEquals(3, groups.count { it.titleMatch }); assertEquals(3, groups.count { it.bodyMatch })
        db.dao().update(values[0].copy(updatedAt = 999999))
        assertEquals(expected, repo.observe().first().map { it.clip.id })
        assertTrue(runCatching { repo.save(Draft(content = "bad", rating = 6)) }.isFailure)
    }
    @Test fun survivesDatabaseReopenAndDraftRecovery() = runBlocking {
        val name = "persistence-test.db"; context.deleteDatabase(name)
        var disk = Room.databaseBuilder(context, ClipperDatabase::class.java, name).build()
        val id = ClipRepository(disk).save(Draft(content = "стійкий текст #вет")); disk.close()
        disk = Room.databaseBuilder(context, ClipperDatabase::class.java, name).build()
        assertEquals(id, ClipRepository(disk).observe("стійкий").first().single().clip.id)
        disk.close(); context.deleteDatabase(name)
        val draft = Draft(content = "довгий\n".repeat(30000), dirty = true)
        DraftStore(context).write(draft)
        assertEquals(draft, DraftStore(context).read(draft.token)); DraftStore(context).delete(draft.token)
    }
}
