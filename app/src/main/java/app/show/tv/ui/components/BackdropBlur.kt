package app.show.tv.ui.components

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/**
 * 1.5.3: the hero backdrop's blur, done once per image on Coil's decode thread and cached, instead of
 * a 40 dp RenderEffect that the GPU re-ran every frame (and that Android 8–11 never had).
 *
 * The logo is shrunk to ~48 px, box-blurred three times (≈ Gaussian), then scaled back up with
 * bilinear filtering to [OUT_LONG_EDGE] px so the final on-screen upscale stays small and smooth.
 * (Drawing the tiny bitmap straight to full width showed blocky pixels on some devices.)
 */
class BackdropBlur(private val radius: Int = 3) : Transformation {
    override val cacheKey: String = "show-backdrop-blur-v1-$radius"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = blur(input, radius)

    companion object {
        const val SMALL_LONG_EDGE = 48
        const val OUT_LONG_EDGE = 384

        fun blur(input: Bitmap, radius: Int = 3): Bitmap {
            val src = if (input.config == Bitmap.Config.HARDWARE) input.copy(Bitmap.Config.ARGB_8888, false) else input
            val scale = SMALL_LONG_EDGE.toFloat() / maxOf(src.width, src.height)
            val w = maxOf(2, (src.width * scale).toInt())
            val h = maxOf(2, (src.height * scale).toInt())
            val small = Bitmap.createScaledBitmap(src, w, h, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            repeat(3) { boxPass(px, w, h, radius, horizontal = true); boxPass(px, w, h, radius, horizontal = false) }
            val blurred = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            val outScale = OUT_LONG_EDGE.toFloat() / maxOf(w, h)
            return Bitmap.createScaledBitmap(blurred, (w * outScale).toInt(), (h * outScale).toInt(), true)
        }

        /** One separable box-blur pass over ARGB pixels (edges clamped). */
        internal fun boxPass(px: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val len = if (horizontal) w else h
            val lines = if (horizontal) h else w
            val line = IntArray(len)
            val span = 2 * r + 1
            for (l in 0 until lines) {
                for (i in 0 until len) line[i] = px[if (horizontal) l * w + i else i * w + l]
                for (i in 0 until len) {
                    var a = 0; var rr = 0; var g = 0; var b = 0
                    for (k in -r..r) {
                        val c = line[(i + k).coerceIn(0, len - 1)]
                        a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                    }
                    px[if (horizontal) l * w + i else i * w + l] =
                        ((a / span) shl 24) or ((rr / span) shl 16) or ((g / span) shl 8) or (b / span)
                }
            }
        }
    }
}
