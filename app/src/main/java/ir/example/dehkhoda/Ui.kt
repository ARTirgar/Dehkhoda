package ir.example.dehkhoda

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Composable
fun DehkhodaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content
    )
}

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as DehkhodaApp).container
    private val repo = c.repo
    val query = MutableStateFlow("")
    val deep = MutableStateFlow(false)
    val historyEnabled: StateFlow<Boolean> = c.settings.historyEnabled

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<Entry>> = combine(query, deep) { q, d -> q to d }
        .debounce { if (it.first.isBlank()) 0L else 180L }
        .flatMapLatest { (q, d) -> flow { emit(if (q.isBlank()) emptyList() else repo.search(q, d)) } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteIds: StateFlow<Set<Long>> = repo.favoriteIds().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val history: StateFlow<List<SearchHistory>> = repo.history(40)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val suggestions: StateFlow<List<SearchHistory>> = query.debounce(120)
        .flatMapLatest { repo.historySuggestions(it, 5) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun commitQuery() {
        val q = query.value
        if (q.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) { repo.recordSearch(q, hits = results.value.size) }
    }
    fun openEntry(e: Entry) {
        val q = query.value
        viewModelScope.launch(Dispatchers.IO) { repo.recordSearch(q, e.id, e.word, results.value.size) }
    }
    fun removeHistory(key: String) = viewModelScope.launch(Dispatchers.IO) { repo.removeHistory(key) }
    fun clearHistory() = viewModelScope.launch(Dispatchers.IO) { repo.clearHistory() }
    fun setHistoryEnabled(v: Boolean) = c.settings.setHistoryEnabled(v)
    fun toggleFavorite(id: Long) = viewModelScope.launch(Dispatchers.IO) { repo.toggleFavorite(id) }
}

class FavoritesViewModel(app: Application) : AndroidViewModel(app) {
    val items = (app as DehkhodaApp).container.repo.favorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

class DetailViewModel(app: Application, state: SavedStateHandle) : AndroidViewModel(app) {
    private val repo = (app as DehkhodaApp).container.repo
    private val id: Long = state.get<Long>("id") ?: 0L
    val entry = flow { emit(repo.entry(id)) }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val isFavorite = repo.isFavorite(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    fun toggleFavorite() = viewModelScope.launch(Dispatchers.IO) { repo.toggleFavorite(id) }
}

@Composable
fun ImportScreen(count: Long, error: String?, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("در حال آماده‌سازی لغت‌نامه…", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        if (error == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text("$count واژه وارد شد", style = MaterialTheme.typography.bodySmall)
            Text("این کار فقط یک‌بار انجام می‌شود.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("خطا: $error", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("تلاش دوباره") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(vm: SearchViewModel, onOpen: (Long) -> Unit) {
    val query by vm.query.collectAsStateWithLifecycle()
    val deep by vm.deep.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val favs by vm.favoriteIds.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val historyOn by vm.historyEnabled.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var askClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        OutlinedTextField(
            value = query, onValueChange = { vm.query.value = it },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            placeholder = { Text("جست‌وجوی واژه…") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty())
                    IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Close, "پاک کردن") }
            },
            singleLine = true, shape = MaterialTheme.shapes.extraLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.commitQuery(); focus.clearFocus() })
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            FilterChip(selected = deep, onClick = { vm.deep.value = !deep },
                label = { Text("جست‌وجو در متن تعریف‌ها") })
            Spacer(Modifier.weight(1f))
            if (query.isNotBlank() && results.isNotEmpty())
                Text("${results.size} نتیجه", style = MaterialTheme.typography.bodySmall)
        }

        if (query.isBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("جست‌وجوهای اخیر", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
                TextButton(enabled = history.isNotEmpty(), onClick = { askClear = true }) {
                    Icon(Icons.Filled.DeleteSweep, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text("پاک کردن")
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                if (history.isEmpty()) {
                    item {
                        Box(Modifier.fillParentMaxHeight(0.5f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("واژه‌ای را بنویس؛ مثلاً «آب» یا «مهر»")
                        }
                    }
                } else {
                    items(history, key = { it.queryNorm }) { h ->
                        HistoryRow(h, onClick = {
                            val hid = h.entryId
                            if (hid != null) onOpen(hid) else vm.query.value = h.query
                        }, onDelete = { vm.removeHistory(h.queryNorm) })
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("ذخیرهٔ تاریخچهٔ جست‌وجو", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Switch(checked = historyOn, onCheckedChange = { vm.setHistoryEnabled(it) })
                    }
                }
            }
        } else {
            if (suggestions.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { s ->
                        AssistChip(onClick = { vm.query.value = s.query },
                            label = { Text(s.query, maxLines = 1) },
                            leadingIcon = { Icon(Icons.Filled.History, null, Modifier.size(16.dp)) })
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(results, key = { it.id }) { e ->
                    EntryRow(e, favs.contains(e.id),
                        { vm.openEntry(e); onOpen(e.id) }, { vm.toggleFavorite(e.id) })
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }

    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text("پاک کردن تاریخچه؟") },
            text = { Text("همهٔ جست‌وجوهای اخیر حذف می‌شوند. واژه‌های نشان‌شده می‌مانند.") },
            confirmButton = { TextButton(onClick = { vm.clearHistory(); askClear = false }) { Text("پاک کن") } },
            dismissButton = { TextButton(onClick = { askClear = false }) { Text("انصراف") } }
        )
    }
}

@Composable
fun EntryRow(e: Entry, fav: Boolean, onClick: () -> Unit, onFav: () -> Unit) {
    // Bounded preview, computed once per entry — not on every recomposition,
    // and never over the full (up to 65k-char) definition.
    val preview = remember(e.id) { PersianText.preview(e.definition) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(e.word, style = MaterialTheme.typography.titleMedium)
            Text(preview, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onFav) {
            Icon(if (fav) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "نشان",
                tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun HistoryRow(h: SearchHistory, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (h.entryId != null) Icons.Filled.Bookmark else Icons.Filled.History, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.query, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = buildString {
                h.word?.let { append(it).append(" • ") }
                append(relativeTimeFa(h.lastAt))
                if (h.count > 1) append(" • ${faDigits(h.count.toLong())} بار")
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Close, "حذف از تاریخچه") }
    }
}

@Composable
fun FavoritesScreen(vm: FavoritesViewModel, onOpen: (Long) -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("هنوز واژه‌ای نشان نکرده‌ای.") }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            items(items, key = { it.id }) { e ->
                EntryRow(e, true, { onOpen(e.id) }, {})
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

/** Definitions longer than this (plain chars) render as lazy blocks: a single
 *  giant Text re-draws its whole StaticLayout every scroll frame (measured
 *  ~123 ms draw/frame at 65k chars vs ~4 ms at 2k on the JVM harness). */
private const val LAZY_DEF_CHARS = 4000
private const val DEF_CHUNK_CHARS = 2500

internal data class DetailBlock(val text: String, val paragraphEnd: Boolean)

/** Split a long definition into viewport-friendly blocks: one block per
 *  paragraph; paragraphs longer than [chunkTarget] are chunked at sentence
 *  boundaries (fallback: word boundary) so even single-block definitions
 *  render lazily. Chunks of one paragraph stack without extra spacing;
 *  paragraph ends get a spacer. Word content is preserved exactly — only
 *  whitespace at chunk boundaries is dropped. Trade-off: a chunk's final
 *  line renders natural (not stretched) like a paragraph-final line. */
internal fun detailBlocks(text: String, chunkTarget: Int = DEF_CHUNK_CHARS): List<DetailBlock> {
    val out = ArrayList<DetailBlock>()
    for (raw in text.split('\n')) {
        val p = raw.trim()
        if (p.isEmpty()) continue
        if (p.length <= chunkTarget) { out.add(DetailBlock(p, true)); continue }
        var start = 0
        while (start < p.length) {
            val hardEnd = minOf(start + chunkTarget, p.length)
            if (hardEnd >= p.length) { out.add(DetailBlock(p.substring(start), true)); break }
            var cut = -1
            var i = hardEnd - 1
            val floor = start + chunkTarget / 2
            while (i >= floor) {
                val ch = p[i]
                if (ch == '.' || ch == '!' || ch == '؟' || ch == '؛' || ch == '۔') {
                    var j = i + 1
                    while (j < p.length && p[j] == ' ') j++
                    cut = j
                    break
                }
                i--
            }
            if (cut < 0) {
                val sp = p.lastIndexOf(' ', hardEnd - 1)
                cut = if (sp > start) sp + 1 else hardEnd
            }
            out.add(DetailBlock(p.substring(start, cut).trimEnd(), cut >= p.length))
            start = cut
        }
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: DetailViewModel, onBack: () -> Unit) {
    val entry by vm.entry.collectAsStateWithLifecycle()
    val fav by vm.isFavorite.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Compute once per entry — fav toggles and other recompositions must not
    // re-run plainText over the full definition (up to 65k chars).
    val entryId = entry?.id
    val text = remember(entryId) { entry?.let { PersianText.plainText(it.definition) } ?: "" }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(entry?.word ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "بازگشت") } },
            actions = {
                IconButton(onClick = { vm.toggleFavorite() }) {
                    Icon(if (fav) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, "نشان")
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Icon(Icons.Filled.ContentCopy, "کپی") }
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "${entry?.word}\n\n$text")
                    }
                    ctx.startActivity(Intent.createChooser(send, "اشتراک"))
                }) { Icon(Icons.Filled.Share, "اشتراک") }
            })
    }) { pad ->
        // Justified Persian definition; RTL comes from the app-wide
        // LayoutDirection.Rtl. TextAlign.Justify needs API 26+ and
        // falls back to start alignment on API 24-25 (minSdk 24).
        val defStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 28.sp)
        if (text.length <= LAZY_DEF_CHARS) {
            SelectionContainer {
                Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(entry?.word ?: "",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(16.dp))
                    Text(text, style = defStyle, textAlign = TextAlign.Justify)
                }
            }
        } else {
            // Long definition: lazy blocks bound composition, layout AND draw
            // to the viewport instead of the full definition length.
            val blocks = remember(entryId) { detailBlocks(text) }
            LazyColumn(Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)) {
                item(key = "header") {
                    Column {
                        Text(entry?.word ?: "",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(16.dp))
                    }
                }
                itemsIndexed(blocks, key = { i, _ -> i }) { _, b ->
                    SelectionContainer {
                        Text(b.text, style = defStyle, textAlign = TextAlign.Justify)
                    }
                    if (b.paragraphEnd) Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val topLevel = route == "search" || route == "favorites"
    Scaffold(bottomBar = {
        if (topLevel) NavigationBar {
            NavigationBarItem(selected = route == "search", onClick = {
                nav.navigate("search") { popUpTo("search") { inclusive = true }; launchSingleTop = true }
            }, icon = { Icon(Icons.Filled.Search, null) }, label = { Text("جست‌وجو") })
            NavigationBarItem(selected = route == "favorites", onClick = {
                nav.navigate("favorites") { launchSingleTop = true }
            }, icon = { Icon(Icons.Filled.Bookmark, null) }, label = { Text("نشان‌شده‌ها") })
        }
    }) { pad ->
        NavHost(nav, startDestination = "search", modifier = Modifier.padding(pad)) {
            composable("search") { SearchScreen(viewModel()) { id -> nav.navigate("detail/$id") } }
            composable("favorites") { FavoritesScreen(viewModel()) { id -> nav.navigate("detail/$id") } }
            composable("detail/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                DetailScreen(viewModel()) { nav.popBackStack() }
            }
        }
    }
}

private fun faDigits(n: Long): String =
    n.toString().map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")

fun relativeTimeFa(at: Long): String {
    val diff = (System.currentTimeMillis() - at).coerceAtLeast(0L)
    val min = diff / 60000; val hr = min / 60; val day = hr / 24
    return when {
        min < 1 -> "همین حالا"
        min < 60 -> "${faDigits(min)} دقیقه پیش"
        hr < 24 -> "${faDigits(hr)} ساعت پیش"
        day < 7 -> "${faDigits(day)} روز پیش"
        else -> "${faDigits(day / 7)} هفته پیش"
    }
}
