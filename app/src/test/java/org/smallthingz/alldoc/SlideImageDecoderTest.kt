package org.smallthingz.alldoc

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SlideImageDecoderTest {
    @Test fun samplingKeepsTheDecodedPixelBudget() {
        assertEquals(8,slideRasterSample(8192,8192,1_048_576))
        assertEquals(1,slideRasterSample(200,100,40_000))
        assertThrows(IllegalArgumentException::class.java) { slideRasterSample(100_000,100,100) }
    }
    @Test fun pngPreviewIsSampledBeforePixelAllocation() {
        val source = Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.BLUE)
        val output = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.PNG,100,output)
        source.recycle()
        val result = decodeSlideRaster(output.toByteArray(),65_536)
        assertTrue(result.width.toLong()*result.height <=65_536)
        assertEquals(android.graphics.Color.BLUE,result.getPixel(0,0))
        result.recycle()
    }
    @Test fun markupAndInvalidPayloadsNeverReachRasterAllocation() {
        assertThrows(IllegalArgumentException::class.java) { decodeSlideRaster("<svg><script>bad()</script></svg>".toByteArray(),1000) }
        assertThrows(IllegalArgumentException::class.java) { decodeSlideRaster(ByteArray(MAX_SLIDE_IMAGE_BYTES+1),1000) }
        assertThrows(IllegalArgumentException::class.java) { decodeSlideRaster(byteArrayOf(1,2,3),1000) }
    }
}
