package si.plahutar.karooarsoradar

import android.util.Log
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension

/**
 * Vstopna tocka razsiritve. Karoo OS se poveze na ta service prek intent-filtra
 * io.hammerhead.karooext.KAROO_EXTENSION (glej AndroidManifest.xml).
 *
 * Prvi argument mora biti enak atributu id v res/xml/extension_info.xml.
 */
class ArsoRadarExtension : KarooExtension("arso-radar", "1.1") {

    private val karooSystem by lazy { KarooSystemService(applicationContext) }

    override val types by lazy {
        listOf(RadarDataType(extension))
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem.connect { connected ->
            Log.d("ArsoRadar", "Povezava s Karoo sistemom: $connected")
        }
        RadarRepository.karooSystem = karooSystem
    }

    override fun onDestroy() {
        if (RadarRepository.karooSystem === karooSystem) {
            RadarRepository.karooSystem = null
        }
        karooSystem.disconnect()
        super.onDestroy()
    }
}
