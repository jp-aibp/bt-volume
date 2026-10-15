package eu.aibusinesspro.btvolume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the service after the phone restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, VolumeService::class.java))
        }
    }
}
