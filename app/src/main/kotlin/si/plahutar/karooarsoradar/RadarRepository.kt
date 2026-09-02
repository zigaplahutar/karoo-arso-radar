package si.plahutar.karooarsoradar

import android.graphics.Bitmap
import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.OnLocationChanged
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
 * En sam vir resnice za celo aplikacijo.
 *
 * Obicajno prenasamo samo zadnjo sliko (~13 kB). Animacijo zadnjih 90 minut
 * (nekaj sto kB) prenesemo sele ob pritisku na play.
 *
 * Prenos se NE dogaja sam od sebe - samo ob prvem prikazu in ob pritisku na gumb.
 */
object RadarRepository {

    private const val TAG = "ArsoRadar"

    val ZOOM_LEVELS = floatArrayOf(1f, 2f, 4f, 8f)

    private const val FRAME_DELAY_MS = 260L
    private const val LAST_FRAME_DELAY_MS = 1400L

    data class Location(val lat: Double, val lng: Double)

    data class State(
        val frame: Bitmap? = null,
        val fetchedAtMs: Long? = null,
        val loading: Boolean = false,
        val failed: Boolean = false,
        val playing: Boolean = false,
        val zoomIndex: Int = 0,
        val frameIndex: Int = 0,
        val frameCount: Int = 0,
        val location: Location? = null,
        /** Kaj se trenutno dogaja med prenosom. */
        val progress: String? = null,
        /** Katera pot je uspela oziroma zakaj ni - vidno na zaslonu aplikacije. */
        val diagnostic: String? = null,
    ) {
        val zoom: Float get() = ZOOM_LEVELS[zoomIndex.coerceIn(0, ZOOM_LEVELS.lastIndex)]
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    var karooSystem: KarooSystemService? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()
    private var playJob: Job? = null
    private var locationConsumerId: String? = null

    /** Zadnja slika (ena slicica) in animacija (vseh 90 minut) locena. */
    private var staticBytes: ByteArray? = null
    private var animationBytes: ByteArray? = null

    fun hasImage(): Boolean = staticBytes != null

    // --- lokacija ----------------------------------------------------------

    fun startLocationUpdates(system: KarooSystemService) {
        if (locationConsumerId != null) return
        locationConsumerId = system.addConsumer<OnLocationChanged> { event ->
            _state.update { it.copy(location = Location(event.lat, event.lng)) }
        }
        Log.d(TAG, "Spremljanje lokacije vklopljeno")
    }

    fun stopLocationUpdates(system: KarooSystemService) {
        locationConsumerId?.let { system.removeConsumer(it) }
        locationConsumerId = null
    }

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

    /** Play/stop. Ce animacije se nimamo, jo najprej prenesemo. */
    fun togglePlay() {
        if (playJob?.isActive == true) {
            stopPlay()
            return
        }
        playJob = scope.launch {
            val bytes = animationBytes ?: downloadAnimation() ?: return@launch

            _state.update { it.copy(playing = true, progress = null) }
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

    /** Zadnja slika. [force] = pritisk na gumb. */
    suspend fun refresh(force: Boolean = false) {
        mutex.withLock {
            if (!force && staticBytes != null) return

            _state.update { it.copy(loading = true, progress = RadarDownloader.PROGRESS_DOWNLOADING) }

            val result = RadarDownloader.download(
                karooSystem = karooSystem,
                animation = false,
                onProgress = { text -> _state.update { it.copy(progress = text) } },
                onDiagnostic = { text -> _state.update { it.copy(diagnostic = text) } },
            )
            val frame = result?.let { GifFrames.lastFrame(it.bytes) }

            if (result != null && frame != null) {
                staticBytes = result.bytes
                // Animacija je zdaj zastarela; naslednji play jo potegne na novo.
                animationBytes = null
                Log.d(TAG, "Nova slika prek ${result.via}: ${frame.width}x${frame.height}")
                _state.update {
                    it.copy(
                        frame = frame,
                        fetchedAtMs = System.currentTimeMillis(),
                        loading = false,
                        failed = false,
                        frameIndex = 0,
                        frameCount = 0,
                        progress = null,
                    )
                }
            } else {
                Log.w(TAG, "Slike ni bilo mogoce pridobiti")
                // Staro sliko obdrzimo - bolje stara slika kot prazen zaslon.
                _state.update { it.copy(loading = false, failed = true, progress = null) }
            }
        }
    }

    private suspend fun downloadAnimation(): ByteArray? {
        _state.update { it.copy(loading = true, progress = "Prenašam animacijo…") }
        val result = RadarDownloader.download(
            karooSystem = karooSystem,
            animation = true,
            onProgress = { text -> _state.update { it.copy(progress = text) } },
            onDiagnostic = { text -> _state.update { it.copy(diagnostic = text) } },
        )
        _state.update { it.copy(loading = false, progress = null, failed = result == null) }
        animationBytes = result?.bytes
        return animationBytes
    }
}
