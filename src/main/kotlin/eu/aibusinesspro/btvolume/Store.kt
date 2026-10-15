package eu.aibusinesspro.btvolume

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceInfo
import android.media.AudioManager
import java.util.Locale

/** Lowest attenuation the user can set, in dB. 0 dB = no change. */
const val MIN_DB = -40f

/** Step of the in-app slider and buttons. */
const val STEP_FINE = 0.5f

/** Step of the notification buttons. */
const val STEP_NOTIFICATION = 1f

private val BLUETOOTH_OUTPUTS = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
)

data class BtDevice(val key: String, val name: String)

/** The connected Bluetooth audio output, or null when none is connected. */
fun currentBtDevice(context: Context): BtDevice? {
    val audio = context.getSystemService(AudioManager::class.java)
    val device = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .firstOrNull { it.type in BLUETOOTH_OUTPUTS } ?: return null
    val name = device.productName?.toString().orEmpty().ifBlank { "Bluetooth" }
    // The address may be partly masked by Android, but it stays the same for the same device.
    val key = device.address.ifBlank { name }
    return BtDevice(key, name)
}

fun prefs(context: Context): SharedPreferences =
    context.getSharedPreferences("volumes", Context.MODE_PRIVATE)

fun getDb(context: Context, key: String): Float = prefs(context).getFloat("db:$key", 0f)

fun setDb(context: Context, device: BtDevice, db: Float) {
    prefs(context).edit()
        .putFloat("db:${device.key}", db.coerceIn(MIN_DB, 0f))
        .putString("name:${device.key}", device.name)
        .apply()
}

/** All devices that have a saved value, as (name, dB). */
fun savedDevices(context: Context): List<Pair<String, Float>> {
    val all = prefs(context).all
    return all.keys.filter { it.startsWith("name:") }.map { nameKey ->
        val key = nameKey.removePrefix("name:")
        (all[nameKey] as String) to ((all["db:$key"] as? Float) ?: 0f)
    }.sortedBy { it.first }
}

fun formatDb(db: Float): String = String.format(Locale.getDefault(), "%.1f dB", db)
