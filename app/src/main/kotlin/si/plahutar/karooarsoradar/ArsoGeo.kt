package si.plahutar.karooarsoradar

/**
 * Preslikava iz zemljepisnih koordinat v piksel na ARSO radarski sliki.
 *
 * Kalibrirano na sliki si0-rm.gif (821x660): iz slike je bilo prebranih 34 krajevnih
 * oznak, jim pripisane koordinate in izracunana linearna preslikava z robustnim
 * prilagajanjem. RMS 1,76 piksla, najvecje odstopanje 3,25 piksla. En piksel je
 * priblizno 500 m, torej je napaka pod kilometrom. Kontrola: radar Lisca, ki je na
 * sliki oznacen s krizcem, se ujema na en piksel.
 *
 * Mreza je pravokotna in brez rotacije, zato zadostujeta dve premici.
 */
object ArsoGeo {

    const val REFERENCE_WIDTH = 821
    const val REFERENCE_HEIGHT = 660

    private const val A_X = 153.2746
    private const val B_X = -1852.7596
    private const val A_Y = -222.3361
    private const val B_Y = 10589.8661

    // Uporabno obmocje karte znotraj okvirja (brez zgornje glave in robov).
    private const val MAP_LEFT = 10.0
    private const val MAP_RIGHT = 812.0
    private const val MAP_TOP = 49.0
    private const val MAP_BOTTOM = 651.0

    /** Obseg slike: dolzina 12,15-17,39, sirina 44,70-47,41. */
    data class Pixel(val x: Float, val y: Float)

    /**
     * Vrne piksel na sliki podane velikosti ali null, ce:
     *  - slika ni v pricakovanem razmerju stranic (ARSO je spremenil izsek),
     *  - je tocka zunaj karte.
     *
     * Ce je slika enakomerno pomanjsana ali povecana, se preslikava ustrezno skalira.
     */
    fun toPixel(lat: Double, lng: Double, width: Int, height: Int): Pixel? {
        if (width <= 0 || height <= 0) return null

        val scaleX = width.toDouble() / REFERENCE_WIDTH
        val scaleY = height.toDouble() / REFERENCE_HEIGHT
        // Dovolimo le enakomerno skaliranje; sicer je izsek drugacen in bi marker lagal.
        if (kotlin.math.abs(scaleX - scaleY) > 0.01) return null

        val x = A_X * lng + B_X
        val y = A_Y * lat + B_Y
        if (x < MAP_LEFT || x > MAP_RIGHT || y < MAP_TOP || y > MAP_BOTTOM) return null

        return Pixel((x * scaleX).toFloat(), (y * scaleY).toFloat())
    }

    /** Za diagnostiko na zaslonu aplikacije. */
    fun describeMismatch(width: Int, height: Int): String =
        "slika ${width}x$height (pricakovano ${REFERENCE_WIDTH}x$REFERENCE_HEIGHT)"
}
