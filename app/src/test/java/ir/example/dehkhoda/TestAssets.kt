package ir.example.dehkhoda

import android.app.Application
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.util.zip.GZIPInputStream

/** Opens the shipped dictionary asset the same way DataImporter does. */
fun openDataAsset(app: Application): BufferedReader {
    val name = app.assets.list("")!!.first { it.startsWith("dehkhoda") }
    val raw = BufferedInputStream(app.assets.open(name))
    raw.mark(2)
    val b0 = raw.read(); val b1 = raw.read()
    raw.reset()
    val input = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw) else raw
    return input.bufferedReader(Charsets.UTF_8)
}
