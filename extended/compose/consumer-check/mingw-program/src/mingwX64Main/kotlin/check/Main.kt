package check

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Rect

// Compose's graphics on Skia: an ImageBitmap is a Skia bitmap, and drawing into it runs skiko's
// C++ half and Skia, so a program that prints the right pixel has all three linked in.
fun main() {
    val bitmap = ImageBitmap(8, 8)
    Canvas(bitmap).drawRect(Rect(0f, 0f, 8f, 8f), Paint().apply { color = Color.Red })
    val pixels = IntArray(64)
    bitmap.readPixels(pixels)
    val expected = Color.Red.toArgb()
    check(pixels.all { it == expected }) {
        "drew red, read back ${pixels.distinct().joinToString { it.toUInt().toString(16) }}"
    }
    println("mingw program ok: 64 pixels of ${expected.toUInt().toString(16)} drawn by Skia")
}
