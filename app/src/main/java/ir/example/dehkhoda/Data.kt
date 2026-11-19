package ir.example.dehkhoda

import android.app.Application
import android.content.Context
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

object PersianText {
    private val brPRe = Regex("<br\\s*/?>|</p>")
    private val tagRe = Regex("<[^>]+>")

    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            val c: Char? = when (ch) {
                'ي', 'ى', 'ئ', 'ٸ', 'ۓ' -> 'ی'
                'ك', 'ڪ' -> 'ک'
                'ة', 'ۀ', 'ۂ' -> 'ه'
                'أ', 'إ', 'آ', 'ٱ', 'ٲ', 'ٳ' -> 'ا'
                'ؤ', 'ٶ' -> 'و'
                '\u0640' -> null
                '\u200c', '\u200d', '\u200e', '\u200f', '\u00ad' -> ' '
                in '\u064B'..'\u0652' -> null
                in '\u0653'..'\u065F' -> null
                '\u0670' -> null
                in '\u06D6'..'\u06ED' -> null
                in '۰'..'۹' -> '0' + (ch - '۰')
                in '٠'..'٩' -> '0' + (ch - '٠')
                else -> ch
            }
            if (c != null) sb.append(c.lowercaseChar())
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }
    fun compact(input: String): String = normalize(input).replace(" ", "")
    fun tokens(input: String): List<String> =
        normalize(input).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    fun plainText(s: String): String {
        if (!s.contains('<')) return collapseSourceWraps(s).trim()
        return plainTextHtml(s)
    }

    private fun plainTextHtml(s: String): String {
        // Protect intentional <br>/</p> breaks with a sentinel, strip tags,
        // then collapse the source's fixed-width line wraps (raw newlines in
        // the HTML text) to single spaces per HTML whitespace semantics.
        // Stored definitions are untouched; this is display/FTS-prep only.
        val noTags = tagRe.replace(brPRe.replace(s, "\u0000"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&")
        return collapseSourceWraps(noTags).trim()
    }

    /** Bounded-cost preview for list rows: a maxLines=2 preview never needs
     *  the full definition (up to 65k chars). Processes only a prefix cut at
     *  the first tag boundary after [limit], with any partial entity trimmed.
     *  Leading output matches plainText; cost is O(limit), not O(definition). */
    fun preview(s: String, limit: Int = 2000): String {
        if (s.length <= limit) return plainText(s)
        // Long plain-text definitions (no tags): bound the input too; leading
        // output is identical because whitespace collapsing is local.
        if (!s.contains('<')) return plainText(s.substring(0, limit))
        val cut = s.indexOf('>', limit)
        // No tag closes after [limit]: processing the prefix is still safe —
        // an unclosed trailing tag strips identically in prefix and full text.
        if (cut < 0) return plainText(s.substring(0, limit))
        var prefix = s.substring(0, cut + 1)
        val amp = prefix.lastIndexOf('&')
        if (amp >= 0 && prefix.indexOf(';', amp) < 0) prefix = prefix.substring(0, amp)
        return plainTextHtml(prefix)
    }

    /** Single pass: whitespace runs -> one space; U+0000 sentinels -> newline
     *  (at most two consecutive). Whitespace class matches Regex("\\s+"). */
    private fun collapseSourceWraps(s: String): String {
        val sb = StringBuilder(s.length)
        var pendingSpace = false
        var nlRun = 0
        for (ch in s) {
            when {
                ch == '\u0000' -> {
                    if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                    if (nlRun < 2) { sb.append('\n'); nlRun++ }
                    pendingSpace = false
                }
                ch == ' ' || ch == '\t' || ch == '\n' || ch == '\u000B' || ch == '\u000C' || ch == '\r' ->
                    pendingSpace = true
                else -> {
                    if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                    pendingSpace = false
                    nlRun = 0
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }
}

@Entity(tableName = "entries", indices = [Index("wordNorm")])
data class Entry(
    @PrimaryKey val id: Long, val word: String,
    val wordNorm: String, val definition: String
)

@Entity(tableName = "entries_fts")
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, tokenizerArgs = ["remove_diacritics=1"])
data class EntryFts(
    val entryId: Long, val wordNorm: String,
    val wordCompact: String, val defNorm: String
)

@Entity(tableName = "favorites")
data class Favorite(@PrimaryKey val entryId: Long, val addedAt: Long)

@Entity(tableName = "search_history", indices = [Index("lastAt")])
data class SearchHistory(
    @PrimaryKey val queryNorm: String, val query: String,
    val entryId: Long?, val word: String?,
    val hits: Int, val count: Int, val lastAt: Long
)

data class Hit(val id: Long, val wordNorm: String)

@Dao
interface EntryDao {
    @Query("SELECT COUNT(*) FROM entries") suspend fun count(): Int
    @Query("SELECT * FROM entries WHERE id = :id") suspend fun byId(id: Long): Entry?
    @Query("SELECT * FROM entries WHERE id IN (:ids)") suspend fun byIds(ids: List<Long>): List<Entry>
    @Query("SELECT CAST(entryId AS INTEGER) AS id, wordNorm FROM entries_fts WHERE entries_fts MATCH :match LIMIT :limit")
    suspend fun fts(match: String, limit: Int): List<Hit>
    @Insert suspend fun insertEntries(items: List<Entry>)
    @Insert suspend fun insertFts(items: List<EntryFts>)
}

@Dao
interface FavoriteDao {
    @Query("SELECT e.* FROM entries e JOIN favorites f ON e.id = f.entryId ORDER BY f.addedAt DESC")
    fun observe(): Flow<List<Entry>>
    @Query("SELECT entryId FROM favorites") fun ids(): Flow<List<Long>>
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE entryId = :id)") fun isFavorite(id: Long): Flow<Boolean>
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE entryId = :id)") suspend fun isFavoriteOnce(id: Long): Boolean
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun add(f: Favorite)
    @Query("DELETE FROM favorites WHERE entryId = :id") suspend fun remove(id: Long)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM search_history ORDER BY lastAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SearchHistory>>
    @Query("""
        SELECT * FROM search_history WHERE queryNorm LIKE :prefix || '%' ESCAPE '\'
        ORDER BY count DESC, lastAt DESC LIMIT :limit
    """)
    fun suggest(prefix: String, limit: Int): Flow<List<SearchHistory>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(item: SearchHistory): Long
    @Query("""
        UPDATE search_history SET query = :query,
            entryId = COALESCE(:entryId, entryId), word = COALESCE(:word, word),
            hits = :hits, count = count + 1, lastAt = :at
        WHERE queryNorm = :key
    """)
    suspend fun touch(key: String, query: String, entryId: Long?, word: String?, hits: Int, at: Long)
    @Query("DELETE FROM search_history WHERE queryNorm NOT IN (SELECT queryNorm FROM search_history ORDER BY lastAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
    @Query("DELETE FROM search_history WHERE queryNorm = :key") suspend fun remove(key: String)
    @Query("DELETE FROM search_history") suspend fun clear()
}

@Database(entities = [Entry::class, EntryFts::class, Favorite::class, SearchHistory::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    companion object {
        fun build(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx, AppDatabase::class.java, "dehkhoda.db").build()
    }
}

class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("dehkhoda_settings", Context.MODE_PRIVATE)
    private val _historyEnabled = MutableStateFlow(sp.getBoolean("history_enabled", true))
    val historyEnabled: StateFlow<Boolean> = _historyEnabled.asStateFlow()
    fun setHistoryEnabled(v: Boolean) {
        sp.edit().putBoolean("history_enabled", v).apply(); _historyEnabled.value = v
    }
    var historyLimit: Int
        get() = sp.getInt("history_limit", 100)
        set(v) = sp.edit().putInt("history_limit", v).apply()
}

class DataImporter(private val ctx: Context, private val db: AppDatabase) {
        private fun openData(): java.io.InputStream {
        val names = ctx.assets.list("")?.toList() ?: emptyList()
        val name = names.firstOrNull { it.startsWith("dehkhoda") }
            ?: throw java.io.FileNotFoundException("data asset missing; assets: $names")
        val raw = java.io.BufferedInputStream(ctx.assets.open(name))
        raw.mark(2)
        val b0 = raw.read(); val b1 = raw.read()
        raw.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw) else raw
    }
    suspend fun import(asset: String = "dehkhoda.jsonl.gz", onProgress: (Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val dao = db.entryDao()
            val batch = 2000
            val deepIndex = true
            // Pipeline: prepare the next batch while the current one is being
            // written, overlapping CPU text processing with SQLite writes.
            // Entry order, per-batch transactions and progress semantics are
            // unchanged.
            val channel = Channel<Pair<List<Entry>, List<EntryFts>>>(capacity = 1)
            coroutineScope {
                launch {
                    var id = 0L
                    var entries = ArrayList<Entry>(batch)
                    var fts = ArrayList<EntryFts>(batch)
                    try {
                        openData().bufferedReader(Charsets.UTF_8).use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isBlank()) continue
                                val o = JSONObject(line)
                                val word = o.optString("w").trim()
                                if (word.isEmpty()) continue
                                val def = o.optString("d")
                                val norm = PersianText.normalize(word)
                                id++
                                entries.add(Entry(id, word, norm, def))
                                fts.add(EntryFts(id, norm, PersianText.compact(word),
                                    if (deepIndex) PersianText.normalize(PersianText.plainText(def)) else ""))
                                if (entries.size >= batch) {
                                    channel.send(entries to fts)
                                    entries = ArrayList(batch); fts = ArrayList(batch)
                                }
                            }
                        }
                        if (entries.isNotEmpty()) channel.send(entries to fts)
                    } finally {
                        channel.close()
                    }
                }
                for ((e, f) in channel) {
                    db.withTransaction { dao.insertEntries(e); dao.insertFts(f) }
                    onProgress(e.last().id)
                }
            }
        }
}

class DictionaryRepository(
    private val dao: EntryDao, private val fav: FavoriteDao,
    private val history: HistoryDao, private val settings: Settings
) {
    suspend fun count() = dao.count()
    suspend fun entry(id: Long) = dao.byId(id)

    suspend fun search(raw: String, deep: Boolean = false, limit: Int = 80): List<Entry> {
        val norm = PersianText.normalize(raw)
        val tokens = PersianText.tokens(raw)
        if (tokens.isEmpty()) return emptyList()
        val ordered = LinkedHashMap<Long, String>()
        val wordMatch = tokens.joinToString(" ") { "wordNorm:$it*" }
        dao.fts(wordMatch, limit)
            .sortedWith(compareByDescending<Hit> { it.wordNorm == norm }.thenBy { it.wordNorm.length })
            .forEach { ordered[it.id] = it.wordNorm }
        val compact = PersianText.compact(raw)
        if (ordered.size < limit && compact != norm && compact.isNotEmpty())
            dao.fts("wordCompact:$compact*", limit).forEach { ordered.putIfAbsent(it.id, it.wordNorm) }
        if (deep && ordered.size < limit) {
            val defMatch = tokens.joinToString(" ") { "defNorm:$it*" }
            dao.fts(defMatch, limit).forEach { ordered.putIfAbsent(it.id, it.wordNorm) }
        }
        if (ordered.isEmpty()) return emptyList()
        val map = dao.byIds(ordered.keys.toList()).associateBy { it.id }
        return ordered.keys.mapNotNull { map[it] }
    }

    fun favorites(): Flow<List<Entry>> = fav.observe()
    fun favoriteIds(): Flow<List<Long>> = fav.ids()
    fun isFavorite(id: Long): Flow<Boolean> = fav.isFavorite(id)
    suspend fun toggleFavorite(id: Long) {
        if (fav.isFavoriteOnce(id)) fav.remove(id) else fav.add(Favorite(id, System.currentTimeMillis()))
    }

    fun history(limit: Int = 40): Flow<List<SearchHistory>> = history.recent(limit)
    fun historySuggestions(raw: String, limit: Int = 5): Flow<List<SearchHistory>> {
        val norm = PersianText.normalize(raw)
        if (norm.length < 2) return flowOf(emptyList())
        val esc = norm.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return history.suggest(esc, limit + 1).map { l -> l.filter { it.queryNorm != norm }.take(limit) }
    }
    suspend fun recordSearch(query: String, entryId: Long? = null, word: String? = null, hits: Int = 0) {
        if (!settings.historyEnabled.value) return
        val norm = PersianText.normalize(query)
        if (norm.length < 2) return
        val now = System.currentTimeMillis(); val clean = query.trim()
        val row = SearchHistory(norm, clean, entryId, word, hits, 1, now)
        if (history.insertIgnore(row) == -1L) history.touch(norm, clean, entryId, word, hits, now)
        history.trim(settings.historyLimit)
    }
    suspend fun removeHistory(key: String) = history.remove(key)
    suspend fun clearHistory() = history.clear()
}

class AppContainer(ctx: Context) {
    val db by lazy { AppDatabase.build(ctx) }
    val settings by lazy { Settings(ctx) }
    val repo by lazy { DictionaryRepository(db.entryDao(), db.favoriteDao(), db.historyDao(), settings) }
    private val importer by lazy { DataImporter(ctx, db) }
    val ready = MutableStateFlow(false)
    val imported = MutableStateFlow(0L)
    val error = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)
    fun ensureData() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                if (repo.count() > 0) { ready.value = true; return@launch }
                importer.import { imported.value = it }
                ready.value = true
            } catch (t: Throwable) {
                error.value = t.message ?: "خطا در آماده‌سازی داده"; started.set(false)
            }
        }
    }
}

class DehkhodaApp : Application() {
    lateinit var container: AppContainer
    override fun onCreate() { super.onCreate(); container = AppContainer(this) }
}
