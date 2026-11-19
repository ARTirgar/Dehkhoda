package ir.example.dehkhoda

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Scroll-jank workload measurements (JVM proxy — CPU + allocation evidence,
 * NOT device frame timing). Quantifies the text-processing work the UI
 * performs per composition event on both screens, using real definitions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = DehkhodaApp::class)
class ScrollWorkloadTest {

    // java.lang.management is absent from android.jar (compile classpath)
    // but present on the Robolectric JVM — reach it via reflection.
    private val threadMxBean: Any? = try {
        Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getThreadMXBean").invoke(null)
    } catch (e: Exception) { null }

    private fun allocBytes(): Long {
        val b = threadMxBean ?: return 0L
        return try {
            // invoke through the exported interface (impl class is module-private)
            val m = Class.forName("com.sun.management.ThreadMXBean")
                .getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
            m.invoke(b, Thread.currentThread().id) as Long
        } catch (e: Exception) { 0L }
    }

    private fun gcCount(): Long = try {
        val beans = Class.forName("java.lang.management.ManagementFactory")
            .getMethod("getGarbageCollectorMXBeans").invoke(null) as List<*>
        val iface = Class.forName("java.lang.management.GarbageCollectorMXBean")
        val m = iface.getMethod("getCollectionCount")
        beans.sumOf { m.invoke(it) as Long }
    } catch (e: Exception) { 0L }

    @Test
    fun scrollWorkloads() {
        val app = ApplicationProvider.getApplicationContext<Application>() as DehkhodaApp
        val log = StringBuilder()
        fun log(s: String) { println(s); log.append(s).append('\n') }

        // ---- Stream the real dataset: cost distribution + samples ----
        val longest = ArrayList<Pair<String, String>>(30)   // (word, definition)
        val typical = ArrayList<Pair<String, String>>(400)  // evenly spaced sample
        var n = 0L; var totalNs = 0L; var totalBytes = 0L
        var maxLen = 0; var sumLen = 0L
        val bucketNs = LongArray(8); val bucketN = LongArray(8)
        fun bucket(len: Int): Int = when {
            len < 500 -> 0; len < 1000 -> 1; len < 2000 -> 2; len < 5000 -> 3
            len < 10000 -> 4; len < 20000 -> 5; len < 50000 -> 6; else -> 7
        }
        val a0 = allocBytes()
        openDataAsset(app).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val o = JSONObject(line)
                val w = o.optString("w").trim()
                if (w.isEmpty()) continue
                val d = o.optString("d")
                n++; sumLen += d.length; if (d.length > maxLen) maxLen = d.length
                val t0 = System.nanoTime()
                PersianText.plainText(d)
                val dt = System.nanoTime() - t0
                totalNs += dt
                bucketNs[bucket(d.length)] += dt; bucketN[bucket(d.length)]++
                if (longest.size < 30 || d.length > longest.last().second.length) {
                    longest.add(w to d); longest.sortByDescending { it.second.length }
                    if (longest.size > 30) longest.removeAt(30)
                }
                if (n % 858 == 0L && typical.size < 400) typical.add(w to d)
            }
        }
        totalBytes = allocBytes() - a0
        log("plainText over ALL $n real definitions: total ${totalNs / 1_000_000} ms, " +
            "avg ${totalNs / n / 1000.0} us/call, alloc ${totalBytes / n} B/call")
        log("definition length: avg ${sumLen / n} chars, max $maxLen chars")
        val labels = listOf("<500", "500-1k", "1k-2k", "2k-5k", "5k-10k", "10k-20k", "20k-50k", ">50k")
        for (i in labels.indices) if (bucketN[i] > 0)
            log("  bucket ${labels[i]}: ${bucketN[i]} defs, avg ${bucketNs[i] / bucketN[i] / 1000.0} us/call")

        // ---- Word-list screen: work per composition event ----
        // S1: 3 new rows entering composition during a scroll step
        //     (current code: full plainText per row, no caching).
        val s1rows = typical.take(3)
        repeat(3) { s1rows.forEach { PersianText.plainText(it.second) } } // warmup
        var t0 = System.nanoTime(); var b0 = allocBytes(); val g0 = gcCount()
        repeat(50) { s1rows.forEach { PersianText.plainText(it.second) } }
        val s1us = (System.nanoTime() - t0) / 1000.0 / 50
        val s1bytes = (allocBytes() - b0) / 50
        log("LIST S1 (3 new rows/scroll step): $s1us us, $s1bytes B allocated, GCs ${gcCount() - g0}")

        // S2: full visible window (10 rows) recomposed (parent state change,
        //     e.g. favorites update — rows are not skippable in practice).
        val window = typical.take(10)
        repeat(3) { window.forEach { PersianText.plainText(it.second) } }
        t0 = System.nanoTime(); b0 = allocBytes()
        repeat(30) { window.forEach { PersianText.plainText(it.second) } }
        val s2us = (System.nanoTime() - t0) / 1000.0 / 30
        val s2bytes = (allocBytes() - b0) / 30
        log("LIST S2 (10-row window recomposition): $s2us us, $s2bytes B allocated")

        // S3: worst-case visible rows (long definitions)
        val longRows = longest.take(3)
        t0 = System.nanoTime(); b0 = allocBytes()
        repeat(10) { longRows.forEach { PersianText.plainText(it.second) } }
        val s3us = (System.nanoTime() - t0) / 1000.0 / 10
        val s3bytes = (allocBytes() - b0) / 10
        log("LIST S3 (3 LONG rows ${longRows.map { it.second.length }} chars): $s3us us, $s3bytes B")

        // ---- Detail screen: work per recomposition (fav toggle etc.) ----
        val (lw, ld) = longest[0]
        PersianText.plainText(ld)
        t0 = System.nanoTime(); b0 = allocBytes()
        repeat(20) { PersianText.plainText(ld) }
        val dUs = (System.nanoTime() - t0) / 1000.0 / 20
        val dBytes = (allocBytes() - b0) / 20
        log("DETAIL recomposition (longest entry '$lw', ${ld.length} chars): $dUs us, $dBytes B per recomposition")

        // Preview-truncation candidate: cost of PersianText.preview (bounded
        // prefix) vs full definition — the actual implementation under test.
        t0 = System.nanoTime()
        repeat(50) { typical.take(3).forEach { PersianText.preview(it.second) } }
        val pUs = (System.nanoTime() - t0) / 1000.0 / 50
        log("LIST S1 with PersianText.preview: $pUs us (vs $s1us us full)")
        t0 = System.nanoTime(); b0 = allocBytes()
        repeat(10) { longRows.forEach { PersianText.preview(it.second) } }
        val pLongUs = (System.nanoTime() - t0) / 1000.0 / 10
        val pLongBytes = (allocBytes() - b0) / 10
        log("LIST S3 with PersianText.preview: $pLongUs us, $pLongBytes B (vs $s3us us, $s3bytes B full)")

        // Correctness: preview must not change the first 200 output chars.
        // Check EVERY real entry (full dataset) + the 30 longest.
        var mismatch = 0; var checked = 0
        openDataAsset(app).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val o = JSONObject(line)
                val w = o.optString("w").trim()
                if (w.isEmpty()) continue
                val d = o.optString("d")
                checked++
                if (PersianText.plainText(d).take(200) != PersianText.preview(d).take(200)) {
                    mismatch++; if (mismatch <= 3) log("  preview mismatch: $w")
                }
            }
        }
        for ((w, d) in longest) {
            if (PersianText.plainText(d).take(200) != PersianText.preview(d).take(200)) {
                mismatch++; log("  preview mismatch (longest): $w")
            }
        }
        log("preview correctness: $mismatch mismatches over $checked + ${longest.size} entries (first 200 chars)")

        // detailBlocks content preservation: with all whitespace removed, the
        // blocks must reconstruct the original plainText exactly.
        var blockMismatch = 0; var blockChecked = 0
        val ws = Regex("\\s+")
        for ((w, d) in longest + typical) {
            val full = PersianText.plainText(d)
            if (full.length <= 4000) continue
            blockChecked++
            val joined = detailBlocks(full).joinToString("") { it.text }
            if (ws.replace(full, "") != ws.replace(joined, "")) {
                blockMismatch++; if (blockMismatch <= 3) log("  block mismatch: $w")
            }
        }
        log("detailBlocks content preservation: $blockMismatch mismatches over $blockChecked long entries")

        File("build/scroll-workload.txt").apply { parentFile.mkdirs() }.writeText(log.toString())
    }
}
