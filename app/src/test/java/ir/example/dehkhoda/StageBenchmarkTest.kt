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
 * Instrumented replica of DataImporter.import (same operations, same batch
 * size, same DAO calls, both entries + entries_fts tables) with per-stage
 * timers: accumulate (read/JSON/normalize/FTS-prep), insertEntries,
 * insertFts, and transaction commit. Runs on a fresh Robolectric DB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DehkhodaApp::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StageBenchmarkTest {

    @Test
    fun stageBreakdown() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>() as DehkhodaApp
        val db = app.container.db
        val dao = db.entryDao()

        val batch = 2000 // identical to DataImporter
        val entries = ArrayList<Entry>(batch)
        val fts = ArrayList<EntryFts>(batch)
        var id = 0L
        var accNs = 0L; var readNs = 0L; var jsonNs = 0L; var wordNs = 0L; var defNs = 0L
        var insENs = 0L; var insFNs = 0L; var txNs = 0L; var commits = 0

        suspend fun flush() {
            if (entries.isEmpty()) return
            val t0 = System.nanoTime()
            db.withTransaction {
                val t1 = System.nanoTime()
                dao.insertEntries(entries)
                val t2 = System.nanoTime()
                dao.insertFts(fts)
                val t3 = System.nanoTime()
                insENs += t2 - t1; insFNs += t3 - t2
            }
            txNs += System.nanoTime() - t0
            commits++
            entries.clear(); fts.clear()
        }

        val total0 = System.nanoTime()
        openDataAsset(app).use { reader ->
            while (true) {
                val a0 = System.nanoTime()
                val r0 = System.nanoTime()
                val line = reader.readLine() ?: break
                readNs += System.nanoTime() - r0
                if (line.isBlank()) continue
                val p0 = System.nanoTime()
                val o = JSONObject(line)
                val word = o.optString("w").trim()
                if (word.isEmpty()) continue
                val def = o.optString("d")
                jsonNs += System.nanoTime() - p0
                val w0 = System.nanoTime()
                val norm = PersianText.normalize(word)
                val compact = PersianText.compact(word)
                wordNs += System.nanoTime() - w0
                val d0 = System.nanoTime()
                val defNorm = PersianText.normalize(PersianText.plainText(def))
                defNs += System.nanoTime() - d0
                id++
                entries += Entry(id, word, norm, def)
                fts += EntryFts(id, norm, compact, defNorm)
                accNs += System.nanoTime() - a0
                if (entries.size >= batch) flush()
            }
            flush()
        }
        val totalMs = (System.nanoTime() - total0) / 1_000_000
        val count = dao.count()
        val commitMs = (txNs - insENs - insFNs) / 1_000_000

        val log = buildString {
            appendLine("STAGE BREAKDOWN (instrumented replica of DataImporter, batch=$batch)")
            appendLine("total: $totalMs ms, entries: $count, transactions: $commits")
            appendLine("accumulate (read+json+norm+fts-prep): ${accNs / 1_000_000} ms")
            appendLine("  read: ${readNs / 1_000_000} ms")
            appendLine("  json: ${jsonNs / 1_000_000} ms")
            appendLine("  wordNorm+compact: ${wordNs / 1_000_000} ms")
            appendLine("  defProcess (plainText+normalize = FTS prep): ${defNs / 1_000_000} ms")
            appendLine("insertEntries: ${insENs / 1_000_000} ms")
            appendLine("insertFts: ${insFNs / 1_000_000} ms")
            appendLine("transaction commit overhead: $commitMs ms")
            appendLine("tx total: ${txNs / 1_000_000} ms")
        }
        println(log)
        check(count == 343316) { "integrity: expected 343316, got $count" }
        File("build/stage-results.txt").apply { parentFile.mkdirs() }.writeText(log)
        File("build/stage-results.json").writeText(
            """{"totalMs":$totalMs,"count":$count,"transactions":$commits,"batch":$batch,""" +
            """"accumulateMs":${accNs / 1_000_000},"readMs":${readNs / 1_000_000},""" +
            """"jsonMs":${jsonNs / 1_000_000},"wordNormMs":${wordNs / 1_000_000},""" +
            """"defProcessMs":${defNs / 1_000_000},"insertEntriesMs":${insENs / 1_000_000},""" +
            """"insertFtsMs":${insFNs / 1_000_000},"commitMs":$commitMs}""")
    }
}
