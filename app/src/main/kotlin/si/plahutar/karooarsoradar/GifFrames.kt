package si.plahutar.karooarsoradar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder

/**
 * ARSO objavlja animacijo zadnjih 90 minut. Nas zanima ZADNJA sliko v animaciji,
 * ker je najnovejsa. Android sam iz animiranega GIF-a zna dekodirati samo prvo sliko,
 * zato uporabimo samostojni dekoder iz Glide.
 */
object GifFrames {

    private const val TAG = "ArsoRadar"

    /** Zadnja (najnovejsa) slika animacije; ce dekodiranje ne uspe, poskusi navadno sliko. */
    fun lastFrame(data: ByteArray): Bitmap? =
        decodeLastAnimationFrame(data) ?: runCatching {
            BitmapFactory.decodeByteArray(data, 0, data.size)
        }.getOrNull()

    private fun decodeLastAnimationFrame(data: ByteArray): Bitmap? = try {
        val header = GifHeaderParser().setData(data).parseHeader()
        val frameCount = header.numFrames
        if (frameCount <= 0) {
            null
        } else {
            val decoder = StandardGifDecoder(BitmapProvider)
            decoder.setData(header, data)
            var result: Bitmap? = null
            for (index in 0 until frameCount) {
                decoder.advance()
                val frame = decoder.nextFrame
                // Dekoder si isti bitmap sposoja naprej, zato zadnjega prekopiramo.
                if (index == frameCount - 1 && frame != null) {
                    result = frame.copy(Bitmap.Config.RGB_565, false)
                }
            }
            result
        }
    } catch (throwable: Throwable) {
        Log.w(TAG, "Dekodiranje GIF animacije ni uspelo", throwable)
        null
    }

    /**
     * Najbolj preprosta implementacija - brez recikliranja, ker sicer tvegamo,
     * da nam dekoder pod roko sprosti sliko, ki jo se rabimo.
     */
    private object BitmapProvider : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
            Bitmap.createBitmap(width, height, config)

        override fun release(bitmap: Bitmap) = Unit

        override fun obtainByteArray(size: Int): ByteArray = ByteArray(size)

        override fun release(bytes: ByteArray) = Unit

        override fun obtainIntArray(size: Int): IntArray = IntArray(size)

        override fun release(array: IntArray) = Unit
    }
}
