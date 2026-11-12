package id.rancangan.mlai.capture

import android.media.Image
import id.rancangan.mlai.vision.FrameView

/**
 * [FrameView] over a MediaProjection [Image]'s first plane — zero-copy:
 * [Perception] samples only the small regions it needs (three skill
 * discs + a centre window), so no full-frame Bitmap is ever built.
 *
 * Must be used only while the image is open (inside the frame callback);
 * the caller closes the image right after.
 */
class BufferFrameView(image: Image) : FrameView {

    private val buffer = image.planes[0].buffer
    private val pixelStride = image.planes[0].pixelStride
    private val rowStride = image.planes[0].rowStride

    override val width: Int = image.width
    override val height: Int = image.height

    override fun rgb(x: Int, y: Int): Int {
        val idx = y * rowStride + x * pixelStride
        if (idx < 0 || idx + 2 >= buffer.capacity()) return 0
        val r = buffer.get(idx).toInt() and 0xFF
        val g = buffer.get(idx + 1).toInt() and 0xFF
        val b = buffer.get(idx + 2).toInt() and 0xFF
        return (r shl 16) or (g shl 8) or b
    }
}
