package eu.aibusinesspro.btvolume

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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

private val MEDIA = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()

/**
 * The Bluetooth output that media currently plays to, or null when media plays elsewhere
 * (for example to the phone speaker while headphones stay connected) or nothing is connected.
 * Before Android 13 the routing cannot be read, so the first connected Bluetooth output is used.
 */
fun currentBtDevice(context: Context): BtDevice? {
    val audio = context.getSystemService(AudioManager::class.java)
    val connected = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val device = if (Build.VERSION.SDK_INT >= 33) {
        val routed = audio.getAudioDevicesForAttributes(MEDIA).firstOrNull { it.type in BLUETOOTH_OUTPUTS } ?: return null
        // The connected entry carries the same name and address that the saved values use.
        connected.firstOrNull { it.id == routed.id } ?: routed
    } else {
        connected.firstOrNull { it.type in BLUETOOTH_OUTPUTS } ?: return null
    }
    val name = device.productName?.toString().orEmpty().ifBlank { "Bluetooth" }
    // The address may be partly masked by Android, but it stays the same for the same device.
    val key = device.address.ifBlank { name }
    return BtDevice(key, name)
}

fun prefs(context: Context): SharedPreferences =
    context.getSharedPreferences("volumes", Context.MODE_PRIVATE)

/** Working state of the service (sessions, warning). Separate, because changes of [prefs] trigger an update. */
fun statePrefs(context: Context): SharedPreferences =
    context.getSharedPreferences("state", Context.MODE_PRIVATE)

/** Why the saved attenuation is not applied right now, or empty when it is. */
fun currentWarning(context: Context): String = statePrefs(context).getString(KEY_WARNING, "").orEmpty()

const val KEY_WARNING = "warning"

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

/** The slider step as text, with the decimal separator of the phone's language. */
fun formatStep(): String = String.format(Locale.getDefault(), "%.1f", STEP_FINE)
