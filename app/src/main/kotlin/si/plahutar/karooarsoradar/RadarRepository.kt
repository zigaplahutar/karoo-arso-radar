package si.plahutar.karooarsoradar

import android.content.Context
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * En sam vir resnice za celo aplikacijo.
 *
 * Prenasamo samo zadnjo sliko (~13 kB). Vsako shranimo, in ko pritisnes play,
 * predvajamo tisto, kar se je nabralo - tvojo lastno animacijo zadnje ure in pol.
 * Celotne animacije z ARSO (nekaj sto kB) ne prenasamo vec: prek Companiona
 * povezava zmore priblizno kilobajt na sekundo, kar bi pomenilo vec minut cakanja.
 *
 * Samodejna osvezitev tece, dokler je odprt zaslon ali polje oziroma dokler
 * tece snemanje voznje. Sicer se ne dogaja nic.
 */
object RadarRepository {

    private const val TAG = "ArsoRadar"

    val ZOOM_LEVELS = floatArrayOf(1f, 2f, 4f, 8f)

    /** ARSO objavi novo sliko na 5 minut. */
    const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L

    private const val TICK_MS = 30_000L
    private const val FRAME_DELAY_MS = 500L
    private const val LAST_FRAME_DELAY_MS = 1800L

    data class Location(val lat: Double, val lng: Double)

    data class State(
        val frame: Bitmap? = null,
        val fetchedAtMs: Long? = null,
        val loading: Boolean = false,
        val failed: Boolean = false,
        val playing: Boolean = false,
        val zoomIndex: Int = 0,
        val location: Location? = null,
        val progress: String? = null,
        val diagnostic: String? = null,
        /** Koliko slicic imamo shranjenih in cez kaksen razpon minut. */
        val storedFrames: Int = 0,
        val storedSpanMinutes: Int = 0,
        /** Cas slicice, ki se trenutno predvaja. */
        val playingFrameTimeMs: Long? = null,
        val playingIndex: Int = 0,
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
    private var autoJob: Job? = null
    private var locationConsumerId: String? = null
    private var clients = 0
    private var lastFetchMs = 0L

    private var store: RadarFrameStore? = null

    /** Poklice razsiritev ali aktivnost, preden karkoli drugega. */
    fun init(context: Context) {
        if (store != null) return
        val created = RadarFrameStore(File(context.applicationContext.cacheDir, "radar-frames"))
        created.load()
        store = created
        publishStoreState()
    }

    fun hasImage(): Boolean = _state.value.frame != null

    // --- kdo nas potrebuje --------------------------------------------------

    /**
     * Klice podatkovno polje, zaslon aplikacije in snemanje voznje. Dokler je
     * vsaj en odjemalec, tece samodejna osvezitev.
     */
    @Synchronized
    fun addClient() {
        clients++
        if (autoJob?.isActive != true) {
            autoJob = scope.launch {
                // Prvo sliko poberemo takoj, potem na REFRESH_INTERVAL_MS.
                refresh()
                while (isActive) {
                    delay(TICK_MS)
                    if (System.currentTimeMillis() - lastFetchMs >= REFRESH_INTERVAL_MS) {
                        refresh()
                    }
                }
            }
        }
    }

    @Synchronized
    fun removeClient() {
        clients = (clients - 1).coerceAtLeast(0)
        if (clients == 0) {
            autoJob?.cancel()
            autoJob = null
            stopPlay()
        }
    }

    // --- lokacija ----------------------------------------------------------

    fun startLocationUpdates(system: KarooSystemService) {
        if (locationConsumerId != null) return
        locationConsumerId = system.addConsumer<OnLocationChanged> { event ->
            _state.update { it.copy(location = Location(event.lat, event.lng)) }
        }
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

    /** Predvaja shranjene slicice od najstarejse do najnovejse. */
    fun togglePlay() {
        if (playJob?.isActive == true) {
            stopPlay()
            return
        }
        val frames = store?.frames().orEmpty()
        if (frames.size < 2) {
            _state.update {
                it.copy(progress = "Za animacijo rabim vsaj 2 sliki (imam ${frames.size})")
            }
            scope.launch {
                delay(3000)
                _state.update { it.copy(progress = null) }
            }
            return
        }

        playJob = scope.launch {
            _state.update { it.copy(playing = true, progress = null) }
            try {
                frames.forEachIndexed { index, stored ->
                    val bitmap = GifFrames.lastFrame(stored.bytes)
                    if (bitmap != null) {
                        _state.update {
                            it.copy(
                                frame = bitmap,
                                playingFrameTimeMs = stored.timeMs,
                                playingIndex = index + 1,
                            )
                        }
                    }
                    delay(if (index == frames.lastIndex) LAST_FRAME_DELAY_MS else FRAME_DELAY_MS)
                }
            } finally {
                _state.update { it.copy(playing = false, playingFrameTimeMs = null) }
            }
        }
    }

    fun stopPlay() {
        playJob?.cancel()
        playJob = null
        _state.update { it.copy(playing = false, playingFrameTimeMs = null) }
    }

    // --- prenos ------------------------------------------------------------

    /** [force] = pritisk na gumb; sicer prenesemo le, ce slike se nimamo. */
    suspend fun refresh(force: Boolean = false) {
        mutex.withLock {
            if (!force && _state.value.frame != null &&
                System.currentTimeMillis() - lastFetchMs < REFRESH_INTERVAL_MS
            ) {
                return
            }

            _state.update { it.copy(loading = true, progress = RadarDownloader.PROGRESS_DOWNLOADING) }

            val result = RadarDownloader.downloadStatic(
                karooSystem = karooSystem,
                onProgress = { text -> _state.update { it.copy(progress = text) } },
                onDiagnostic = { text -> _state.update { it.copy(diagnostic = text) } },
            )
            val bitmap = result?.let { GifFrames.lastFrame(it.bytes) }
            lastFetchMs = System.currentTimeMillis()

            if (result != null && bitmap != null) {
                val fresh = store?.add(result.bytes, lastFetchMs) ?: false
                Log.d(TAG, "Slika prek ${result.via}, nova=$fresh, shranjenih=${store?.size()}")
                _state.update {
                    it.copy(
                        frame = if (it.playing) it.frame else bitmap,
                        fetchedAtMs = lastFetchMs,
                        loading = false,
                        failed = false,
                        progress = null,
                    )
                }
                publishStoreState()
            } else {
                Log.w(TAG, "Slike ni bilo mogoce pridobiti")
                _state.update { it.copy(loading = false, failed = true, progress = null) }
            }
        }
    }

    private fun publishStoreState() {
        val current = store ?: return
        _state.update {
            it.copy(storedFrames = current.size(), storedSpanMinutes = current.spanMinutes())
        }
    }
}
