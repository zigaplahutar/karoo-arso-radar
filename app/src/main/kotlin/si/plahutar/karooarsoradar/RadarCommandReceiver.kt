package si.plahutar.karooarsoradar

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Gumbi v podatkovnem polju se izrisujejo v procesu Karoo OS, zato pritiska ne
 * moremo prestreci neposredno. Vsak gumb dobi PendingIntent, ki v nasem procesu
 * sprozi ta sprejemnik.
 */
class RadarCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra(EXTRA_COMMAND)) {
            CMD_ZOOM_IN -> RadarRepository.zoomIn()
            CMD_ZOOM_OUT -> RadarRepository.zoomOut()
            CMD_PLAY -> RadarRepository.togglePlay()
            CMD_REFRESH -> RadarRepository.refreshAsync()
        }
    }

    companion object {
        const val EXTRA_COMMAND = "command"
        const val CMD_ZOOM_IN = "zoom_in"
        const val CMD_ZOOM_OUT = "zoom_out"
        const val CMD_PLAY = "play"
        const val CMD_REFRESH = "refresh"

        fun pendingIntent(context: Context, command: String): PendingIntent {
            val intent = Intent(context, RadarCommandReceiver::class.java)
                .setAction("si.plahutar.karooarsoradar.$command")
                .putExtra(EXTRA_COMMAND, command)
            return PendingIntent.getBroadcast(
                context,
                command.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
