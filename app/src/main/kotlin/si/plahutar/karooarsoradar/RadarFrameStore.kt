package si.plahutar.karooarsoradar

import android.util.Log
import java.io.File

/**
 * Shramba zadnjih radarskih slik.
 *
 * Ob vsaki osvezitvi pade v roke ~13 kB velika slika. Namesto da jo zavrzemo,
 * jo shranimo - po uri in pol imamo lastno animacijo, ki ni stala niti enega
 * dodatnega bajta prenosa.
 *
 * Hranimo surove bajte GIF-a, ne dekodiranih slik: 13 kB proti dobremu megabajtu
 * na slicico. Dekodiramo sele med predvajanjem.
 *
 * Na disk pisemo zato, da zgodovina prezivi ponovni zagon razsiritve.
 */
class RadarFrameStore(private val directory: File) {

    companion object {
        private const val TAG = "ArsoRadar"

        /** Koliko nazaj hranimo slike. */
        const val RETENTION_MS = 90 * 60 * 1000L

        /** Varovalka, ce bi kdo osvezeval zelo pogosto. */
        private const val MAX_FRAMES = 40

        private const val PREFIX = "frame_"
        private const val SUFFIX = ".gif"
    }

    data class Frame(val bytes: ByteArray, val timeMs: Long) {
        override fun equals(other: Any?) =
            other is Frame && timeMs == other.timeMs && bytes.contentEquals(other.bytes)

        override fun hashCode() = 31 * timeMs.hashCode() + bytes.contentHashCode()
    }

    private val frames = mutableListOf<Frame>()

    @Synchronized
    fun load() {
        if (frames.isNotEmpty()) return
        runCatching {
            directory.mkdirs()
            directory.listFiles { file -> file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) }
                ?.forEach { file ->
                    val time = file.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull()
                    if (time != null) frames += Frame(file.readBytes(), time)
                }
            frames.sortBy { it.timeMs }
            prune()
            Log.d(TAG, "Iz shrambe naloženih ${frames.size} sličic")
        }.onFailure { Log.w(TAG, "Shrambe ni bilo mogoče naložiti", it) }
    }

    /**
     * Doda novo sliko. Ce je enaka zadnji (ARSO se ni objavil nove), je ne dodamo -
     * sicer bi animacija stala na mestu.
     *
     * @return true, ce je slika res nova
     */
    @Synchronized
    fun add(bytes: ByteArray, timeMs: Long): Boolean {
        if (frames.lastOrNull()?.bytes?.contentEquals(bytes) == true) {
            prune()
            return false
        }
        frames += Frame(bytes, timeMs)
        runCatching { File(directory, "$PREFIX$timeMs$SUFFIX").writeBytes(bytes) }
            .onFailure { Log.w(TAG, "Sličice ni bilo mogoče shraniti", it) }
        prune()
        return true
    }

    @Synchronized
    fun frames(): List<Frame> = frames.toList()

    @Synchronized
    fun size(): Int = frames.size

    /** Casovni razpon shranjenih slik v minutah. */
    @Synchronized
    fun spanMinutes(): Int {
        if (frames.size < 2) return 0
        return ((frames.last().timeMs - frames.first().timeMs) / 60_000L).toInt()
    }

    @Synchronized
    private fun prune() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val removed = mutableListOf<Frame>()
        frames.removeAll { frame ->
            (frame.timeMs < cutoff).also { if (it) removed += frame }
        }
        while (frames.size > MAX_FRAMES) {
            removed += frames.removeAt(0)
        }
        removed.forEach { frame ->
            runCatching { File(directory, "$PREFIX${frame.timeMs}$SUFFIX").delete() }
        }
    }
}
