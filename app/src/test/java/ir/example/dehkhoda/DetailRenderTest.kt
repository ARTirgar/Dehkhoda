package ir.example.dehkhoda

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Phase 1 visual verification: renders the real DetailScreen with real
 * dataset entries under Robolectric native graphics and saves PNGs for
 * inspection (RTL + justified Persian definitions).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = DehkhodaApp::class, qualifiers = "w400dp-h800dp-xhdpi")
class DetailRenderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun renderDetailScreens() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>() as DehkhodaApp
        val dao = app.container.db.entryDao()

        // Pick real entries from the shipped asset: one common word and one
        // long multi-paragraph definition (justification needs many lines).
        var short: Entry? = null
        var long: Entry? = null
        var lazyMulti: Entry? = null
        var lazySingle: Entry? = null
        openDataAsset(app).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val o = org.json.JSONObject(line)
                val w = o.optString("w").trim(); val d = o.optString("d")
                if (w.isEmpty()) continue
                if (short == null && w == "آب") short = Entry(1, w, PersianText.normalize(w), d)
                if (long == null && d.length > 1500 && d.length < 4000)
                    long = Entry(2, w, PersianText.normalize(w), d)
                if (d.length > 6000 && (lazyMulti == null || lazySingle == null)) {
                    val p = PersianText.plainText(d)
                    if (lazyMulti == null && p.length > 4000 && p.contains('\n'))
                        lazyMulti = Entry(3, w, PersianText.normalize(w), d)
                    if (lazySingle == null && p.length > 8000 && !p.contains('\n'))
                        lazySingle = Entry(4, w, PersianText.normalize(w), d)
                }
                if (short != null && long != null && lazyMulti != null && lazySingle != null) break
            }
        }
        check(short != null && long != null && lazyMulti != null && lazySingle != null) {
            "sample entries not found"
        }
        dao.insertEntries(listOf(short!!, long!!, lazyMulti!!, lazySingle!!))

        for ((id, name) in listOf(
            1L to "render-detail-short.png", 2L to "render-detail-long.png",
            3L to "render-detail-lazy-multi.png", 4L to "render-detail-lazy-single.png"
        )) {
            val vm = DetailViewModel(app, SavedStateHandle(mapOf("id" to id)))
            rule.activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    DehkhodaTheme(dark = false) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            LocalLayoutDirection provides LayoutDirection.Rtl
                        ) { DetailScreen(vm) {} }
                    }
                }
            }
            rule.waitForIdle()
            Thread.sleep(1500) // allow the entry flow (IO) to emit + recompose
            rule.waitForIdle()
            // Robolectric's window-capture redraw wait times out; draw the
            // content view directly into a bitmap instead (native graphics).
            val f = File("build/$name")
            f.parentFile.mkdirs()
            rule.activityRule.scenario.onActivity { activity ->
                val root = activity.findViewById<android.view.View>(android.R.id.content)
                val bmp = android.graphics.Bitmap.createBitmap(
                    root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                root.draw(android.graphics.Canvas(bmp))
                FileOutputStream(f).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                println("saved ${f.absolutePath} (${bmp.width}x${bmp.height})")
            }
        }
    }
}
