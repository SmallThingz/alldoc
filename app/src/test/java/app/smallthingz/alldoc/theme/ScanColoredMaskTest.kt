package app.smallthingz.alldoc.theme

import org.junit.Assert.*
import org.junit.Test

class ScanColoredMaskTest {
    @Test fun isolatedBlueGlyphAndItsAntialiasingAreSelectedWithoutSelectingThePhoto() {
        val width = 256
        val height = 192
        val pixels = IntArray(width * height) { -1 }
        for (y in 61 until 71) for (x in 19 until 29)
            pixels[y * width + x] = DocumentColors.blend(-1, 0xff235d75.toInt(),
                if (y in 62 until 70 && x in 20 until 28) 255 else 128)
        for (y in 96 until 160) for (x in 96 until 224)
            pixels[y * width + x] = 0xff000000.toInt() or (0x204080 + ((x + y) and 15))
        // Pale lettering and gray details inside a continuous photo are not page ink.
        for (y in 110 until 114) for (x in 110 until 114) pixels[y * width + x] = -1
        for (y in 125 until 129) for (x in 125 until 129) pixels[y * width + x] = 0xff888888.toInt()
        val original = pixels.copyOf()
        var rows = 0
        val mask = requireNotNull(ScanPaperMask.detect(width, height, { y, row ->
            assertEquals(rows++, y)
            pixels.copyInto(row, 0, y * width, (y + 1) * width)
        }))
        assertEquals("No extra bitmap reads for colored components", height, rows)
        for (y in 0 until height) for (x in 0 until width)
            assertEquals("paper, glyph or photo at $x,$y", !(x in 96 until 224 && y in 96 until 160), mask.contains(x, y))
        assertArrayEquals(original, pixels)
    }
}
