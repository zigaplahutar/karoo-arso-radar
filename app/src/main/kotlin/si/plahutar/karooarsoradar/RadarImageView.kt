package si.plahutar.karooarsoradar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Radarska slika s premikanjem in povecevanjem s prstom.
 *
 * Uporablja se SAMO na zaslonu aplikacije. Podatkovno polje med voznjo se izrisuje
 * v procesu Karoo OS prek RemoteViews, kjer poteg prsta ni na voljo - tam so gumbi.
 *
 * Poteg prsta = premik, dvojni tap = priblizaj/oddalji, dva prsta = scipanje
 * (ce zaslon zazna dva prsta; ce ne, ostane vse ostalo uporabno).
 */
class RadarImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        const val MAX_ZOOM_FACTOR = 16f
        const val DOUBLE_TAP_ZOOM = 4f
    }

    private var bitmap: Bitmap? = null
    private var marker: ArsoGeo.Pixel? = null

    private var scale = 1f
    private var minScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var layoutDone = false

    private val drawMatrix = Matrix()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 30, 30)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val markerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 30, 30)
        style = Paint.Style.FILL
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            offsetX -= distanceX
            offsetY -= distanceY
            clamp()
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > minScale * 1.2f) {
                fitToView()
            } else {
                zoomAt(e.x, e.y, DOUBLE_TAP_ZOOM)
            }
            invalidate()
            return true
        }
    })

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAt(detector.focusX, detector.focusY, detector.scaleFactor)
            invalidate()
            return true
        }
    })

    /** Nova slicica. Ce se velikost ni spremenila, ohranimo trenutni pogled. */
    fun setImage(newBitmap: Bitmap?, newMarker: ArsoGeo.Pixel?) {
        val sizeChanged = newBitmap?.width != bitmap?.width || newBitmap?.height != bitmap?.height
        bitmap = newBitmap
        marker = newMarker
        if (sizeChanged || !layoutDone) fitToView()
        invalidate()
    }

    /** Gumba - in + na zaslonu aplikacije. Povecujemo okoli lokacije, ce jo poznamo. */
    fun zoomBy(factor: Float) {
        val anchorX: Float
        val anchorY: Float
        val currentMarker = marker
        if (currentMarker != null) {
            anchorX = currentMarker.x * scale + offsetX
            anchorY = currentMarker.y * scale + offsetY
        } else {
            anchorX = width / 2f
            anchorY = height / 2f
        }
        zoomAt(anchorX, anchorY, factor)
        invalidate()
    }

    /** Postavi pogled na kolesarja, ce lokacijo poznamo. */
    fun centerOnMarker() {
        val currentMarker = marker ?: return
        offsetX = width / 2f - currentMarker.x * scale
        offsetY = height / 2f - currentMarker.y * scale
        clamp()
        invalidate()
    }

    fun resetView() {
        fitToView()
        invalidate()
    }

    private fun zoomAt(focusX: Float, focusY: Float, factor: Float) {
        val target = (scale * factor).coerceIn(minScale, minScale * MAX_ZOOM_FACTOR)
        val applied = target / scale
        offsetX = focusX - (focusX - offsetX) * applied
        offsetY = focusY - (focusY - offsetY) * applied
        scale = target
        clamp()
    }

    private fun fitToView() {
        val current = bitmap ?: return
        if (width == 0 || height == 0) return
        minScale = min(width.toFloat() / current.width, height.toFloat() / current.height)
        scale = minScale
        offsetX = (width - current.width * scale) / 2f
        offsetY = (height - current.height * scale) / 2f
        layoutDone = true
    }

    /** Slike ne pustimo odvleci s zaslona. */
    private fun clamp() {
        val current = bitmap ?: return
        val scaledWidth = current.width * scale
        val scaledHeight = current.height * scale
        offsetX = if (scaledWidth <= width) {
            (width - scaledWidth) / 2f
        } else {
            offsetX.coerceIn(width - scaledWidth, 0f)
        }
        offsetY = if (scaledHeight <= height) {
            (height - scaledHeight) / 2f
        } else {
            offsetY.coerceIn(height - scaledHeight, 0f)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val current = bitmap ?: return

        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(offsetX, offsetY)
        canvas.drawBitmap(current, drawMatrix, bitmapPaint)

        marker?.let {
            val x = it.x * scale + offsetX
            val y = it.y * scale + offsetY
            if (x in 0f..width.toFloat() && y in 0f..height.toFloat()) {
                canvas.drawCircle(x, y, 9f, haloPaint)
                canvas.drawCircle(x, y, 7f, markerPaint)
                canvas.drawCircle(x, y, 2.5f, markerDotPaint)
            }
        }
    }
}
