package si.plahutar.karooarsoradar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Iz radarske slike naredi tisto, kar gre na zaslon: izrez okoli kolesarja,
 * pomanjsanje na velikost polja in marker lokacije.
 *
 * Vse v enem prehodu, ker bitmap potuje cez procesno mejo do Karoo OS in ga
 * ne posiljamo vecjega, kot ga polje potrebuje.
 */
object RadarRenderer {

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 30, 30)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val markerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 30, 30)
        style = Paint.Style.FILL
    }

    /**
     * @param source cela radarska slika
     * @param targetWidth,targetHeight prostor, ki ga imamo na voljo
     * @param zoom 1 = cela slika, vec = izrez okoli [center]
     * @param center piksel, okoli katerega rezemo (kolesar); null = sredina slike
     * @param drawMarker ali na [center] narisemo oznako lokacije
     */
    fun render(
        source: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        zoom: Float,
        center: ArsoGeo.Pixel?,
        drawMarker: Boolean,
    ): Bitmap {
        val fieldWidth = targetWidth.coerceAtLeast(64)
        val fieldHeight = targetHeight.coerceAtLeast(64)

        val srcRect = cropRect(source, fieldWidth, fieldHeight, zoom, center)

        // Izhodna slika ohrani razmerje izreza in se prilega polju.
        val scale = min(
            fieldWidth.toFloat() / srcRect.width(),
            fieldHeight.toFloat() / srcRect.height(),
        )
        val outWidth = (srcRect.width() * scale).roundToInt().coerceAtLeast(1)
        val outHeight = (srcRect.height() * scale).roundToInt().coerceAtLeast(1)

        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(output)
        canvas.drawBitmap(source, srcRect, RectF(0f, 0f, outWidth.toFloat(), outHeight.toFloat()), bitmapPaint)

        if (drawMarker && center != null &&
            center.x >= srcRect.left && center.x <= srcRect.right &&
            center.y >= srcRect.top && center.y <= srcRect.bottom
        ) {
            val mx = (center.x - srcRect.left) * outWidth / srcRect.width()
            val my = (center.y - srcRect.top) * outHeight / srcRect.height()
            // Bela podlaga, da je marker viden tudi na temnem dezju.
            canvas.drawCircle(mx, my, 7f, haloPaint)
            canvas.drawCircle(mx, my, 5.5f, markerPaint)
            canvas.drawCircle(mx, my, 1.8f, markerDotPaint)
        }

        return output
    }

    /**
     * Izrez z razmerjem stranic polja - tako slika zapolni polje in ni crnih pasov.
     * Pri zoomu 1 vrnemo celo sliko (pregled), pri vecjem oknu izrez okoli kolesarja.
     */
    private fun cropRect(
        source: Bitmap,
        fieldWidth: Int,
        fieldHeight: Int,
        zoom: Float,
        center: ArsoGeo.Pixel?,
    ): Rect {
        if (zoom <= 1f) return Rect(0, 0, source.width, source.height)

        var cropWidth = source.width / zoom
        var cropHeight = cropWidth * fieldHeight / fieldWidth
        if (cropHeight > source.height) {
            cropHeight = source.height.toFloat()
            cropWidth = cropHeight * fieldWidth / fieldHeight
        }
        if (cropWidth > source.width) {
            cropWidth = source.width.toFloat()
            cropHeight = cropWidth * fieldHeight / fieldWidth
        }

        val cx = center?.x ?: (source.width / 2f)
        val cy = center?.y ?: (source.height / 2f)

        var left = cx - cropWidth / 2f
        var top = cy - cropHeight / 2f
        left = max(0f, min(left, source.width - cropWidth))
        top = max(0f, min(top, source.height - cropHeight))

        return Rect(
            left.roundToInt(),
            top.roundToInt(),
            (left + cropWidth).roundToInt().coerceAtMost(source.width),
            (top + cropHeight).roundToInt().coerceAtMost(source.height),
        )
    }
}
