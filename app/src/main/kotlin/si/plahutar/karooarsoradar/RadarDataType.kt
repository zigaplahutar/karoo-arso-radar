package si.plahutar.karooarsoradar

import android.content.Context
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

        /** Priblizen delez visine polja, ki ostane sliki (ostalo vzamejo gumbi). */
        const val IMAGE_HEIGHT_RATIO = 0.76f
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

        val source = state.frame
        val marker = source?.let { bitmap ->
            state.location?.let { ArsoGeo.toPixel(it.lat, it.lng, bitmap.width, bitmap.height) }
        }

        val bitmap = source?.let {
            RadarRenderer.render(
                source = it,
                targetWidth = config.viewSize.first.takeIf { w -> w > 0 } ?: 400,
                targetHeight = ((config.viewSize.second.takeIf { h -> h > 0 } ?: 400) * IMAGE_HEIGHT_RATIO)
                    .roundToInt(),
                zoom = state.zoom,
                center = marker,
                drawMarker = true,
            )
        }

        if (bitmap != null) {
            views.setImageViewBitmap(R.id.radar_image, bitmap)
            views.setViewVisibility(R.id.radar_image, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.radar_image, View.GONE)
        }

        val message = when {
            bitmap == null && state.progress != null -> state.progress
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

        val caption = when {
            bitmap == null -> null
            state.playing && state.frameCount > 0 -> "${state.frameIndex}/${state.frameCount}"
            state.progress != null -> state.progress
            else -> buildString {
                append(state.fetchedAtMs?.let { timeFormat.format(Date(it)) } ?: "-")
                if (state.failed) append(" !")
                if (state.zoom > 1f) append("  ${state.zoom.roundToInt()}x")
                // Ce smo povecali, pa markerja ni, uporabnik mora vedeti zakaj.
                if (state.zoom > 1f && marker == null) append("  brez GPS")
            }
        }
        if (caption != null) {
            views.setTextViewText(R.id.radar_caption, caption)
            views.setViewVisibility(R.id.radar_caption, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.radar_caption, View.GONE)
        }

        views.setTextViewText(R.id.btn_play, if (state.playing) "■" else "▶")

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
}
