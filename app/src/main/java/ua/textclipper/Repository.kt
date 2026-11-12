package ua.textclipper

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** Markdown stays canonical, making a future file exporter independent of UI. */
data class MarkdownDocument(val title: String, val markdown: String, val sourceUrl: String?, val tags: List<String>, val rating: Int)

class ClipRepository(private val db: ClipperDatabase) {
    val dao = db.dao()
    fun observe(query: String = "", tagIds: Set<Long> = emptySet()): Flow<List<ClipRow>> = dao.observe(searchSql(query, tagIds))
    suspend fun save(draft: Draft): Long = db.withTransaction {
        require(draft.content.isNotBlank()) { "Додайте текст кліпу" }
        require(draft.rating in 0..5)
        val old = draft.clipId?.let { dao.get(it)?.clip ?: error("Кліп уже видалено") }
        val clip = Clip(id = old?.id ?: 0, title = draft.title.trim().ifEmpty { suggestedTitle(draft.content) },
            contentMarkdown = draft.content, sourceUrl = draft.source.trim().ifEmpty { null }, rating = draft.rating,
            createdAt = old?.createdAt ?: System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        val id = if (old == null) dao.insert(clip) else { dao.update(clip); clip.id }
        dao.clearLinks(id); dao.clearSuppressed(id)
        val manual = draft.manual.associateBy(::tagKey)
        val detected = hashtags(draft.content).filterKeys { it !in draft.suppressed }
        (manual.keys + detected.keys).forEach { key ->
            val name = manual[key] ?: detected.getValue(key)
            val tag = ensureTag(name)
            dao.bind(ClipTag(id, tag.id, key in manual, key in detected))
        }
        draft.suppressed.forEach { key -> dao.suppress(SuppressedTag(id, ensureTag(key).id)) }
        id
    }
    private suspend fun ensureTag(name: String): Tag {
        require(validTag(name)) { "Тег: літери, цифри, _ або -, до 80 символів" }
        val key = tagKey(name)
        dao.insertTag(Tag(name = tagName(name), normalizedName = key))
        return requireNotNull(dao.tag(key))
    }
    suspend fun renameTag(id: Long, name: String) = db.withTransaction {
        require(validTag(name)) { "Некоректна назва тегу" }
        val old = requireNotNull(dao.tagById(id))
        val target = dao.tag(tagKey(name))
        if (target == null || target.id == id) {
            dao.updateTag(old.copy(name = tagName(name), normalizedName = tagKey(name)))
            id
        }
        else {
            dao.links(id).forEach { link ->
                val existing = dao.link(link.clipId, target.id)
                dao.bind(link.copy(tagId = target.id, manualAssigned = link.manualAssigned || existing?.manualAssigned == true,
                    textDetected = link.textDetected || existing?.textDetected == true))
            }
            dao.suppressionsForTag(id).forEach { dao.suppress(it.copy(tagId = target.id)) }
            dao.deleteTag(id)
            target.id
        }
    }
    companion object {
        fun searchSql(query: String, tagIds: Set<Long> = emptySet()): SimpleSQLiteQuery {
            val args = mutableListOf<Any>()
            val trimmed = query.trim()
            val tag = if (trimmed.isEmpty()) "0" else {
                args.add("%" + tagKey(trimmed).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
                "EXISTS(SELECT 1 FROM clip_tags ct JOIN tags t ON t.id=ct.tagId WHERE ct.clipId=c.id AND t.normalizedName LIKE ? ESCAPE '\\')"
            }
            fun match(column: String): String {
                val fts = ftsQuery(trimmed, column) ?: return "0"
                args.add(fts)
                return "c.id IN (SELECT rowid FROM clip_fts WHERE clip_fts MATCH ?)"
            }
            val title = match("title"); val body = match("contentMarkdown")
            val filter = if (tagIds.isEmpty()) "1" else {
                args.addAll(tagIds.sorted()); args.add(tagIds.size)
                "c.id IN (SELECT clipId FROM clip_tags WHERE tagId IN (${tagIds.joinToString { "?" }}) GROUP BY clipId HAVING COUNT(DISTINCT tagId)=?)"
            }
            return SimpleSQLiteQuery("""SELECT * FROM (SELECT c.*, $tag AS tagMatch, $title AS titleMatch, $body AS bodyMatch
                FROM clips c WHERE $filter) WHERE ${if (trimmed.isEmpty()) "1" else "(tagMatch OR titleMatch OR bodyMatch)"}
                ORDER BY rating DESC, createdAt DESC, id DESC""", args.toTypedArray())
        }
    }
}
