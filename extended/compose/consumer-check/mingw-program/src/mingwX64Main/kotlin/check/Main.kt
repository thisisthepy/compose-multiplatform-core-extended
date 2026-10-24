package check

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.system.exitProcess

// Two checks from one file with nothing beside it.
//
// Drawing: an ImageBitmap is a Skia bitmap, so a red rectangle read back as red has Compose's
// graphics, skiko's C++ half and Skia linked in.
//
// Korean text: Compose's text layout shapes 한 and 글 through Skia's paragraph and ICU, whose
// data has to be compiled into the program (there is no icudtl.dat on the machine). Each
// syllable is drawn into its own bitmap: both have to ink pixels, and differently, which a
// missing font or failed shaping (nothing drawn, or the same empty box twice) does not.
fun main() {
    val bitmap = ImageBitmap(8, 8)
    Canvas(bitmap).drawRect(Rect(0f, 0f, 8f, 8f), Paint().apply { color = Color.Red })
    val pixels = IntArray(64)
    bitmap.readPixels(pixels)
    val red = Color.Red.toArgb()
    if (!pixels.all { it == red }) fail("drew red, read back ${pixels.distinct().joinToString { it.toUInt().toString(16) }}")
    println("draw ok: 64 pixels of ${red.toUInt().toString(16)} drawn by Skia")

    val measurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
    val inked = listOf("한", "글").map { syllable ->
        val layout = measurer.measure(syllable, TextStyle(color = Color.Black, fontSize = 48.sp))
        val glyph = ImageBitmap(64, 64)
        val canvas = Canvas(glyph)
        canvas.drawRect(Rect(0f, 0f, 64f, 64f), Paint().apply { color = Color.White })
        TextPainter.paint(canvas, layout)
        val out = IntArray(64 * 64)
        glyph.readPixels(out)
        val count = out.count { it != Color.White.toArgb() }
        println("$syllable: ${layout.size.width}x${layout.size.height}, $count inked pixels")
        if (count < 40) fail("$syllable inked $count pixels; Korean text did not shape or draw")
        out
    }
    if (inked[0].contentEquals(inked[1])) fail("한 and 글 drew the same pixels, so neither is its own glyph")
    println("mingw program ok: Skia draws, and Compose shapes Korean with the ICU data compiled in")
}

private fun fail(message: String): Nothing {
    println("mingw program FAILED: $message")
    exitProcess(1)
}
