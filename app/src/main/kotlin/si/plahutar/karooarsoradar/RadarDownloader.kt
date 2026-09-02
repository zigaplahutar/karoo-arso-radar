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
 * Dve datoteki:
 *  - si0-rm.gif       zadnja slika, ~13 kB - to je tisto, kar potrebujemo obicajno
 *  - si0-rm-anim.gif  animacija zadnjih 90 minut, nekaj sto kB - samo ob pritisku na play
 *
 * Dve poti:
 *  1. Karoo HTTP API (OnHttpResponse.MakeHttpRequest) - edina pot, ki dela tudi takrat,
 *     ko internet priteka prek Companion aplikacije na telefonu. Zahtevo je treba dati
 *     v vrsto (waitForConnection = true), ker povezava s telefonom ni takoj pripravljena.
 *     Omejitev je 100 kB na telo odgovora; zadnja slika je krepko pod njo, animacijo
 *     poberemo po kosih z zaglavjem Range.
 *  2. Navadna HTTP povezava - deluje SAMO, ko je Karoo na WiFi. Prek Companiona
 *     omrezne poti ni, ker ta ni omrezni vmesnik, ampak posrednik zahtev.
 */
object RadarDownloader {

    const val STATIC_URL =
        "https://meteo.arso.gov.si/uploads/probase/www/observ/radar/si0-rm.gif"
    const val ANIMATION_URL =
        "https://meteo.arso.gov.si/uploads/probase/www/observ/radar/si0-rm-anim.gif"

    private const val TAG = "ArsoRadar"
    private const val CHUNK_SIZE = 90_000
    private const val MAX_TOTAL_BYTES = 6_000_000
    private const val USER_AGENT = "karoo-arso-radar"

    /** Prek Bluetootha zahteva caka v vrsti, dokler se povezava s telefonom ne zbudi. */
    private const val FIRST_REQUEST_TIMEOUT_MS = 90_000L
    private const val CHUNK_TIMEOUT_MS = 60_000L

    /** Kaj se dogaja - gre na zaslon, da uporabnik ne bulji v "Nalagam". */
    const val PROGRESS_WAITING = "Čakam na povezavo…"
    const val PROGRESS_DOWNLOADING = "Prenašam…"

    data class Download(
        val bytes: ByteArray,
        /** Kratek opis, katera pot je uspela - za diagnostiko na zaslonu. */
        val via: String,
    )

    suspend fun download(
        karooSystem: KarooSystemService?,
        animation: Boolean,
        onProgress: (String) -> Unit = {},
        onDiagnostic: (String) -> Unit = {},
    ): Download? {
        val url = if (animation) ANIMATION_URL else STATIC_URL
        val notes = StringBuilder()

        if (karooSystem != null) {
            // Zadnja slika je majhna, zato najprej navadna zahteva brez Range.
            if (!animation) {
                runCatching { singleRequest(karooSystem, url, onProgress) }
                    .onFailure { notes.append("Karoo: ${it.message}; ") }
                    .getOrNull()
                    ?.let { bytes ->
                        if (isCompleteGif(bytes)) {
                            onDiagnostic("Karoo · ${bytes.size / 1024} kB")
                            return Download(bytes, "Karoo")
                        }
                        notes.append("Karoo: nepopolna slika (${bytes.size} B); ")
                    }
            }

            runCatching { chunkedRequest(karooSystem, url, onProgress) }
                .onFailure { notes.append("kosi: ${it.message}; ") }
                .getOrNull()
                ?.let { bytes ->
                    if (isCompleteGif(bytes)) {
                        onDiagnostic("Karoo po kosih · ${bytes.size / 1024} kB")
                        return Download(bytes, "Karoo po kosih")
                    }
                    notes.append("kosi: nepopolna slika (${bytes.size} B); ")
                }
        } else {
            notes.append("ni povezave s Karoo sistemom; ")
        }

        onProgress(PROGRESS_DOWNLOADING)
        runCatching { directRequest(url) }
            .onFailure { notes.append("WiFi: ${it.message}") }
            .getOrNull()
            ?.let { bytes ->
                if (isCompleteGif(bytes)) {
                    onDiagnostic("WiFi · ${bytes.size / 1024} kB")
                    return Download(bytes, "WiFi")
                }
                notes.append("WiFi: nepopolna slika (${bytes.size} B)")
            }

        Log.w(TAG, "Prenos ni uspel: $notes")
        onDiagnostic(notes.toString().trim().ifEmpty { "prenos ni uspel" })
        return null
    }

    /** GIF se zacne z GIF8 in konca s trailerjem 0x3B; tako lovimo odrezan prenos. */
    private fun isCompleteGif(bytes: ByteArray): Boolean {
        if (bytes.size < 100) return false
        if (String(bytes, 0, 4, Charsets.US_ASCII) != "GIF8") return false
        return bytes[bytes.size - 1] == 0x3B.toByte()
    }

    // --- pot 1a: ena zahteva prek Karoo -------------------------------------

    private suspend fun singleRequest(
        karooSystem: KarooSystemService,
        url: String,
        onProgress: (String) -> Unit,
    ): ByteArray {
        val response = request(
            karooSystem,
            url,
            mapOf("User-Agent" to USER_AGENT),
            FIRST_REQUEST_TIMEOUT_MS,
            onProgress,
        ) ?: error("brez odgovora v ${FIRST_REQUEST_TIMEOUT_MS / 1000} s")

        response.error?.let { error(it) }
        if (response.statusCode !in 200..299) error("HTTP ${response.statusCode}")
        return response.body ?: error("prazno telo")
    }

    // --- pot 1b: po kosih z Range -------------------------------------------

    private suspend fun chunkedRequest(
        karooSystem: KarooSystemService,
        url: String,
        onProgress: (String) -> Unit,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        var total: Int? = null
        var first = true

        while (true) {
            val response = request(
                karooSystem,
                url,
                mapOf(
                    "User-Agent" to USER_AGENT,
                    "Range" to "bytes=$offset-${offset + CHUNK_SIZE - 1}",
                ),
                if (first) FIRST_REQUEST_TIMEOUT_MS else CHUNK_TIMEOUT_MS,
                onProgress,
            ) ?: error("brez odgovora")
            first = false

            response.error?.let { error(it) }
            val body = response.body ?: error("prazno telo")

            when (response.statusCode) {
                206 -> {
                    out.write(body)
                    offset += body.size
                    if (total == null) total = parseTotalLength(response.headers)
                    if (body.isEmpty() || (total != null && offset >= total)) return out.toByteArray()
                    if (offset > MAX_TOTAL_BYTES) error("preveliko")
                }
                // Streznik je Range ignoriral in poslal vse naenkrat.
                200 -> return body
                else -> error("HTTP ${response.statusCode}")
            }
        }
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
                    // Kljucno: prek Companiona povezava ni takoj na voljo, zato
                    // mora zahteva pocakati v vrsti namesto da takoj odpove.
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

    /** Iz "bytes 0-89999/523456" potegne 523456. */
    private fun parseTotalLength(headers: Map<String, String>): Int? =
        headers.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value
            ?.substringAfter('/', "")
            ?.trim()
            ?.toIntOrNull()

    // --- pot 2: navadna povezava (samo WiFi) --------------------------------

    private fun directRequest(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 25_000
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
