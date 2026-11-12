package ua.textclipper

import android.app.Application
import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ClipperApp : Application() {
    val database by lazy { Room.databaseBuilder(this, ClipperDatabase::class.java, "textclipper.db").build() }
    val repository by lazy { ClipRepository(database) }
    val drafts by lazy { DraftStore(this) }
}
data class Draft(
    val token: String = UUID.randomUUID().toString(), val clipId: Long? = null,
    val title: String = "", val content: String = "", val source: String = "", val rating: Int = 0,
    val manual: Set<String> = emptySet(), val suppressed: Set<String> = emptySet(), val dirty: Boolean = false,
)
/** Atomic local files avoid Bundle limits for long articles and survive process death. */
class DraftStore(context: Context) {
    private val folder = File(context.filesDir, "drafts").apply { mkdirs() }
    private fun file(token: String): AtomicFile {
        require(token.matches(Regex("[a-zA-Z0-9-]+")))
        return AtomicFile(File(folder, "$token.json"))
    }
    @Synchronized fun write(d: Draft) {
        val json = JSONObject().put("token", d.token).put("clipId", d.clipId ?: JSONObject.NULL)
            .put("title", d.title).put("content", d.content).put("source", d.source).put("rating", d.rating)
            .put("manual", JSONArray(d.manual.toList())).put("suppressed", JSONArray(d.suppressed.toList())).put("dirty", d.dirty)
        val target = file(d.token)
        val out = target.startWrite()
        try { out.write(json.toString().toByteArray(Charsets.UTF_8)); target.finishWrite(out) }
        catch (e: Exception) { target.failWrite(out); throw e }
    }
    @Synchronized fun read(token: String): Draft? {
        val f = file(token)
        if (!f.baseFile.exists() && !File(f.baseFile.path + ".bak").exists()) return null
        val j = JSONObject(f.openRead().bufferedReader().use { it.readText() })
        fun set(key: String) = j.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        return Draft(j.getString("token"), if (j.isNull("clipId")) null else j.getLong("clipId"),
            j.getString("title"), j.getString("content"), j.getString("source"), j.getInt("rating"), set("manual"), set("suppressed"), j.getBoolean("dirty"))
    }
    @Synchronized fun delete(token: String) = file(token).delete()
    @Synchronized fun list(): List<Draft> = folder.listFiles().orEmpty().filter { it.extension == "json" }
        .sortedByDescending { it.lastModified() }.mapNotNull { runCatching { read(it.nameWithoutExtension) }.getOrNull() }.filter { it.dirty }
}
class AppModel(app: Application) : AndroidViewModel(app) {
    val services = app as ClipperApp
    val repo = services.repository
    val preferences = app.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    var theme by mutableStateOf(preferences.getString("theme", "system") ?: "system"); private set
    var grouped by mutableStateOf(preferences.getBoolean("grouped", false)); private set
    val query = MutableStateFlow("")
    val selectedTags = MutableStateFlow<Set<Long>>(emptySet())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
    val clips = combine(query.debounce(120), selectedTags) { q, t -> q to t }
        .flatMapLatest { (q, t) -> repo.observe(q, t) }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val tags = repo.dao.tags().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val count = repo.dao.count().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    var drafts by mutableStateOf(services.drafts.list()); private set
    var incoming by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)
    fun refreshDrafts() { drafts = services.drafts.list() }
    fun updateTheme(value: String) { theme = value; preferences.edit().putString("theme", value).apply() }
    fun updateGrouped(value: Boolean) { grouped = value; preferences.edit().putBoolean("grouped", value).apply() }
    fun toggleTag(id: Long) { selectedTags.value = selectedTags.value.let { if (id in it) it - id else it + id } }
    fun capture(text: String = "", title: String = ""): String {
        val draft = Draft(content = text, title = title, dirty = text.isNotBlank() || title.isNotBlank())
        services.drafts.write(draft); refreshDrafts()
        return draft.token
    }
    fun acceptShare(text: String, title: String) { incoming = capture(text, title) }
    fun consumeShare() { incoming = null }
    fun edit(id: Long, navigate: (String) -> Unit) = viewModelScope.launch {
        runCatching {
            val existing = services.drafts.list().firstOrNull { it.clipId == id }
            val draft = existing ?: withContext(Dispatchers.IO) {
                val row = repo.dao.get(id) ?: error("Кліп уже видалено")
                Draft(clipId = id, title = row.clip.title, content = row.clip.contentMarkdown,
                    source = row.clip.sourceUrl.orEmpty(), rating = row.clip.rating,
                    manual = row.tags.filter { it.manualAssigned }.map { it.name }.toSet(),
                    suppressed = repo.dao.suppressed(id).toSet())
            }.also { services.drafts.write(it) }
            navigate(draft.token)
        }.onFailure { message = it.message ?: "Не вдалося відкрити кліп" }
    }
    fun delete(id: Long, done: () -> Unit = {}) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { repo.dao.deleteClip(id) } }
            .onSuccess { services.drafts.list().filter { it.clipId == id }.forEach { services.drafts.delete(it.token) }; refreshDrafts(); done() }
            .onFailure { message = "Не вдалося видалити кліп" }
    }
    fun rename(id: Long, name: String, done: () -> Unit) = viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { repo.renameTag(id, name) } }
            .onSuccess { targetId ->
                if (id in selectedTags.value) selectedTags.value = selectedTags.value - id + targetId
                done()
            }.onFailure { message = it.message ?: "Не вдалося перейменувати тег" }
    }
}
class EditorModel(private val app: ClipperApp, token: String) : ViewModel() {
    var draft by mutableStateOf(app.drafts.read(token) ?: Draft(token = token)); private set
    var saving by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    fun change(transform: (Draft) -> Draft) {
        if (saving) return
        draft = transform(draft).copy(dirty = true)
        runCatching { app.drafts.write(draft) }.onFailure { error = "Не вдалося зберегти чернетку: ${it.message}" }
    }
    fun text(value: String) = change { if (value == it.content) it else it.copy(content = value, suppressed = emptySet()) }
    fun manual(name: String) = change { d ->
        val key = tagKey(name)
        if (d.manual.any { tagKey(it) == key }) d.copy(manual = d.manual.filterNot { tagKey(it) == key }.toSet())
        else d.copy(manual = d.manual + tagName(name), suppressed = d.suppressed - key)
    }
    fun hideAuto(key: String) = change { it.copy(suppressed = it.suppressed + key) }
    fun discard() = app.drafts.delete(draft.token)
    fun save(done: (Long) -> Unit) {
        if (saving) return
        saving = true; error = null
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { app.repository.save(draft) } }
                .onSuccess { discard(); done(it) }
                .onFailure { error = it.message ?: "Не вдалося зберегти кліп" }
            saving = false
        }
    }
}
