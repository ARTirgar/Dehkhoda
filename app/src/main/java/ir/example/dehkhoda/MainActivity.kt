package ir.example.dehkhoda

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = (application as DehkhodaApp).container
        setContent {
            DehkhodaTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    LaunchedEffect(Unit) { c.ensureData() }
                    val ready by c.ready.collectAsStateWithLifecycle()
                    val count by c.imported.collectAsStateWithLifecycle()
                    val error by c.error.collectAsStateWithLifecycle()
                    if (ready) AppNav()
                    else ImportScreen(count, error) { c.error.value = null; c.ensureData() }
                }
            }
        }
    }
}
