package si.plahutar.karooarsoradar

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Graficno podatkovno polje z radarsko sliko in stirimi gumbi: - + play osvezi.
 *
 * Karoo poklice [startView], ko je polje na zaslonu, in preklice, ko ni vec.
 * Ob preklicu ustavimo animacijo - ko strani ne gledas, se ne dogaja nic.
 */
class RadarDataType(extension: String) : DataTypeImpl(extension, "radar") {

    private companion object {
        const val TAG = "ArsoRadar"

        /**
         * Obrez robov slike v delezih sirine/visine, ce hoces vreci stran okolico.
         * Privzeto pokazemo celo sliko.
         */
        const val CROP_LEFT = 0.0f
        const val CROP_TOP = 0.0f
        const val CROP_RIGHT = 0.0f
        const val CROP_BOTTOM = 0.0f

        /** Priblizen delez visine polja, ki ostane sliki (ostalo vzamejo gumbi). */
        const val IMAGE_HEIGHT_RATIO = 0.78f
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "startView, velikost polja ${config.viewSize}, predogled=${config.preview}")
        val scope = CoroutineScope(Dispatchers.IO)

        val configJob = scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(ShowCustomStreamState("", null))
            awaitCancellation()
        }

        // Prvi prikaz: sliko potegnemo enkrat. Potem samo se na gumb.
        if (!config.preview && !RadarRepository.hasImage()) {
            scope.launch { RadarRepository.refresh() }
        }

        val viewJob = scope.launch {
            RadarRepository.state.collect { state ->
                emitter.updateView(render(context, config, state))
            }
        }

        emitter.setCancellable {
            Log.d(TAG, "stopView")
            RadarRepository.stopPlay()
            configJob.cancel()
            viewJob.cancel()
        }
    }

    private fun render(
        context: Context,
        config: ViewConfig,
        state: RadarRepository.State,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.radar_field)
        val bitmap = state.frame?.let { fitToField(it, config, state.zoom) }

        if (bitmap != null) {
            views.setImageViewBitmap(R.id.radar_image, bitmap)
            views.setViewVisibility(R.id.radar_image, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.radar_image, View.GONE)
        }

        val message = when {
            state.loading && bitmap == null -> context.getString(R.string.loading)
            bitmap == null -> context.getString(R.string.no_connection)
            else -> null
        }
        if (message != null) {
            views.setTextViewText(R.id.radar_status, message)
            views.setViewVisibility(R.id.radar_status, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.radar_status, View.GONE)
        }

        // Napis v kotu: med animacijo stevec slicic, sicer cas zadnjega prenosa.
        val caption = when {
            bitmap == null -> null
            state.playing && state.frameCount > 0 -> "${state.frameIndex}/${state.frameCount}"
            state.loading -> "…"
            state.fetchedAtMs != null -> {
                val zoomLabel = if (state.zoom > 1f) "  ${state.zoom}x" else ""
                timeFormat.format(Date(state.fetchedAtMs)) +
                    (if (state.failed) " !" else "") + zoomLabel
            }
            else -> null
        }
        if (caption != null) {
            views.setTextViewText(R.id.radar_caption, caption)
            views.setViewVisibility(R.id.radar_caption, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.radar_caption, View.GONE)
        }

        views.setTextViewText(R.id.btn_play, if (state.playing) "■" else "▶")

        // V predogledu (urejanje profila) gumbi ne smejo delovati.
        if (!config.preview) {
            views.setOnClickPendingIntent(
                R.id.btn_zoom_out,
                RadarCommandReceiver.pendingIntent(context, RadarCommandReceiver.CMD_ZOOM_OUT),
            )
            views.setOnClickPendingIntent(
                R.id.btn_zoom_in,
                RadarCommandReceiver.pendingIntent(context, RadarCommandReceiver.CMD_ZOOM_IN),
            )
            views.setOnClickPendingIntent(
                R.id.btn_play,
                RadarCommandReceiver.pendingIntent(context, RadarCommandReceiver.CMD_PLAY),
            )
            views.setOnClickPendingIntent(
                R.id.btn_refresh,
                RadarCommandReceiver.pendingIntent(context, RadarCommandReceiver.CMD_REFRESH),
            )
        }

        return views
    }

    /**
     * Obreze (fiksni obrez robov + zoom na sredino) in pomanjsa sliko na velikost polja.
     * Pomembno: bitmap potuje cez procesno mejo do Karoo OS, zato ga ne posiljamo
     * vecjega, kot ga polje potrebuje.
     */
    private fun fitToField(source: Bitmap, config: ViewConfig, zoom: Float): Bitmap {
        var working = source

        // 1. fiksni obrez robov
        val edgeWidth = (source.width * (1f - CROP_LEFT - CROP_RIGHT)).roundToInt()
        val edgeHeight = (source.height * (1f - CROP_TOP - CROP_BOTTOM)).roundToInt()
        if (edgeWidth in 1 until source.width || edgeHeight in 1 until source.height) {
            working = Bitmap.createBitmap(
                working,
                (source.width * CROP_LEFT).roundToInt(),
                (source.height * CROP_TOP).roundToInt(),
                edgeWidth,
                edgeHeight,
            )
        }

        // 2. zoom - izrez na sredini slike
        if (zoom > 1f) {
            val zoomWidth = (working.width / zoom).roundToInt().coerceAtLeast(1)
            val zoomHeight = (working.height / zoom).roundToInt().coerceAtLeast(1)
            working = Bitmap.createBitmap(
                working,
                (working.width - zoomWidth) / 2,
                (working.height - zoomHeight) / 2,
                zoomWidth,
                zoomHeight,
            )
        }

        // 3. pomanjsanje na velikost polja
        val targetWidth = config.viewSize.first.takeIf { it > 0 } ?: 400
        val targetHeight = ((config.viewSize.second.takeIf { it > 0 } ?: 400) * IMAGE_HEIGHT_RATIO)
            .roundToInt().coerceAtLeast(1)
        val scale = min(
            targetWidth.toFloat() / working.width,
            targetHeight.toFloat() / working.height,
        )
        if (scale >= 1f) return working

        return Bitmap.createScaledBitmap(
            working,
            (working.width * scale).roundToInt().coerceAtLeast(1),
            (working.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }
}
