package si.plahutar.karooarsoradar

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Samostojen zaslon z radarsko sliko cez cel zaslon naprave.
 * Tu prst dela: poteg premika, dvojni tap priblizuje, dva prsta scipata.
 */
class MainActivity : AppCompatActivity() {

    private val karooSystem by lazy { KarooSystemService(this) }
    private var ownsKarooSystem = false
    private var centeredOnce = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val radarView = findViewById<RadarImageView>(R.id.radar_view)
        val status = findViewById<TextView>(R.id.status)
        val diagnostic = findViewById<TextView>(R.id.diagnostic)
        val playButton = findViewById<TextView>(R.id.btn_play)

        findViewById<TextView>(R.id.btn_zoom_out).setOnClickListener { radarView.zoomBy(0.5f) }
        findViewById<TextView>(R.id.btn_zoom_in).setOnClickListener { radarView.zoomBy(2f) }
        playButton.setOnClickListener { RadarRepository.togglePlay() }
        findViewById<TextView>(R.id.btn_refresh).setOnClickListener { RadarRepository.refreshAsync() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (!RadarRepository.hasImage()) {
                    launch { RadarRepository.refresh() }
                }
                RadarRepository.state.collect { state ->
                    val frame = state.frame
                    val marker = frame?.let { bitmap ->
                        state.location?.let { ArsoGeo.toPixel(it.lat, it.lng, bitmap.width, bitmap.height) }
                    }
                    radarView.setImage(frame, marker)
                    if (marker != null && !centeredOnce) {
                        centeredOnce = true
                        radarView.centerOnMarker()
                    }

                    diagnostic.text = state.diagnostic.orEmpty()
                    playButton.text = if (state.playing) "■" else "▶"
                    status.text = when {
                        state.progress != null -> state.progress
                        state.loading && frame == null -> getString(R.string.loading)
                        frame == null -> getString(R.string.no_connection)
                        state.playing && state.frameCount > 0 ->
                            getString(R.string.animation_progress, state.frameIndex, state.frameCount)
                        else -> {
                            val time = state.fetchedAtMs?.let { timeFormat.format(Date(it)) } ?: "-"
                            val base = getString(R.string.fetched_at, time)
                            when {
                                state.failed -> "$base  ${getString(R.string.last_attempt_failed)}"
                                state.location == null -> "$base  ${getString(R.string.no_gps)}"
                                marker == null && frame != null ->
                                    "$base  ${ArsoGeo.describeMismatch(frame.width, frame.height)}"
                                else -> base
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (RadarRepository.karooSystem == null) {
            karooSystem.connect()
            RadarRepository.karooSystem = karooSystem
            RadarRepository.startLocationUpdates(karooSystem)
            ownsKarooSystem = true
        }
    }

    override fun onStop() {
        RadarRepository.stopPlay()
        if (ownsKarooSystem) {
            RadarRepository.stopLocationUpdates(karooSystem)
            if (RadarRepository.karooSystem === karooSystem) {
                RadarRepository.karooSystem = null
            }
            karooSystem.disconnect()
            ownsKarooSystem = false
        }
        super.onStop()
    }
}
