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
 * Prenese animirano radarsko sliko ARSO.
 *
 * Dve poti, ker imata obe svojo omejitev:
 *
 *  1. Karoo HTTP API (OnHttpResponse.MakeHttpRequest) - edina pot, ki dela tudi takrat,
 *     ko internet priteka prek Companion aplikacije na telefonu. Ima pa trdo omejitev
 *     100 kB na telo odgovora, GIF je vecji, zato ga poberemo po kosih z zaglavjem Range.
 *  2. Navadna HTTP povezava - dela, ko je Karoo na WiFi. Uporabimo jo kot rezervo,
 *     ce streznik ne podpira zahtev Range.
 */
object RadarDownloader {

    const val RADAR_URL =
        "https://meteo.arso.gov.si/uploads/probase/www/observ/radar/si0-rm-anim.gif"

    private const val TAG = "ArsoRadar"

    /** karoo-ext dovoli 100_000 bajtov na telo, pustimo si rezervo. */
    private const val CHUNK_SIZE = 90_000

    private const val MAX_TOTAL_BYTES = 6_000_000
    private const val USER_AGENT = "karoo-arso-radar"
    private const val REQUEST_TIMEOUT_MS = 30_000L

    /**
     * Vrne cele bajte GIF-a ali null, ce ni slo.
     * Uspeh preverimo po vsebini (glava GIF8 + zakljucni bajt), ne po statusu,
     * ker skrajsan prenos sicer tiho konca v pokvarjeni sliki.
     */
    suspend fun download(karooSystem: KarooSystemService?): ByteArray? {
        if (karooSystem != null) {
            val viaKaroo = runCatching { downloadViaKaroo(karooSystem) }
                .onFailure { Log.w(TAG, "Prenos prek Karoo API ni uspel: ${it.message}") }
                .getOrNull()
            if (viaKaroo != null && isCompleteGif(viaKaroo)) return viaKaroo
        }

        val direct = runCatching { downloadDirect() }
            .onFailure { Log.w(TAG, "Neposredni prenos ni uspel: ${it.message}") }
            .getOrNull()
        return direct?.takeIf { isCompleteGif(it) }
    }

    private fun isCompleteGif(bytes: ByteArray): Boolean {
        if (bytes.size < 100) return false
        val header = String(bytes, 0, 4, Charsets.US_ASCII)
        if (header != "GIF8") return false
        // GIF se konca s trailerjem 0x3B; ce ga ni, je prenos odrezan
        return bytes[bytes.size - 1] == 0x3B.toByte()
    }

    // --- pot 1: Karoo HTTP API, po kosih ------------------------------------

    private suspend fun downloadViaKaroo(karooSystem: KarooSystemService): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        var total: Int? = null

        while (true) {
            val response = request(
                karooSystem,
                mapOf(
                    "User-Agent" to USER_AGENT,
                    "Range" to "bytes=$offset-${offset + CHUNK_SIZE - 1}",
                ),
            ) ?: error("timeout")

            response.error?.let { error(it) }
            val body = response.body ?: error("prazen odgovor")

            when (response.statusCode) {
                206 -> {
                    out.write(body)
                    offset += body.size
                    if (total == null) total = parseTotalLength(response.headers)
                    val done = body.isEmpty() || (total != null && offset >= total)
                    if (done) return out.toByteArray()
                    if (offset > MAX_TOTAL_BYTES) error("slika je prevelika")
                }
                // Streznik je Range ignoriral in poslal vse naenkrat.
                // Ce je bilo vec kot 100 kB, bo telo odrezano - to ujame isCompleteGif().
                200 -> return body
                else -> error("HTTP ${response.statusCode}")
            }
        }
    }

    private suspend fun request(
        karooSystem: KarooSystemService,
        headers: Map<String, String>,
    ): HttpResponseState.Complete? = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
        callbackFlow {
            val listenerId = karooSystem.addConsumer(
                OnHttpResponse.MakeHttpRequest(
                    method = "GET",
                    url = RADAR_URL,
                    headers = headers,
                    waitForConnection = false,
                ),
            ) { event: OnHttpResponse ->
                (event.state as? HttpResponseState.Complete)?.let {
                    trySendBlocking(it)
                    close()
                }
            }
            awaitClose { karooSystem.removeConsumer(listenerId) }
        }.first()
    }

    /** Iz "bytes 0-89999/523456" potegne 523456. */
    private fun parseTotalLength(headers: Map<String, String>): Int? {
        val contentRange = headers.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value ?: return null
        return contentRange.substringAfter('/', "").trim().toIntOrNull()
    }

    // --- pot 2: navadna povezava (WiFi) ------------------------------------

    private fun downloadDirect(): ByteArray {
        val connection = (URL(RADAR_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
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
                    if (out.size() > MAX_TOTAL_BYTES) error("slika je prevelika")
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
