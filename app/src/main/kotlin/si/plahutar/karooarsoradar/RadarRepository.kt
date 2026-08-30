package si.plahutar.karooarsoradar

import android.graphics.Bitmap
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * En sam vir resnice za celo aplikacijo: podatkovno polje in glavni zaslon
 * gledata isto sliko in isto stanje (zoom, animacija).
 *
 * Prenos se NE dogaja sam od sebe - samo ob prvem prikazu in ob pritisku na gumb
 * za osvezitev. Ko polje ni na zaslonu, se ne dogaja nic.
 */
object RadarRepository {

    private const val TAG = "ArsoRadar"

    val ZOOM_LEVELS = floatArrayOf(1f, 1.5f, 2f, 3f)

    /** Koliko casa se ena slicica pokaze med animacijo. */
    private const val FRAME_DELAY_MS = 260L

    /** Na zadnji (najnovejsi) slicici se animacija malo ustavi. */
    private const val LAST_FRAME_DELAY_MS = 1400L

    data class State(
        val frame: Bitmap? = null,
        val fetchedAtMs: Long? = null,
        val loading: Boolean = false,
        val failed: Boolean = false,
        val playing: Boolean = false,
        val zoomIndex: Int = 0,
        val frameIndex: Int = 0,
        val frameCount: Int = 0,
    ) {
        val zoom: Float get() = ZOOM_LEVELS[zoomIndex.coerceIn(0, ZOOM_LEVELS.lastIndex)]
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Povezavo s Karoo sistemom postavi razsiritev (ali aplikacija, ce razsiritev
     * se ne tece). Prenos gre prek nje, ker je to edina pot, ki dela tudi takrat,
     * ko internet priteka prek Companion aplikacije.
     */
    @Volatile
    var karooSystem: KarooSystemService? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private var playJob: Job? = null

    /** Cel GIF ostane v pomnilniku - v njem je vseh 90 minut animacije. */
    private var gifBytes: ByteArray? = null

    fun hasImage(): Boolean = gifBytes != null

    // --- ukazi z gumbov ----------------------------------------------------

    fun refreshAsync() {
        scope.launch { refresh(force = true) }
    }

    fun zoomIn() {
        _state.update { it.copy(zoomIndex = (it.zoomIndex + 1).coerceAtMost(ZOOM_LEVELS.lastIndex)) }
    }

    fun zoomOut() {
        _state.update { it.copy(zoomIndex = (it.zoomIndex - 1).coerceAtLeast(0)) }
    }

    /** Play/stop. Prenosa ne potrebuje - vse slicice so ze v prenesenem GIF-u. */
    fun togglePlay() {
        if (playJob?.isActive == true) {
            stopPlay()
            return
        }
        playJob = scope.launch {
            val bytes = gifBytes ?: run {
                refresh(force = true)
                gifBytes
            } ?: return@launch

            _state.update { it.copy(playing = true) }
            try {
                GifFrames.forEachFrame(bytes) { frame, index, count ->
                    _state.update {
                        it.copy(frame = frame, frameIndex = index + 1, frameCount = count)
                    }
                    delay(if (index == count - 1) LAST_FRAME_DELAY_MS else FRAME_DELAY_MS)
                }
            } finally {
                _state.update { it.copy(playing = false) }
            }
        }
    }

    fun stopPlay() {
        playJob?.cancel()
        playJob = null
        _state.update { it.copy(playing = false) }
    }

    // --- prenos ------------------------------------------------------------

    /**
     * [force] = pritisk na gumb. Brez njega se slika prenese samo, ce je se nimamo
     * (prvi prikaz polja).
     */
    suspend fun refresh(force: Boolean = false) {
        mutex.withLock {
            if (!force && gifBytes != null) return

            _state.update { it.copy(loading = true) }

            val bytes = RadarDownloader.download(karooSystem)
            val frame = bytes?.let { GifFrames.lastFrame(it) }

            if (bytes != null && frame != null) {
                gifBytes = bytes
                Log.d(TAG, "Nova radarska slika: ${frame.width}x${frame.height}, ${bytes.size} B")
                _state.update {
                    it.copy(
                        frame = frame,
                        fetchedAtMs = System.currentTimeMillis(),
                        loading = false,
                        failed = false,
                        frameIndex = 0,
                        frameCount = 0,
                    )
                }
            } else {
                Log.w(TAG, "Radarske slike ni bilo mogoce pridobiti")
                // Staro sliko obdrzimo - bolje stara slika kot prazen zaslon.
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }
}
