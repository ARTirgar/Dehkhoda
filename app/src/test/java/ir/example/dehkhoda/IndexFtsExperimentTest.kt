package ir.example.dehkhoda

import android.app.Application
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/**
 * Controlled A/B experiments on temporary tables (app schema untouched),
 * first 100,000 real entries, same values, same 2000-row transactions:
 *  1. entries insert WITH vs WITHOUT the wordNorm secondary index
 *  2. FTS4 vs FTS5 insert speed + MATCH result-set equivalence
 * Sample-based evidence for schema proposals; not a full-import number.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DehkhodaApp::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class IndexFtsExperimentTest {

    private data class Row(val id: Long, val word: String, val norm: String,
                           val compact: String, val def: String, val defNorm: String)

    @Test
    fun experiments() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>() as DehkhodaApp
        val db = app.container.db
        val sdb = db.openHelper.writableDatabase
        val log = StringBuilder()
        fun log(s: String) { println(s); log.append(s).append('\n') }

        val rows = ArrayList<Row>(100_000)
        openDataAsset(app).use { reader ->
            var id = 0L
            while (rows.size < 100_000) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val o = JSONObject(line)
                val w = o.optString("w").trim()
                if (w.isEmpty()) continue
                val d = o.optString("d")
                id++
                rows.add(Row(id, w, PersianText.normalize(w), PersianText.compact(w),
                    d, PersianText.normalize(PersianText.plainText(d))))
            }
        }
        log("sample rows: ${rows.size}")
        val batch = 2000

        // ---- Experiment 1: wordNorm index cost on entries inserts ----
        sdb.execSQL("CREATE TABLE exp_a(id INTEGER PRIMARY KEY, word TEXT, wordNorm TEXT, definition TEXT)")
        sdb.execSQL("CREATE INDEX exp_a_wordNorm ON exp_a(wordNorm)")
        sdb.execSQL("CREATE TABLE exp_b(id INTEGER PRIMARY KEY, word TEXT, wordNorm TEXT, definition TEXT)")
        suspend fun insertPlain(table: String): Long {
            val stmt = sdb.compileStatement("INSERT INTO $table(id, word, wordNorm, definition) VALUES (?,?,?,?)")
            val t0 = System.nanoTime()
            rows.chunked(batch).forEach { chunk ->
                db.withTransaction {
                    for (r in chunk) {
                        stmt.bindLong(1, r.id); stmt.bindString(2, r.word)
                        stmt.bindString(3, r.norm); stmt.bindString(4, r.def)
                        stmt.executeInsert(); stmt.clearBindings()
                    }
                }
            }
            return (System.nanoTime() - t0) / 1_000_000
        }
        val withIdx = insertPlain("exp_a")
        val withoutIdx = insertPlain("exp_b")
        log("EXP1 entries insert 100k: WITH index $withIdx ms | WITHOUT index $withoutIdx ms | index cost ${withIdx - withoutIdx} ms (${(withIdx - withoutIdx) * 100 / withIdx}%)")

        // ---- Experiment 2: FTS4 vs FTS5 ----
        sdb.execSQL("CREATE VIRTUAL TABLE exp_fts4 USING fts4(entryId, wordNorm, wordCompact, defNorm, tokenize=unicode61 \"remove_diacritics=1\")")
        sdb.execSQL("CREATE VIRTUAL TABLE exp_fts5 USING fts5(entryId, wordNorm, wordCompact, defNorm, tokenize=\"unicode61 remove_diacritics 2\")")
        suspend fun insertFts(table: String): Long {
            val stmt = sdb.compileStatement("INSERT INTO $table(entryId, wordNorm, wordCompact, defNorm) VALUES (?,?,?,?)")
            val t0 = System.nanoTime()
            rows.chunked(batch).forEach { chunk ->
                db.withTransaction {
                    for (r in chunk) {
                        stmt.bindLong(1, r.id); stmt.bindString(2, r.norm)
                        stmt.bindString(3, r.compact); stmt.bindString(4, r.defNorm)
                        stmt.executeInsert(); stmt.clearBindings()
                    }
                }
            }
            return (System.nanoTime() - t0) / 1_000_000
        }
        val fts4ms = insertFts("exp_fts4")
        val fts5ms = insertFts("exp_fts5")
        log("EXP2 FTS insert 100k: FTS4 $fts4ms ms | FTS5 $fts5ms ms | delta ${fts4ms - fts5ms} ms (${(fts4ms - fts5ms) * 100 / fts4ms}%)")

        // MATCH equivalence on the sample (FTS5 uses 'col : expr' syntax)
        val queries = listOf("آب" to "wordNorm", "ده" to "wordNorm", "کتاب" to "wordNorm",
            "رستاخیز" to "defNorm", "فروریختن" to "defNorm")
        for ((term, col) in queries) {
            fun ids(table: String, expr: String): List<Long> {
                val out = ArrayList<Long>()
                sdb.query("SELECT CAST(entryId AS INTEGER) FROM $table WHERE $table MATCH ?", arrayOf(expr)).use { c ->
                    while (c.moveToNext()) out.add(c.getLong(0))
                }
                return out.sorted()
            }
            val a = ids("exp_fts4", "$col:$term*")
            val b = ids("exp_fts5", "$col : $term*")
            log("EXP2 MATCH '$col:$term*': fts4 ${a.size} hits, fts5 ${b.size} hits, identical=${a == b}")
        }

        sdb.execSQL("DROP TABLE exp_a"); sdb.execSQL("DROP TABLE exp_b")
        sdb.execSQL("DROP TABLE exp_fts4"); sdb.execSQL("DROP TABLE exp_fts5")
        File("build/experiment-results.txt").apply { parentFile.mkdirs() }.writeText(log.toString())
    }
}
