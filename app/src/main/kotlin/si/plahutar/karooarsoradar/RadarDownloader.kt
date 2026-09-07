package si.plahutar.karooarsoradar

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Prenese radarsko sliko ARSO.
 *
 * Prenasamo samo zadnjo sliko si0-rm.gif (~13 kB). Celotne animacije z ARSO
 * (nekaj sto kB) ne prenasamo: prek Companiona povezava zmore priblizno kilobajt
 * na sekundo, kar bi pomenilo vec minut cakanja. Animacijo si sestavimo sami iz
 * shranjenih slik (glej RadarFrameStore).
 *
 * Dve poti:
 *  1. Karoo HTTP API - edina pot, ki dela tudi prek Companion aplikacije na telefonu.
 *     Zahteva mora cakati v vrsti (waitForConnection = true), sicer prek Bluetootha
 *     takoj odpove. Omejitev 100 kB v SDK velja samo za telo ZAHTEVE, ne odgovora -
 *     velik odgovor torej ni prepovedan, je pa prek Bluetootha pocasen.
 *  2. Navadna HTTP povezava - deluje SAMO na WiFi (Companion ni omrezni vmesnik).
 *
 * Vsak poskus se belezi v [Diagnostics], da se na napravi vidi, kaj je odpovedalo.
 */
object RadarDownloader {

    const val STATIC_URL =
        "https://meteo.arso.gov.si/uploads/probase/www/observ/radar/si0-rm.gif"

    private const val TAG = "ArsoRadar"
    private const val USER_AGENT = "karoo-arso-radar"

    private const val MAX_TOTAL_BYTES = 6_000_000

    private const val STATIC_TIMEOUT_MS = 90_000L

    const val PROGRESS_WAITING = "Čakam na povezavo…"
    const val PROGRESS_DOWNLOADING = "Prenašam…"

    data class Download(val bytes: ByteArray, val via: String)

    /** Zbira korake poskusa, da jih lahko prikazemo na zaslonu. */
    private class Diagnostics {
        private val steps = mutableListOf<String>()
        fun add(step: String) {
            steps += step
            Log.d(TAG, "diag: $step")
        }
        override fun toString(): String = steps.joinToString(" · ")
    }

    // --- zadnja slika (majhna) ---------------------------------------------

    suspend fun downloadStatic(
        karooSystem: KarooSystemService?,
        onProgress: (String) -> Unit = {},
        onDiagnostic: (String) -> Unit = {},
    ): Download? {
        val diagnostics = Diagnostics()

        if (karooSystem != null) {
            attempt(diagnostics, "Karoo") {
                singleRequest(karooSystem, STATIC_URL, emptyMap(), STATIC_TIMEOUT_MS, onProgress)
            }?.let { onDiagnostic(diagnostics.toString()); return Download(it, "Karoo") }
        } else {
            diagnostics.add("ni povezave s Karoo")
        }

        onProgress(PROGRESS_DOWNLOADING)
        attempt(diagnostics, "WiFi") { directRequest(STATIC_URL) }
            ?.let { onDiagnostic(diagnostics.toString()); return Download(it, "WiFi") }

        onDiagnostic(diagnostics.toString())
        return null
    }

    /** Izvede poskus, izmeri cas in zabelezi izid. Vrne bajte le, ce je GIF popoln. */
    private suspend fun attempt(
        diagnostics: Diagnostics,
        name: String,
        block: suspend () -> ByteArray,
    ): ByteArray? {
        val started = System.currentTimeMillis()
        return try {
            val bytes = block()
            val seconds = (System.currentTimeMillis() - started) / 1000
            if (isCompleteGif(bytes)) {
                diagnostics.add("$name ✓ ${bytes.size / 1024}kB ${seconds}s")
                bytes
            } else {
                diagnostics.add("$name odrezan ${bytes.size / 1024}kB ${seconds}s")
                null
            }
        } catch (throwable: Throwable) {
            val seconds = (System.currentTimeMillis() - started) / 1000
            diagnostics.add("$name ✗ ${throwable.message} ${seconds}s")
            null
        }
    }

    /** GIF se zacne z GIF8 in konca s trailerjem 0x3B; tako lovimo odrezan prenos. */
    private fun isCompleteGif(bytes: ByteArray): Boolean {
        if (bytes.size < 100) return false
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "GIF8") return false
        return bytes[bytes.size - 1] == 0x3B.toByte()
    }

    // --- gradniki -----------------------------------------------------------

    private suspend fun singleRequest(
        karooSystem: KarooSystemService,
        url: String,
        extraHeaders: Map<String, String>,
        timeoutMs: Long,
        onProgress: (String) -> Unit,
    ): ByteArray {
        val response = request(
            karooSystem,
            url,
            mapOf("User-Agent" to USER_AGENT) + extraHeaders,
            timeoutMs,
            onProgress,
        ) ?: error("brez odgovora v ${timeoutMs / 1000}s")

        response.error?.let { error(it) }
        if (response.statusCode !in 200..299) error("HTTP ${response.statusCode}")
        return response.body ?: error("prazno telo")
    }

    private suspend fun request(
        karooSystem: KarooSystemService,
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        onProgress: (String) -> Unit,
    ): HttpResponseState.Complete? = withTimeoutOrNull(timeoutMs) {
        callbackFlow {
            val listenerId = karooSystem.addConsumer(
                OnHttpResponse.MakeHttpRequest(
                    method = "GET",
                    url = url,
                    headers = headers,
                    // Prek Companiona povezava ni takoj na voljo, zato mora zahteva
                    // pocakati v vrsti namesto da takoj odpove.
                    waitForConnection = true,
                ),
            ) { event: OnHttpResponse ->
                when (val responseState = event.state) {
                    is HttpResponseState.Queued -> onProgress(PROGRESS_WAITING)
                    is HttpResponseState.InProgress -> onProgress(PROGRESS_DOWNLOADING)
                    is HttpResponseState.Complete -> {
                        trySendBlocking(responseState)
                        close()
                    }
                }
            }
            awaitClose { karooSystem.removeConsumer(listenerId) }
        }.first()
    }

    private fun directRequest(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            return connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_TOTAL_BYTES) error("preveliko")
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
