package si.plahutar.karooarsoradar

import android.os.Bundle
import android.widget.ImageView
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
 * Isti gumbi kot na podatkovnem polju, isto stanje.
 */
class MainActivity : AppCompatActivity() {

    private val karooSystem by lazy { KarooSystemService(this) }
    private var ownsKarooSystem = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val image = findViewById<ImageView>(R.id.image)
        val status = findViewById<TextView>(R.id.status)
        val playButton = findViewById<TextView>(R.id.btn_play)

        findViewById<TextView>(R.id.btn_zoom_out).setOnClickListener { RadarRepository.zoomOut() }
        findViewById<TextView>(R.id.btn_zoom_in).setOnClickListener { RadarRepository.zoomIn() }
        playButton.setOnClickListener { RadarRepository.togglePlay() }
        findViewById<TextView>(R.id.btn_refresh).setOnClickListener { RadarRepository.refreshAsync() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (!RadarRepository.hasImage()) {
                    launch { RadarRepository.refresh() }
                }
                RadarRepository.state.collect { state ->
                    image.setImageBitmap(state.frame)
                    image.scaleX = state.zoom
                    image.scaleY = state.zoom
                    playButton.text = if (state.playing) "■" else "▶"
                    status.text = when {
                        state.loading && state.frame == null -> getString(R.string.loading)
                        state.frame == null -> getString(R.string.no_connection)
                        state.playing && state.frameCount > 0 ->
                            "Animacija ${state.frameIndex}/${state.frameCount}"
                        else -> {
                            val time = state.fetchedAtMs?.let { timeFormat.format(Date(it)) } ?: "-"
                            val suffix = if (state.failed) "  (zadnji poskus ni uspel)" else ""
                            "Preneseno ob $time$suffix"
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Ce razsiritev ne tece, poskrbimo za povezavo sami.
        if (RadarRepository.karooSystem == null) {
            karooSystem.connect()
            RadarRepository.karooSystem = karooSystem
            ownsKarooSystem = true
        }
    }

    override fun onStop() {
        RadarRepository.stopPlay()
        if (ownsKarooSystem) {
            if (RadarRepository.karooSystem === karooSystem) {
                RadarRepository.karooSystem = null
            }
            karooSystem.disconnect()
            ownsKarooSystem = false
        }
        super.onStop()
    }
}
