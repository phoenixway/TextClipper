@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package ua.textclipper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.sqlite.db.SimpleSQLiteQuery
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun date(time: Long): String = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
private fun copy(context: Context, text: String) = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("TextClipper", text))
private fun share(context: Context, clip: Clip) = context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, clip.contentMarkdown); putExtra(Intent.EXTRA_SUBJECT, clip.title)
}, "Поділитися кліпом"))

@Composable
fun TextClipper(model: AppModel) {
    val dark = when (model.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val lightColors = lightColorScheme(primary = Color(0xFF245E52), secondary = Color(0xFF4D635C), background = Color(0xFFF8FAF7), surface = Color(0xFFF8FAF7))
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF9CD4BE)) else lightColors) {
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route ?: "clips"
        val snackbar = remember { SnackbarHostState() }
        var requestedTab by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(model.message) { model.message?.let { snackbar.showSnackbar(it); model.message = null } }
        LaunchedEffect(model.incoming) {
            model.incoming?.let { nav.navigate("editor/$it"); model.consumeShare() }
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    listOf(Triple("clips", "Кліпи", Icons.Default.List), Triple("tags", "Теги", Icons.Default.Star), Triple("settings", "Налаштування", Icons.Default.Settings)).forEach { (target, label, icon) ->
                        NavigationBarItem(selected = route == target || (target == "clips" && (route.startsWith("reader") || route.startsWith("editor"))),
                            onClick = {
                                // Editor handles its own exit so tab taps also protect unsaved work.
                                if (route.startsWith("editor")) requestedTab = target
                                else nav.navigate(target) { popUpTo("clips"); launchSingleTop = true }
                            }, icon = { Icon(icon, contentDescription = null) }, label = { Text(label) })
                    }
                }
            }
        ) { padding ->
            NavHost(nav, startDestination = "clips", modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
                composable("clips") { ClipsScreen(model, nav) }
                composable("tags") { TagsScreen(model, nav) }
                composable("settings") { SettingsScreen(model) }
                composable("reader/{id}") { backStack -> ReaderScreen(backStack.arguments?.getString("id")!!.toLong(), model, nav) }
                composable("editor/{token}") { backStack ->
                    val token = backStack.arguments?.getString("token")!!
                    val editor: EditorModel = viewModel(key = token, factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = EditorModel(model.services, token) as T
                    })
                    EditorScreen(editor, model, nav, requestedTab) { requestedTab = null }
                }
            }
        }
    }
}

@Composable
private fun ClipsScreen(model: AppModel, nav: NavHostController) {
    val rows by model.clips.collectAsStateWithLifecycle()
    val query by model.query.collectAsStateWithLifecycle()
    val selected by model.selectedTags.collectAsStateWithLifecycle()
    val tags by model.tags.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { model.refreshDrafts() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("TextClipper", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Ваша бібліотека текстів", style = MaterialTheme.typography.bodySmall) }
            FilledIconButton(onClick = { nav.navigate("editor/${model.capture()}") }) { Icon(Icons.Default.Add, "Створити кліп") }
        }
        OutlinedTextField(query, { model.query.value = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            placeholder = { Text("Теги, заголовки, тексти…") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { model.query.value = "" }) { Icon(Icons.Default.Clear, "Очистити пошук") } }, singleLine = true)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrBlank()) model.message = "Буфер обміну порожній" else nav.navigate("editor/${model.capture(text)}")
            }) { Text("Із буфера") }
            Spacer(Modifier.weight(1f))
            if (query.isNotBlank()) TextButton(onClick = { model.updateGrouped(!model.grouped) }) { Text(if (model.grouped) "За групами" else "Єдиний список") }
        }
        if (selected.isNotEmpty()) FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.filter { it.tag.id in selected }.forEach { value -> InputChip(selected = true, onClick = { model.toggleTag(value.tag.id) }, label = { Text("#${value.tag.name} ×") }) }
            TextButton(onClick = { model.selectedTags.value = emptySet() }) { Text("Скинути") }
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (model.drafts.isNotEmpty()) item {
                Column {
                    Text("Незбережені чернетки", style = MaterialTheme.typography.labelMedium)
                    model.drafts.forEach { draft -> TextButton(onClick = { nav.navigate("editor/${draft.token}") }) {
                        Text(draft.title.ifBlank { suggestedTitle(draft.content).ifBlank { "Новий кліп" } }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } }
                }
            }
            if (rows.isEmpty()) item { EmptyState(if (query.isBlank() && selected.isEmpty()) "Збережіть перший текст" else "Нічого не знайдено", "Додайте кліп кнопкою + або через «Поділитися» в іншому застосунку.") }
            if (model.grouped && query.isNotBlank()) {
                listOf("Знайдено за тегами" to rows.filter { it.tagMatch }, "Знайдено в заголовках" to rows.filter { it.titleMatch }, "Знайдено в текстах" to rows.filter { it.bodyMatch }).forEachIndexed { index, (title, group) ->
                    item(key = "heading$index") { Text("$title · ${group.size}", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleSmall) }
                    items(group, key = { "${index}_${it.clip.id}" }) { ClipCard(it, model, nav) }
                }
            } else items(rows, key = { it.clip.id }) { ClipCard(it, model, nav) }
        }
    }
}
@Composable
private fun EmptyState(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun ClipCard(row: ClipRow, model: AppModel, nav: NavHostController) {
    var menu by remember { mutableStateOf(false) }; var delete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Box {
        Card(Modifier.fillMaxWidth().combinedClickable(onClick = { nav.navigate("reader/${row.clip.id}") }, onLongClick = { menu = true }), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(row.clip.title.ifBlank { "Без заголовка" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.clip.contentMarkdown, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (row.tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.tags.take(5).forEach { tag -> Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
                        Text("#${tag.name}", Modifier.padding(horizontal = 6.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
                    } }
                    if (row.tags.size > 5) Text("+${row.tags.size - 5}", style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (row.clip.rating == 0) "Без рейтингу" else "★".repeat(row.clip.rating), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(date(row.clip.createdAt), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Редагувати") }, onClick = { menu = false; model.edit(row.clip.id) { nav.navigate("editor/$it") } })
            DropdownMenuItem(text = { Text("Копіювати текст") }, onClick = { menu = false; copy(context, row.clip.contentMarkdown) })
            DropdownMenuItem(text = { Text("Поділитися") }, onClick = { menu = false; share(context, row.clip) })
            DropdownMenuItem(text = { Text("Видалити") }, onClick = { menu = false; delete = true })
        }
    }
    if (delete) DeleteDialog({ delete = false }) { model.delete(row.clip.id); delete = false }
}
@Composable
private fun DeleteDialog(dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text("Видалити кліп?") }, text = { Text("Текст і його зв’язки з тегами буде видалено.") },
        confirmButton = { TextButton(onClick = confirm) { Text("Видалити") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Скасувати") } })
}
@Composable
private fun ReaderScreen(id: Long, model: AppModel, nav: NavHostController) {
    val flow = remember(id) { model.repo.dao.observe(SimpleSQLiteQuery("SELECT *, 0 AS tagMatch, 0 AS titleMatch, 0 AS bodyMatch FROM clips WHERE id=?", arrayOf(id))) }
    val rows by flow.collectAsStateWithLifecycle(emptyList())
    val row = rows.firstOrNull()
    var menu by remember { mutableStateOf(false) }; var delete by remember { mutableStateOf(false) }
    val context = LocalContext.current; val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Читання") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Default.ArrowBack, "Назад") } },
            actions = {
                TextButton(onClick = { model.edit(id) { nav.navigate("editor/$it") } }) { Text("Редагувати") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Дії") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Копіювати текст") }, onClick = { row?.let { copy(context, it.clip.contentMarkdown) }; menu = false })
                        DropdownMenuItem(text = { Text("Поділитися") }, onClick = { row?.let { share(context, it.clip) }; menu = false })
                        DropdownMenuItem(text = { Text("Видалити") }, onClick = { menu = false; delete = true })
                    }
                }
            })
        if (row != null) {
            val blocks = remember(row.clip.contentMarkdown) { markdownBlocks(row.clip.contentMarkdown) }
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Text(row.clip.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                    item { Text("${date(row.clip.createdAt)} · ${if (row.clip.rating == 0) "Без рейтингу" else "★".repeat(row.clip.rating)}", style = MaterialTheme.typography.labelMedium) }
                    item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.tags.forEach { tag -> AssistChip(onClick = { model.selectedTags.value = setOf(tag.id); nav.navigate("clips") { popUpTo("clips") { inclusive = true } } }, label = { Text("#${tag.name}") }) } } }
                    if (!row.clip.sourceUrl.isNullOrBlank()) item { TextButton(onClick = { val url = row.clip.sourceUrl!!; if (url.startsWith("https://") || url.startsWith("http://")) runCatching { uri.openUri(url) } }) { Text(row.clip.sourceUrl!!, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                    items(blocks.size) { MarkdownPiece(blocks[it]) }
                }
            }
        }
    }
    if (delete) DeleteDialog({ delete = false }) { model.delete(id) { nav.popBackStack() }; delete = false }
}

@Composable
private fun EditorScreen(editor: EditorModel, model: AppModel, nav: NavHostController, requestedTab: String?, consumeTab: () -> Unit) {
    val draft = editor.draft
    var leaving by remember { mutableStateOf(false) }; var picker by rememberSaveable { mutableStateOf(false) }
    val tags by model.tags.collectAsStateWithLifecycle()
    val detected = remember(draft.content, draft.suppressed) { hashtags(draft.content).filterKeys { it !in draft.suppressed } }
    fun finishExit() {
        model.refreshDrafts()
        nav.popBackStack()
        requestedTab?.let { target -> nav.navigate(target) { popUpTo("clips"); launchSingleTop = true } }
        consumeTab()
    }
    fun exit() { if (editor.saving) { consumeTab(); return }; if (draft.dirty) leaving = true else { editor.discard(); finishExit() } }
    LaunchedEffect(requestedTab) { if (requestedTab != null) exit() }
    fun saved(id: Long) {
        model.refreshDrafts()
        nav.navigate("reader/$id") { popUpTo("clips"); launchSingleTop = true }
    }
    BackHandler { exit() }
    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(title = { Text(if (draft.clipId == null) "Новий кліп" else "Редагування") },
            navigationIcon = { IconButton(onClick = { exit() }) { Icon(Icons.Default.ArrowBack, "Назад") } },
            actions = { Button(onClick = { editor.save(::saved) }, enabled = draft.content.isNotBlank() && !editor.saving) { Text(if (editor.saving) "Збереження…" else "Зберегти") } })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            editor.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item { OutlinedTextField(draft.title, { v -> editor.change { it.copy(title = v) } }, Modifier.fillMaxWidth(), label = { Text("Заголовок (необов’язково)") }, placeholder = { Text(suggestedTitle(draft.content)) }, singleLine = true) }
            item { OutlinedTextField(draft.content, editor::text, Modifier.fillMaxWidth().heightIn(min = 240.dp), label = { Text("Текст · Markdown") }, placeholder = { Text("Збережіть думку, цитату або промпт…") }, minLines = 10) }
            item {
                Text("Рейтинг · ${draft.rating}/5", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    (1..5).forEach { value -> IconToggleButton(checked = draft.rating >= value, onCheckedChange = { editor.change { it.copy(rating = if (it.rating == value) 0 else value) } }) {
                        Icon(Icons.Default.Star, "Оцінка $value", tint = if (draft.rating >= value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    } }
                }
                TextButton(onClick = { editor.change { it.copy(rating = 0) } }) { Text("Скинути рейтинг") }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Теги", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { picker = true }) { Text("Вибрати / додати") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    draft.manual.sorted().forEach { name -> InputChip(selected = true, onClick = { editor.manual(name) }, label = { Text("#$name · вручну ×") }) }
                    detected.filterKeys { key -> draft.manual.none { tagKey(it) == key } }.forEach { (key, name) -> InputChip(selected = false, onClick = { editor.hideAuto(key) }, label = { Text("#$name · з тексту ×") }) }
                }
                Text("Автотеги оновлюються після зміни тексту. Натисніть на тег, щоб прибрати його.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { OutlinedTextField(draft.source, { v -> editor.change { it.copy(source = v) } }, Modifier.fillMaxWidth(), label = { Text("URL джерела (необов’язково)") }, singleLine = true) }
        }
    }
    if (picker) TagPicker(tags, draft.manual, { editor.manual(it) }) { picker = false }
    if (leaving) AlertDialog(onDismissRequest = { leaving = false; consumeTab() }, title = { Text("Незбережені зміни") },
        text = { Text("Збережіть чернетку для продовження пізніше або видаліть її.") },
        confirmButton = { TextButton(onClick = { finishExit() }) { Text("Залишити чернетку") } },
        dismissButton = { Row {
            TextButton(onClick = { leaving = false; consumeTab() }) { Text("Продовжити") }
            TextButton(onClick = { editor.discard(); finishExit() }) { Text("Відкинути") }
        } })
}

private fun rankedTags(tags: List<TagCount>, query: String): List<TagCount> = tags.mapNotNull { value -> fuzzyScore(value.tag.name, query)?.let { value to it } }
    .sortedWith(compareBy<Pair<TagCount, Int>> { it.second }.thenByDescending { it.first.count }.thenBy { it.first.tag.normalizedName }).map { it.first }
@Composable
private fun TagPicker(tags: List<TagCount>, manual: Set<String>, toggle: (String) -> Unit, dismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val ranked = remember(tags, query) { rankedTags(tags, query) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).imePadding()) {
            Text("Додати теги", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 12.dp), label = { Text("Назва або пошук тегу") }, singleLine = true)
            if (query.isNotBlank() && validTag(query) && tags.none { it.tag.normalizedName == tagKey(query) }) TextButton(onClick = { toggle(tagName(query)); query = "" }) { Text("Створити #${tagName(query)}") }
            FlowRow(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ranked.forEach { value -> FilterChip(selected = manual.any { tagKey(it) == value.tag.normalizedName }, onClick = { toggle(value.tag.name) }, label = { Text("${value.tag.name} · ${value.count}") }) }
                manual.filter { name -> tags.none { it.tag.normalizedName == tagKey(name) } }.forEach { name -> FilterChip(selected = true, onClick = { toggle(name) }, label = { Text(name) }) }
            }
            Button(onClick = dismiss, Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text("Готово") }
        }
    }
}
@Composable
private fun TagsScreen(model: AppModel, nav: NavHostController) {
    val tags by model.tags.collectAsStateWithLifecycle(); val selected by model.selectedTags.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }; var multi by rememberSaveable { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Tag?>(null) }; var name by rememberSaveable { mutableStateOf("") }
    val ranked = remember(tags, query) { rankedTags(tags, query) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Теги", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Знайти тег") }, leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(multi, { multi = it }); Text("Вибрати кілька (AND)", Modifier.weight(1f))
            if (selected.isNotEmpty()) TextButton(onClick = { model.selectedTags.value = emptySet() }) { Text("Очистити") }
        }
        Text("Утримуйте тег для перейменування", style = MaterialTheme.typography.bodySmall)
        if (selected.isNotEmpty()) Button(onClick = { model.query.value = ""; nav.navigate("clips") { popUpTo("clips") { inclusive = true } } }, Modifier.fillMaxWidth()) { Text("Показати кліпи · ${selected.size} тег(и)") }
        FlowRow(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ranked.forEach { value ->
                val chosen = value.tag.id in selected
                Surface(shape = RoundedCornerShape(16.dp), color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.heightIn(min = 48.dp).combinedClickable(onClick = {
                        if (multi) model.toggleTag(value.tag.id)
                        else { model.selectedTags.value = setOf(value.tag.id); model.query.value = ""; nav.navigate("clips") { popUpTo("clips") { inclusive = true } } }
                    }, onLongClick = { rename = value.tag; name = value.tag.name })) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(value.tag.name, fontSize = (14 + minOf(6, value.count / 3)).sp, fontWeight = if (value.count > 3) FontWeight.Bold else FontWeight.Medium)
                        Text("  ${value.count}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (ranked.isEmpty()) Text("Тегів поки немає або нічого не знайдено", Modifier.padding(top = 24.dp))
        }
    }
    rename?.let { tag -> AlertDialog(onDismissRequest = { rename = null }, title = { Text("Перейменувати тег") },
        text = { Column { OutlinedTextField(name, { name = it }, label = { Text("Нова назва") }, singleLine = true); Text("Якщо назва вже існує, теги буде об’єднано.", style = MaterialTheme.typography.bodySmall) } },
        confirmButton = { TextButton(onClick = { model.rename(tag.id, name) { rename = null } }, enabled = validTag(name)) { Text("Зберегти") } },
        dismissButton = { TextButton(onClick = { rename = null }) { Text("Скасувати") } }) }
}
@Composable
private fun SettingsScreen(model: AppModel) {
    val count by model.count.collectAsStateWithLifecycle(); val tags by model.tags.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Налаштування", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Тема", style = MaterialTheme.typography.titleMedium)
        listOf("system" to "Системна", "light" to "Світла", "dark" to "Темна").forEach { (key, title) ->
            Row(Modifier.fillMaxWidth().clickable { model.updateTheme(key) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(model.theme == key, { model.updateTheme(key) }); Text(title)
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Групувати результати пошуку"); Text("За тегами, заголовками й текстами", style = MaterialTheme.typography.bodySmall) }
            Switch(model.grouped, model::updateGrouped)
        }
        HorizontalDivider()
        Text("Бібліотека", style = MaterialTheme.typography.titleMedium)
        Text("Кліпів: $count · Тегів: ${tags.size}")
        Text("TextClipper ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
        Text("Усі тексти зберігаються лише на цьому пристрої. Застосунок працює офлайн.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
