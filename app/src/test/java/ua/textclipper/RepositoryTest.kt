package ua.textclipper

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ClipperApp::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RepositoryTest {
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
        assertEquals(listOf(a), repo.observe("ветер").first().map { it.clip.id })
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
    @Test fun shareProcessTextAndIndependentDiskDrafts() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ClipperApp>()
        val model = AppModel(app)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, "Отриманий текст #вет")
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Заголовок із Share")
        }
        val payload = capturePayload(send)!!
        model.acceptShare(payload.text, payload.title)
        val first = model.incoming!!
        val process = android.content.Intent(android.content.Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"; putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, "Виділений абзац #vet")
        }
        val secondPayload = capturePayload(process)!!
        model.acceptShare(secondPayload.text, secondPayload.title)
        val second = model.incoming!!
        assertNotEquals(first, second)
        assertEquals(payload.text, app.drafts.read(first)!!.content)
        val id = repo.save(app.drafts.read(second)!!)
        assertEquals("Виділений абзац #vet", db.dao().get(id)!!.clip.contentMarkdown)
        assertEquals("vet", db.dao().get(id)!!.tags.single().normalizedName)
        assertTrue(AppModel(app).drafts.any { it.token == first })
        app.drafts.delete(first); app.drafts.delete(second); app.database.close()
    }
    @Test fun suppressedTagStaysHiddenUntilContentChanges() {
        val app = ApplicationProvider.getApplicationContext<ClipperApp>()
        val d = Draft(content = "#vet", manual = setOf("vet"), suppressed = setOf("vet"), dirty = true)
        app.drafts.write(d)
        val editor = EditorModel(app, d.token)
        assertEquals(setOf("vet"), editor.draft.suppressed)
        editor.change { it.copy(title = "Зміна заголовка") }
        assertEquals(setOf("vet"), EditorModel(app, d.token).draft.suppressed)
        editor.text("#vet новий текст")
        assertTrue(editor.draft.suppressed.isEmpty())
        assertEquals(setOf("vet"), editor.draft.manual)
        editor.discard()
    }

    @Test fun activityReceivesRepeatedSharesAndDoesNotDuplicateOnRecreation() {
        val app = ApplicationProvider.getApplicationContext<ClipperApp>()
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, "Перший Share #vet")
        }
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java, send).setup()
        val activity = controller.get()
        val model = androidx.lifecycle.ViewModelProvider(activity)[AppModel::class.java]
        val first = model.drafts.single { it.content == "Перший Share #vet" }.token
        val next = android.content.Intent(android.content.Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"; putExtra(android.content.Intent.EXTRA_PROCESS_TEXT, "Другий Share #вет")
        }
        controller.newIntent(next)
        val second = model.drafts.single { it.content == "Другий Share #вет" }.token
        assertNotEquals(first, second)
        val saved = android.os.Bundle()
        controller.pause().saveInstanceState(saved).stop().destroy()
        val restored = org.robolectric.Robolectric.buildActivity(MainActivity::class.java, next)
            .create(saved).start().resume()
        assertEquals(1, app.drafts.list().count { it.content == "Другий Share #вет" })
        assertEquals("Перший Share #vet", app.drafts.read(first)!!.content)
        restored.pause().stop().destroy()
        app.drafts.delete(first); app.drafts.delete(second); app.database.close()
    }

}
