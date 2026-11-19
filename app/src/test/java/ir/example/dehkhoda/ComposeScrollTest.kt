package ir.example.dehkhoda

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Real-Compose scroll/recomposition work measurements under Robolectric.
 * Wall time per scroll step = composition + layout CPU work (Robolectric
 * has no vsync/GPU pipeline, so this is NOT frame timing — it quantifies
 * the work that must fit into a frame budget on a device).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = DehkhodaApp::class, qualifiers = "w400dp-h800dp-xhdpi")
class ComposeScrollTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun sampleEntries(): List<Entry> {
        val app = ApplicationProvider.getApplicationContext<Application>() as DehkhodaApp
        val out = ArrayList<Entry>()
        var longest: Entry? = null
        var id = 0L
        openDataAsset(app).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val o = JSONObject(line)
                val w = o.optString("w").trim()
                if (w.isEmpty()) continue
                val d = o.optString("d")
                id++
                if (id % 858 == 0L && out.size < 397)
                    out.add(Entry(id, w, PersianText.normalize(w), d))
                if (longest == null || d.length > longest!!.definition.length)
                    longest = Entry(id, w, PersianText.normalize(w), d)
            }
        }
        // sprinkle the longest definitions into the list (worst case rows)
        out.add(50, longest!!)
        out.add(150, longest!!)
        out.add(250, longest!!)
        return out
    }

    @Test
    fun listScrollWork() {
        val entries = sampleEntries()
        var listState: androidx.compose.foundation.lazy.LazyListState? = null
        val t0 = System.nanoTime()
        rule.activityRule.scenario.onActivity { a ->
            a.setContent {
                DehkhodaTheme(dark = false) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        val ls = rememberLazyListState()
                        listState = ls
                        LazyColumn(state = ls, modifier = Modifier.fillMaxSize()) {
                            items(entries, key = { it.id }) { e ->
                                EntryRow(e, false, {}, {})
                                HorizontalDivider(thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val initialMs = (System.nanoTime() - t0) / 1_000_000
        val times = ArrayList<Long>()
        for (target in 5..380 step 5) {
            val s0 = System.nanoTime()
            rule.activityRule.scenario.onActivity { a ->
                a.lifecycleScope.launch { listState!!.scrollToItem(target) }
            }
            rule.waitForIdle()
            times.add((System.nanoTime() - s0) / 1_000_000)
        }
        times.sort()
        val log = "LIST initial composition: $initialMs ms\n" +
            "LIST scroll steps (5 items each, ${times.size} steps): " +
            "median ${times[times.size / 2]} ms, p90 ${times[times.size * 9 / 10]} ms, max ${times.last()} ms\n" +
            "steps over 16ms: ${times.count { it > 16 }}/${times.size}\n"
        println(log)
        File("build/scroll-compose-list.txt").apply { parentFile.mkdirs() }.writeText(log)
    }

    /** Harness-overhead control: identical LazyColumn geometry, but rows are
     *  a single short Text (no EntryRow, no preview, no icon). The delta
     *  between this and listScrollWork is the app's added per-step work;
     *  this floor is Robolectric composition/layout machinery, not app jank. */
    @Test
    fun listScrollControl() {
        val entries = sampleEntries()
        var listState: androidx.compose.foundation.lazy.LazyListState? = null
        rule.activityRule.scenario.onActivity { a ->
            a.setContent {
                DehkhodaTheme(dark = false) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        val ls = rememberLazyListState()
                        listState = ls
                        LazyColumn(state = ls, modifier = Modifier.fillMaxSize()) {
                            items(entries, key = { it.id }) { e ->
                                androidx.compose.material3.Text(e.word)
                                HorizontalDivider(thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val times = ArrayList<Long>()
        for (target in 5..380 step 5) {
            val s0 = System.nanoTime()
            rule.activityRule.scenario.onActivity { a ->
                a.lifecycleScope.launch { listState!!.scrollToItem(target) }
            }
            rule.waitForIdle()
            times.add((System.nanoTime() - s0) / 1_000_000)
        }
        times.sort()
        val log = "CONTROL (word-only rows) scroll steps: median ${times[times.size / 2]} ms, " +
            "p90 ${times[times.size * 9 / 10]} ms, max ${times.last()} ms; " +
            "over 16ms: ${times.count { it > 16 }}/${times.size}\n"
        println(log)
        File("build/scroll-compose-control.txt").apply { parentFile.mkdirs() }.writeText(log)
    }

    /** Decomposition control: word + preview Texts (same as EntryRow) but no
     *  Row/IconButton chrome. Delta vs listScrollWork = icon/row overhead;
     *  delta vs listScrollControl = preview text layout cost. */
    @Test
    fun listScrollNoIcon() {
        val entries = sampleEntries()
        var listState: androidx.compose.foundation.lazy.LazyListState? = null
        rule.activityRule.scenario.onActivity { a ->
            a.setContent {
                DehkhodaTheme(dark = false) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        val ls = rememberLazyListState()
                        listState = ls
                        LazyColumn(state = ls, modifier = Modifier.fillMaxSize()) {
                            items(entries, key = { it.id }) { e ->
                                androidx.compose.foundation.layout.Column {
                                    androidx.compose.material3.Text(
                                        e.word, style = MaterialTheme.typography.titleMedium)
                                    androidx.compose.material3.Text(
                                        remember(e.id) { PersianText.preview(e.definition) },
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                HorizontalDivider(thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val times = ArrayList<Long>()
        for (target in 5..380 step 5) {
            val s0 = System.nanoTime()
            rule.activityRule.scenario.onActivity { a ->
                a.lifecycleScope.launch { listState!!.scrollToItem(target) }
            }
            rule.waitForIdle()
            times.add((System.nanoTime() - s0) / 1_000_000)
        }
        times.sort()
        val log = "NOICON (word+preview texts) scroll steps: median ${times[times.size / 2]} ms, " +
            "p90 ${times[times.size * 9 / 10]} ms, max ${times.last()} ms; " +
            "over 16ms: ${times.count { it > 16 }}/${times.size}\n"
        println(log)
        File("build/scroll-compose-noicon.txt").apply { parentFile.mkdirs() }.writeText(log)
    }

    @Test
    fun detailRecompositionWork() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>() as DehkhodaApp
        // find the longest definition and insert it as entry id 1
        var longest: Entry? = null
        openDataAsset(app).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val o = JSONObject(line)
                val w = o.optString("w").trim()
                if (w.isEmpty()) continue
                val d = o.optString("d")
                if (longest == null || d.length > longest!!.definition.length)
                    longest = Entry(1, w, PersianText.normalize(w), d)
            }
        }
        app.container.db.entryDao().insertEntries(listOf(longest!!))
        val vm = DetailViewModel(app, SavedStateHandle(mapOf("id" to 1L)))
        val t0 = System.nanoTime()
        rule.activityRule.scenario.onActivity { a ->
            a.setContent {
                DehkhodaTheme(dark = false) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        DetailScreen(vm) {}
                    }
                }
            }
        }
        rule.waitForIdle()
        // real-time wait for the IO flow to emit (test-clock waitUntil races ahead)
        val deadline = System.currentTimeMillis() + 15_000
        while (vm.entry.value == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50); rule.waitForIdle()
        }
        check(vm.entry.value != null) { "entry flow did not emit" }
        rule.waitForIdle()
        val initialMs = (System.nanoTime() - t0) / 1_000_000
        val times = ArrayList<Long>()
        for (i in 1..10) {
            val before = vm.isFavorite.value
            val s0 = System.nanoTime()
            rule.runOnUiThread { vm.toggleFavorite() }
            val dl = System.currentTimeMillis() + 10_000
            while (vm.isFavorite.value == before && System.currentTimeMillis() < dl) {
                Thread.sleep(10); rule.waitForIdle()
            }
            rule.waitForIdle()
            times.add((System.nanoTime() - s0) / 1_000_000)
        }
        times.sort()
        val log = "DETAIL ('${longest!!.word}', ${longest!!.definition.length} chars) initial: $initialMs ms\n" +
            "DETAIL fav-toggle recompositions: median ${times[times.size / 2]} ms, max ${times.last()} ms\n"
        println(log)
        File("build/scroll-compose-detail.txt").apply { parentFile.mkdirs() }.writeText(log)
    }

    /** Detail-screen SCROLL work (the user-reported jank): real swipe gestures
     *  through the Compose input pipeline + full-screen software draw timing.
     *  Draw time = real CPU glyph/layout replay work (Robolectric NATIVE
     *  graphics); still NOT device frame timing (no vsync/GPU). */
    @Test
    fun detailScrollWork() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>() as DehkhodaApp
        var longest: Entry? = null
        var medium: Entry? = null
        var single: Entry? = null
        openDataAsset(app).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val o = JSONObject(line)
                val w = o.optString("w").trim()
                if (w.isEmpty()) continue
                val d = o.optString("d")
                if (longest == null || d.length > longest!!.definition.length)
                    longest = Entry(1, w, PersianText.normalize(w), d)
                if (medium == null && d.length in 1800..2200)
                    medium = Entry(2, w, PersianText.normalize(w), d)
                if (single == null && d.length > 9000) {
                    val p = PersianText.plainText(d)
                    if (p.length > 8000 && !p.contains('\n'))
                        single = Entry(3, w, PersianText.normalize(w), d)
                }
            }
        }
        check(single != null) { "no single-block long entry found" }
        app.container.db.entryDao().insertEntries(listOf(longest!!, medium!!, single!!))
        val log = StringBuilder()
        for ((eid, label, entry) in listOf(
            Triple(1L, "LONGEST", longest!!), Triple(2L, "MEDIUM", medium!!),
            Triple(3L, "SINGLEBLOCK", single!!)
        )) {
            val vm = DetailViewModel(app, SavedStateHandle(mapOf("id" to eid)))
            val t0 = System.nanoTime()
            rule.activityRule.scenario.onActivity { a ->
                a.setContent {
                    DehkhodaTheme(dark = false) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                            DetailScreen(vm) {}
                        }
                    }
                }
            }
            rule.waitForIdle()
            val deadline = System.currentTimeMillis() + 15_000
            while (vm.entry.value == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(50); rule.waitForIdle()
            }
            check(vm.entry.value != null) { "entry flow did not emit ($label)" }
            rule.waitForIdle()
            val initialMs = (System.nanoTime() - t0) / 1_000_000
            val content = rule.activity.findViewById<android.view.View>(android.R.id.content)
            check(content.width > 0 && content.height > 0) { "content not laid out" }
            val bmp = android.graphics.Bitmap.createBitmap(
                content.width, content.height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            fun drawMs(): Long {
                val s = System.nanoTime(); content.draw(canvas)
                return (System.nanoTime() - s) / 1_000_000
            }
            val firstDraw = drawMs()
            val swipeTimes = ArrayList<Long>(); val drawTimes = ArrayList<Long>()
            repeat(8) {
                val s0 = System.nanoTime()
                rule.onRoot().performTouchInput {
                    swipeUp(startY = height * 0.8f, endY = height * 0.2f, durationMillis = 300)
                }
                rule.waitForIdle()
                swipeTimes.add((System.nanoTime() - s0) / 1_000_000)
                drawTimes.add(drawMs())
            }
            swipeTimes.sort(); drawTimes.sort()
            log.append("DETAIL-SCROLL $label ('${entry.word}', ${entry.definition.length} chars): ")
                .append("initial $initialMs ms, first full-screen draw $firstDraw ms\n")
                .append("  swipe+idle: median ${swipeTimes[4]} ms, max ${swipeTimes.last()} ms")
                .append(" | draw after scroll: median ${drawTimes[4]} ms, max ${drawTimes.last()} ms\n")
        }
        println(log)
        File("build/scroll-compose-detail-scroll.txt").apply { parentFile.mkdirs() }
            .writeText(log.toString())
    }
}
