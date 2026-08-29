package si.plahutar.karooarsoradar

import android.graphics.Bitmap
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * En sam vir resnice za celo aplikacijo: podatkovno polje in glavni zaslon
 * gledata isto sliko, prenos pa se zgodi kvecjemu enkrat na [MIN_REFRESH_INTERVAL_MS],
 * tudi ce je polje na vec straneh hkrati.
 */
object RadarRepository {

    private const val TAG = "ArsoRadar"

    /** ARSO objavi novo sliko na 5 minut; osvezujemo malo pogosteje. */
    private const val MIN_REFRESH_INTERVAL_MS = 120_000L

    data class State(
        val bitmap: Bitmap? = null,
        val fetchedAtMs: Long? = null,
        val loading: Boolean = false,
        val failed: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val mutex = Mutex()
    private var lastAttemptMs = 0L

    suspend fun refresh(karooSystem: KarooSystemService?, force: Boolean = false) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && now - lastAttemptMs < MIN_REFRESH_INTERVAL_MS) return
            lastAttemptMs = now

            _state.value = _state.value.copy(loading = true)

            val bytes = RadarDownloader.download(karooSystem)
            val bitmap = bytes?.let { GifFrames.lastFrame(it) }

            _state.value = if (bitmap != null) {
                Log.d(TAG, "Nova radarska slika: ${bitmap.width}x${bitmap.height}")
                State(bitmap = bitmap, fetchedAtMs = now, loading = false, failed = false)
            } else {
                Log.w(TAG, "Radarske slike ni bilo mogoce pridobiti")
                // Staro sliko obdrzimo - bolje stara slika kot prazen zaslon.
                _state.value.copy(loading = false, failed = true)
            }
        }
    }
}
