package si.plahutar.karooarsoradar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder

/**
 * ARSO objavlja animacijo zadnjih 90 minut v enem samem GIF-u. Android sam iz
 * animiranega GIF-a dekodira samo prvo slicico (torej 90 minut staro), zato
 * uporabimo samostojni dekoder iz Glide.
 */
object GifFrames {

    private const val TAG = "ArsoRadar"

    /** Zadnja (najnovejsa) slicica; ce dekodiranje ne uspe, poskusi navadno sliko. */
    fun lastFrame(data: ByteArray): Bitmap? =
        decodeLastAnimationFrame(data) ?: runCatching {
            BitmapFactory.decodeByteArray(data, 0, data.size)
        }.getOrNull()

    /**
     * Prevrti animacijo od zacetka do konca in za vsako slicico poklice [onFrame]
     * z (slika, zaporedna stevilka, skupno stevilo). Dekodiranje je zaporedno,
     * zato v pomnilniku nikoli ni vec kot ena slicica.
     */
    suspend fun forEachFrame(data: ByteArray, onFrame: suspend (Bitmap, Int, Int) -> Unit) {
        try {
            val header = GifHeaderParser().setData(data).parseHeader()
            val frameCount = header.numFrames
            if (frameCount <= 0) return

            val decoder = StandardGifDecoder(BitmapProvider)
            decoder.setData(header, data)
            for (index in 0 until frameCount) {
                decoder.advance()
                val frame = decoder.nextFrame ?: continue
                // Dekoder si isti bitmap sposoja naprej, zato ga prekopiramo.
                onFrame(frame.copy(Bitmap.Config.RGB_565, false), index, frameCount)
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "Predvajanje animacije ni uspelo", throwable)
        }
    }

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
