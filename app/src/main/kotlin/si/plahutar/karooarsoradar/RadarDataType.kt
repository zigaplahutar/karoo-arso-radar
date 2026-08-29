package si.plahutar.karooarsoradar

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Graficno podatkovno polje z zadnjo radarsko sliko.
 *
 * Karoo poklice [startView], ko je polje na zaslonu, in preklice, ko ni vec -
 * prenosi tako sami od sebe ugasnejo, ko strani ne gledas.
 */
class RadarDataType(
    private val karooSystem: KarooSystemService,
    extension: String,
) : DataTypeImpl(extension, "radar") {

    private companion object {
        const val TAG = "ArsoRadar"

        /** Kako pogosto preverimo, ali je cas za novo sliko (pogostost prenosa omeji RadarRepository). */
        const val TICK_MS = 20_000L

        /**
         * Obrez slike v delezih sirine/visine, ce hoces vreci stran robove
         * (npr. legendo ali napis). Privzeto pokazemo celo sliko - najprej poglej,
         * kako izgleda na napravi, sele potem rezi.
         */
        const val CROP_LEFT = 0.0f
        const val CROP_TOP = 0.0f
        const val CROP_RIGHT = 0.0f
        const val CROP_BOTTOM = 0.0f
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        Log.d(TAG, "startView, velikost polja ${config.viewSize}, predogled=${config.preview}")
        val scope = CoroutineScope(Dispatchers.IO)

        // Brez glave (ikona + ime polja) in brez standardnega besedila o stanju -
        // sliko hocemo cez celo polje.
        val configJob = scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(ShowCustomStreamState("", null))
            awaitCancellation()
        }

        val refreshJob = scope.launch {
            while (isActive) {
                RadarRepository.refresh(karooSystem)
                delay(TICK_MS)
            }
        }

        val viewJob = scope.launch {
            RadarRepository.state.collect { state ->
                emitter.updateView(render(context, config, state))
            }
        }

        emitter.setCancellable {
            Log.d(TAG, "stopView")
            configJob.cancel()
            refreshJob.cancel()
            viewJob.cancel()
        }
    }

    private fun render(
        context: Context,
        config: ViewConfig,
        state: RadarRepository.State,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.radar_field)
        val bitmap = state.bitmap?.let { fitToField(it, config) }

        if (bitmap != null) {
            views.setImageViewBitmap(R.id.radar_image, bitmap)
            views.setViewVisibility(R.id.radar_image, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.radar_image, View.GONE)
        }

        val message = when {
            bitmap != null -> null
            state.loading -> context.getString(R.string.loading)
            else -> context.getString(R.string.no_connection)
        }
        if (message != null) {
            views.setTextViewText(R.id.radar_status, message)
            views.setViewVisibility(R.id.radar_status, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.radar_status, View.GONE)
        }

        // Cas zadnjega uspesnega prenosa; ce zadnji poskus ni uspel, dodamo klicaj.
        val fetchedAt = state.fetchedAtMs
        if (bitmap != null && fetchedAt != null) {
            val caption = timeFormat.format(Date(fetchedAt)) + if (state.failed) " !" else ""
            views.setTextViewText(R.id.radar_caption, caption)
            views.setViewVisibility(R.id.radar_caption, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.radar_caption, View.GONE)
        }

        return views
    }

    /**
     * Obreze in pomanjsa sliko na velikost polja. Pomembno: bitmap potuje cez
     * procesno mejo do Karoo OS, zato ga ne posiljamo vecjega, kot ga polje potrebuje.
     */
    private fun fitToField(source: Bitmap, config: ViewConfig): Bitmap {
        val cropX = (source.width * CROP_LEFT).roundToInt()
        val cropY = (source.height * CROP_TOP).roundToInt()
        val cropWidth = (source.width * (1f - CROP_LEFT - CROP_RIGHT)).roundToInt()
        val cropHeight = (source.height * (1f - CROP_TOP - CROP_BOTTOM)).roundToInt()

        val cropped = if (cropWidth in 1 until source.width || cropHeight in 1 until source.height) {
            Bitmap.createBitmap(source, cropX, cropY, cropWidth, cropHeight)
        } else {
            source
        }

        val targetWidth = config.viewSize.first.takeIf { it > 0 } ?: 400
        val targetHeight = config.viewSize.second.takeIf { it > 0 } ?: 400
        val scale = min(
            targetWidth.toFloat() / cropped.width,
            targetHeight.toFloat() / cropped.height,
        )
        if (scale >= 1f) return cropped

        return Bitmap.createScaledBitmap(
            cropped,
            (cropped.width * scale).roundToInt().coerceAtLeast(1),
            (cropped.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }
}
