package ua.textclipper

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "clips", indices = [Index(value = ["rating", "createdAt", "id"])])
data class Clip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val contentMarkdown: String,
    val sourceUrl: String? = null,
    val rating: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)
@Entity(tableName = "tags", indices = [Index(value = ["normalizedName"], unique = true)])
data class Tag(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val normalizedName: String)
@Entity(tableName = "clip_tags", primaryKeys = ["clipId", "tagId"], indices = [Index("tagId")], foreignKeys = [
    ForeignKey(entity = Clip::class, parentColumns = ["id"], childColumns = ["clipId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)])
data class ClipTag(val clipId: Long, val tagId: Long, val manualAssigned: Boolean, val textDetected: Boolean)
@Entity(tableName = "suppressed_tags", primaryKeys = ["clipId", "tagId"], indices = [Index("tagId")], foreignKeys = [
    ForeignKey(entity = Clip::class, parentColumns = ["id"], childColumns = ["clipId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = Tag::class, parentColumns = ["id"], childColumns = ["tagId"], onDelete = ForeignKey.CASCADE)])
data class SuppressedTag(val clipId: Long, val tagId: Long)
@Fts4(contentEntity = Clip::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "clip_fts")
data class ClipFts(val title: String, val contentMarkdown: String)
@DatabaseView("SELECT tags.*, clip_tags.clipId, clip_tags.manualAssigned, clip_tags.textDetected FROM tags JOIN clip_tags ON tags.id = clip_tags.tagId")
data class Assignment(val id: Long, val name: String, val normalizedName: String, val clipId: Long, val manualAssigned: Boolean, val textDetected: Boolean)
data class ClipRow(
    @Embedded val clip: Clip,
    val tagMatch: Boolean = false, val titleMatch: Boolean = false, val bodyMatch: Boolean = false,
    @Relation(parentColumn = "id", entityColumn = "clipId", entity = Assignment::class) val tags: List<Assignment> = emptyList(),
)
data class TagCount(@Embedded val tag: Tag, val count: Int)

@Dao
interface ClipDao {
    @Transaction @RawQuery(observedEntities = [Clip::class, Tag::class, ClipTag::class])
    fun observe(query: SupportSQLiteQuery): Flow<List<ClipRow>>
    @Transaction @Query("SELECT *, 0 AS tagMatch, 0 AS titleMatch, 0 AS bodyMatch FROM clips WHERE id = :id")
    suspend fun get(id: Long): ClipRow?
    @Query("SELECT tags.*, COUNT(clip_tags.clipId) AS count FROM tags LEFT JOIN clip_tags ON tags.id=clip_tags.tagId GROUP BY tags.id ORDER BY count DESC, normalizedName")
    fun tags(): Flow<List<TagCount>>
    @Query("SELECT * FROM tags WHERE normalizedName = :key") suspend fun tag(key: String): Tag?
    @Query("SELECT * FROM tags WHERE id = :id") suspend fun tagById(id: Long): Tag?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTag(tag: Tag): Long
    @Update suspend fun updateTag(tag: Tag)
    @Insert suspend fun insert(clip: Clip): Long
    @Update suspend fun update(clip: Clip)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun bind(link: ClipTag)
    @Query("SELECT * FROM clip_tags WHERE tagId = :id") suspend fun links(id: Long): List<ClipTag>
    @Query("SELECT * FROM clip_tags WHERE clipId = :clipId AND tagId = :tagId") suspend fun link(clipId: Long, tagId: Long): ClipTag?
    @Query("DELETE FROM clip_tags WHERE clipId = :id") suspend fun clearLinks(id: Long)
    @Query("DELETE FROM clips WHERE id = :id") suspend fun deleteClip(id: Long)
    @Query("DELETE FROM tags WHERE id = :id") suspend fun deleteTag(id: Long)
    @Query("SELECT tags.normalizedName FROM tags JOIN suppressed_tags ON tags.id=suppressed_tags.tagId WHERE clipId = :id")
    suspend fun suppressed(id: Long): List<String>
    @Query("SELECT * FROM suppressed_tags WHERE tagId = :id") suspend fun suppressionsForTag(id: Long): List<SuppressedTag>
    @Query("DELETE FROM suppressed_tags WHERE clipId = :id") suspend fun clearSuppressed(id: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun suppress(value: SuppressedTag)
    @Query("SELECT count(*) FROM clips") fun count(): Flow<Int>
}
@Database(entities = [Clip::class, Tag::class, ClipTag::class, ClipFts::class, SuppressedTag::class], views = [Assignment::class], version = 1, exportSchema = true)
abstract class ClipperDatabase : RoomDatabase() { abstract fun dao(): ClipDao }
