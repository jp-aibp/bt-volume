package eu.aibusinesspro.btvolume

import java.io.IOException

private val OUTPUT_DEVICES = Regex("""^\s*Output devices: (.*)$""", RegexOption.MULTILINE)

// Row of an output thread's track table: [type] id yes|no pid/ uid session ...
private val TRACK_ROW = Regex("""^\s+.*?\b(?:yes|no)\s+\d+/\s*\d+\s+(\d+)\s""", RegexOption.MULTILINE)

/**
 * Audio session IDs of all tracks the audio server plays to a Bluetooth output.
 *
 * Android has no public API for this, so it is read from `dumpsys media.audio_flinger`,
 * which needs the DUMP permission.
 */
@Throws(IOException::class)
fun bluetoothSessions(): Set<Int> {
    val process = ProcessBuilder("/system/bin/dumpsys", "media.audio_flinger").redirectErrorStream(true).start()
    val dump = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor()
    if ("Permission Denial" in dump) throw IOException("dumpsys: Permission Denial")

    val sessions = mutableSetOf<Int>()
    for (thread in dump.split("\nOutput thread ").drop(1)) {
        val devices = OUTPUT_DEVICES.find(thread)?.groupValues?.get(1) ?: continue
        if ("BLUETOOTH" !in devices && "BLE_" !in devices) continue
        // The thread's "Local log" also lists tracks that are already gone.
        for (row in TRACK_ROW.findAll(thread.substringBefore("Local log:"))) {
            val session = row.groupValues[1].toInt()
            if (session > 0) sessions += session
        }
    }
    return sessions
}
