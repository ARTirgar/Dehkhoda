package ir.example.dehkhoda

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/**
 * Phase 2 baseline measurement harness (local JVM via Robolectric).
 *
 * Measures the REAL import pipeline (real asset, real org.json parsing,
 * real PersianText processing, real Room/SQLite inserts) and search
 * latency, and writes machine-readable results to build/benchmark-*.json
 * for before/after comparison. JVM numbers are indicative of algorithmic
 * cost, not identical to a physical device (different CPU/IO).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DehkhodaApp::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ImportSearchBenchmarkTest {

    private fun resultsFile(name: String) = File("build/$name").apply { parentFile.mkdirs() }

    @Test
    fun importAndSearchBaseline() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>() as DehkhodaApp
        val c = app.container
        val db = c.db
        val dao = db.entryDao()
        val out = StringBuilder()
        fun log(s: String) { println(s); out.append(s).append('\n') }

        val assets = app.assets.list("")!!.toList()
        log("assets: $assets")
        check(assets.any { it.startsWith("dehkhoda") }) { "data asset missing" }

        // ---- 1. Full import, real DataImporter ----
        System.gc()
        val t0 = System.nanoTime()
        val milestones = ArrayList<Pair<Long, Long>>() // (entries, elapsedMs)
        DataImporter(app, db).import { n ->
            if (n % 50000 == 0L) milestones.add(n to (System.nanoTime() - t0) / 1_000_000)
        }
        val importMs = (System.nanoTime() - t0) / 1_000_000
        val count = dao.count()
        log("IMPORT: $importMs ms for $count entries")
        milestones.forEach { (n, ms) -> log("  progress: $n entries @ $ms ms") }
        check(count == 343316) { "integrity: expected 343316 entries, got $count" }

        // Ordering spot-check: ids must follow source order (pipelining must
        // not reorder entries).
        val probes = listOf(1L, 50000L, 100000L, 200000L, 343316L)
        val sourceWords = HashMap<Long, String>()
        openDataAsset(app).use { reader ->
            var i = 0L
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val o = JSONObject(line)
                val w = o.optString("w").trim()
                if (w.isEmpty()) continue
                i++
                if (i in probes) sourceWords[i] = w
            }
        }
        for (id in probes) {
            val e = dao.byId(id)
            check(e != null && e.word == sourceWords[id]) { "ordering mismatch at id $id" }
        }
        log("ordering spot-check: ${probes.size} ids match source order")

        // ---- 2. CPU pipeline pass WITHOUT DB (read+parse+normalize+plainText) ----
        val sampleDefs = ArrayList<String>(5000)
        val t1 = System.nanoTime()
        var n = 0L
        var parseNs = 0L; var wordNs = 0L; var defNs = 0L; var readNs = 0L
        openDataAsset(app).use { reader ->
            while (true) {
                val r0 = System.nanoTime()
                val line = reader.readLine() ?: break
                readNs += System.nanoTime() - r0
                if (line.isBlank()) continue
                val p0 = System.nanoTime()
                val o = JSONObject(line)
                val word = o.optString("w").trim()
                val def = o.optString("d")
                parseNs += System.nanoTime() - p0
                if (word.isEmpty()) continue
                val w0 = System.nanoTime()
                PersianText.normalize(word)
                PersianText.compact(word)
                wordNs += System.nanoTime() - w0
                val d0 = System.nanoTime()
                PersianText.normalize(PersianText.plainText(def))
                defNs += System.nanoTime() - d0
                if (sampleDefs.size < 5000 && def.length > 200) sampleDefs.add(def)
                n++
            }
        }
        val pipelineMs = (System.nanoTime() - t1) / 1_000_000
        log("PIPELINE(no DB): $pipelineMs ms for $n entries")
        log("  read: ${readNs / 1_000_000} ms  json: ${parseNs / 1_000_000} ms  wordNorm: ${wordNs / 1_000_000} ms  defProcess: ${defNs / 1_000_000} ms")
        log("  derived DB overhead (import - pipeline): ${importMs - pipelineMs} ms (approximate)")

        // ---- 3. Microbenchmarks: regex compilation vs reuse ----
        val patterns = listOf("\\s+", "<br\\s*/?>", "</p>", "<[^>]+>", "\\n{3,}", "[^\\p{L}\\p{N}]+")
        val reps = 20_000
        val t2 = System.nanoTime()
        repeat(reps) { for (p in patterns) Regex(p) }
        val compileNs = (System.nanoTime() - t2) / reps
        val pre = patterns.map { Regex(it) }
        val t3 = System.nanoTime()
        repeat(reps) { for (p in pre) p.replace("sample text", " ") }
        val reuseNs = (System.nanoTime() - t3) / reps
        log("REGEX: compile-all-per-call ${compileNs} ns/call vs precompiled-reuse ${reuseNs} ns/call (x${compileNs / reuseNs.coerceAtLeast(1)})")

        // plainText throughput on real definitions (current implementation)
        val t4 = System.nanoTime()
        repeat(3) { for (d in sampleDefs) PersianText.plainText(d) }
        val plainUs = (System.nanoTime() - t4) / 1000.0 / (3 * sampleDefs.size)
        log("plainText: $plainUs us/definition over ${sampleDefs.size} real samples")

        // ---- 4. Search latency + accuracy fingerprint ----
        val repo = c.repo
        data class Q(val q: String, val deep: Boolean)
        val queries = listOf(
            Q("آب", false), Q("دهخدا", false), Q("مهر", false), Q("جلو", false),
            Q("اب", false), Q("ده", false), Q("کتاب", false),
            Q("دایره بستن", false), Q("تهمیم", false),
            Q("رستاخیز", true), Q("فروریختن", true)
        )
        val searchJson = StringBuilder("[")
        for ((q, deep) in queries) {
            val times = ArrayList<Long>()
            var ids: List<Long> = emptyList()
            repeat(7) { i ->
                val s0 = System.nanoTime()
                val r = repo.search(q, deep)
                val ms = (System.nanoTime() - s0) / 1_000_000
                if (i >= 2) times.add(ms) // drop 2 warmups
                ids = r.map { it.id }
            }
            times.sort()
            val med = times[times.size / 2]
            log("SEARCH '$q' deep=$deep: median $med ms, ${ids.size} hits")
            if (searchJson.length > 1) searchJson.append(',')
            searchJson.append("{\"q\":${JSONObject.quote(q)},\"deep\":$deep,\"medianMs\":$med,\"ids\":$ids}")
        }
        searchJson.append(']')

        // ---- 5. Persist results ----
        val json = """{"importMs":$importMs,"count":$count,"pipelineNoDbMs":$pipelineMs,""" +
            """"readMs":${readNs / 1_000_000},"jsonMs":${parseNs / 1_000_000},""" +
            """"wordNormMs":${wordNs / 1_000_000},"defProcessMs":${defNs / 1_000_000},""" +
            """"regexCompileNsPerCall":$compileNs,"regexReuseNsPerCall":$reuseNs,""" +
            """"plainTextUsPerDef":$plainUs,"search":$searchJson}"""
        resultsFile("benchmark-results.json").writeText(json)
        resultsFile("benchmark-log.txt").writeText(out.toString())
        log("results written to build/benchmark-results.json")
    }
}
