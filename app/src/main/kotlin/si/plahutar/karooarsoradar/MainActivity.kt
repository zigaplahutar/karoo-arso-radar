package si.plahutar.karooarsoradar

import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Samostojen zaslon z radarsko sliko cez celo napravo.
 * Odpre se iz glavnega menija Karoo (Extensions / seznam aplikacij).
 */
class MainActivity : AppCompatActivity() {

    private val karooSystem by lazy { KarooSystemService(this) }
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val image = findViewById<ImageView>(R.id.image)
        val status = findViewById<TextView>(R.id.status)

        findViewById<Button>(R.id.refresh).setOnClickListener {
            lifecycleScope.launch { RadarRepository.refresh(karooSystem, force = true) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    RadarRepository.state.collect { state ->
                        image.setImageBitmap(state.bitmap)
                        status.text = when {
                            state.loading && state.bitmap == null -> getString(R.string.loading)
                            state.bitmap == null -> getString(R.string.no_connection)
                            else -> {
                                val time = state.fetchedAtMs?.let { timeFormat.format(Date(it)) } ?: "-"
                                val suffix = if (state.failed) "  (zadnji poskus ni uspel)" else ""
                                "Preneseno ob $time$suffix"
                            }
                        }
                    }
                }
                launch {
                    while (isActive) {
                        RadarRepository.refresh(karooSystem)
                        delay(20_000)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        karooSystem.connect()
    }

    override fun onStop() {
        karooSystem.disconnect()
        super.onStop()
    }
}
