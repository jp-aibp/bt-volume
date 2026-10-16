package eu.aibusinesspro.btvolume

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.io.IOException

/**
 * Keeps the attenuation of the connected Bluetooth device applied.
 *
 * A DynamicsProcessing effect with negative input gain is attached to the audio session
 * of every app that plays to the Bluetooth output, so it also goes below the lowest
 * system volume step. A single global effect (session 0) is not used: on Pixel with
 * Android 17 it does not reach tracks that are not spatialized.
 *
 * Sessions come from two sources: players that announce them with
 * ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION (works without setup, e.g. Spotify), and
 * `dumpsys media.audio_flinger` when the DUMP permission is granted (all apps).
 */
class VolumeService : Service() {

    private val worker = HandlerThread("volume").apply { start() }
    private val handler = Handler(worker.looper)
    private val updateTask = Runnable { update() }
    private val notificationTask = Runnable { showNotification() }

    // Touched only on the worker thread.
    private val effects = mutableMapOf<Int, DynamicsProcessing>()
    private val announced = mutableSetOf<Int>()

    @Volatile private var device: BtDevice? = null
    @Volatile private var error: String? = null
    @Volatile private var basicMode = false
    private var retries = 0

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = requestUpdate()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = requestUpdate()
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            requestUpdate()
            // A new track can show up in the audio server a moment later.
            handler.postDelayed(updateTask, 1000)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> requestUpdate() }

    // Runs on the worker thread (registered with its handler).
    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
            if (session <= 0) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> announced += session
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> announced -= session
            }
            // Players that die without closing their session would otherwise pile up.
            while (announced.size > MAX_ANNOUNCED) announced.remove(announced.first())
            // Kept so that a restarted app still knows the sessions of players that are already playing.
            state().edit().putStringSet(KEY_ANNOUNCED, announced.map { it.toString() }.toSet()).apply()
            retries = 0
            update()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        showNotification()
        prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        val audio = getSystemService(AudioManager::class.java)
        // Calls onAudioDevicesAdded right away with the devices connected now.
        audio.registerAudioDeviceCallback(deviceCallback, handler)
        audio.registerAudioPlaybackCallback(playbackCallback, handler)
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        // The players are other apps, so the receiver must be exported.
        announced += state().getStringSet(KEY_ANNOUNCED, emptySet())!!.mapNotNull { it.toIntOrNull() }
        registerReceiver(sessionReceiver, filter, null, handler, if (Build.VERSION.SDK_INT >= 33) RECEIVER_EXPORTED else 0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == WirelessSetup.ACTION_PAIRING_CODE) {
            WirelessSetup.onCodeEntered(this, intent) { requestUpdate() }
        }
        val current = currentBtDevice(this)
        if (current != null) {
            when (intent?.action) {
                ACTION_LOUDER -> setDb(this, current, getDb(this, current.key) + STEP_NOTIFICATION)
                ACTION_QUIETER -> setDb(this, current, getDb(this, current.key) - STEP_NOTIFICATION)
            }
        }
        showNotification()
        requestUpdate()
        return START_STICKY
    }

    override fun onDestroy() {
        val audio = getSystemService(AudioManager::class.java)
        audio.unregisterAudioDeviceCallback(deviceCallback)
        audio.unregisterAudioPlaybackCallback(playbackCallback)
        unregisterReceiver(sessionReceiver)
        prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacksAndMessages(null)
        handler.post { restoreAll() }
        worker.quitSafely()
        super.onDestroy()
    }

    private fun requestUpdate() {
        handler.post {
            retries = 0
            update()
        }
    }

    // Runs on the worker thread.
    private fun update() {
        device = currentBtDevice(this)
        val db = device?.let { getDb(this, it.key) } ?: 0f
        error = null
        basicMode = checkSelfPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED
        val failed = if (db >= 0f) restoreAll() else attenuateAll(db)
        // Right after the app restarts, the audio server may still refuse the effect left by the old process.
        if (failed && retries < MAX_RETRIES) {
            retries++
            handler.postDelayed(updateTask, RETRY_DELAY_MS)
        }
        // While the slider is dragged, update the notification only once it stops;
        // Android drops notifications that are updated too often.
        handler.removeCallbacks(notificationTask)
        handler.postDelayed(notificationTask, NOTIFICATION_DELAY_MS)
    }

    /** Returns true if some session could not be handled and should be tried again. */
    private fun attenuateAll(db: Float): Boolean {
        val sessions = if (basicMode) announced.toSet() else (findSessions() ?: return false) + announced
        // Sessions that no longer play to Bluetooth (ended, or moved to the speaker) get their volume back.
        val failed = (attenuatedSessions() - sessions).filterNot(::restore) +
            sessions.filterNot { attenuate(it, db) }
        saveAttenuatedSessions(effects.keys + failed)
        return failed.isNotEmpty()
    }

    /** Returns true if some session could not be handled and should be tried again. */
    private fun restoreAll(): Boolean {
        val sessions = attenuatedSessions()
        if (sessions.isEmpty()) return false
        val failed = sessions.filterNot(::restore).toSet()
        saveAttenuatedSessions(failed)
        return failed.isNotEmpty()
    }

    private fun findSessions(): Set<Int>? {
        return try {
            bluetoothSessions()
        } catch (e: IOException) {
            error = e.message
            null
        }
    }

    private fun attenuate(session: Int, db: Float): Boolean {
        return try {
            val fx = effects.getOrPut(session) { DynamicsProcessing(PRIORITY, session, config()) }
            fx.setInputGainAllChannelsTo(db)
            fx.enabled = true
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "attenuate $session failed: $e")
            effects.remove(session)?.release()
            false
        }
    }

    /**
     * Sets the session back to 0 dB before letting go of it. Android 17 keeps an effect
     * running with its last settings even after the app releases it or is killed.
     */
    private fun restore(session: Int): Boolean {
        val fx = effects.remove(session)
            ?: try {
                // Left over from before the app was restarted; this takes over the same effect.
                DynamicsProcessing(PRIORITY, session, config())
            } catch (e: RuntimeException) {
                Log.w(TAG, "restore $session: cannot attach: $e")
                return false
            }
        return try {
            fx.setInputGainAllChannelsTo(0f)
            fx.enabled = false
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "restore $session failed: $e")
            false
        } finally {
            fx.release()
        }
    }

    /** Sessions attenuated by this or a previous run of the app (kept in case the app is killed). */
    private fun attenuatedSessions(): Set<Int> =
        effects.keys + state().getStringSet(KEY_SESSIONS, emptySet())!!.map { it.toInt() }

    private fun saveAttenuatedSessions(sessions: Set<Int>) {
        state().edit().putStringSet(KEY_SESSIONS, sessions.map { it.toString() }.toSet()).apply()
    }

    // Separate from the volume prefs, whose changes trigger an update.
    private fun state() = getSharedPreferences("state", MODE_PRIVATE)

    private fun config() = DynamicsProcessing.Config.Builder(
        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
        2,
        false, 1,
        false, 1,
        false, 1,
        false,
    ).build()

    private fun showNotification() {
        val current = device
        val text = when {
            error != null -> getString(R.string.notification_error, error)
            current == null -> getString(R.string.status_no_device)
            basicMode -> getString(R.string.notification_device_basic, current.name, formatDb(getDb(this, current.key)))
            else -> getString(R.string.notification_device, current.name, formatDb(getDb(this, current.key)))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_speaker)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
        if (current != null) {
            builder.addAction(action(getString(R.string.action_quieter), ACTION_QUIETER, 1))
            builder.addAction(action(getString(R.string.action_louder), ACTION_LOUDER, 2))
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, builder.build())
        }
    }

    private fun action(title: String, action: String, requestCode: Int): Notification.Action {
        val intent = Intent(this, VolumeService::class.java).setAction(action)
        val pending = PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_speaker), title, pending).build()
    }

    private companion object {
        const val TAG = "BtVolume"
        const val CHANNEL = "attenuation"
        const val KEY_SESSIONS = "sessions"
        const val KEY_ANNOUNCED = "announced"
        const val MAX_ANNOUNCED = 20
        const val MAX_RETRIES = 5
        const val RETRY_DELAY_MS = 2000L
        const val NOTIFICATION_DELAY_MS = 400L
        const val NOTIFICATION_ID = 1
        const val PRIORITY = 100
        const val ACTION_LOUDER = "eu.aibusinesspro.btvolume.LOUDER"
        const val ACTION_QUIETER = "eu.aibusinesspro.btvolume.QUIETER"
    }
}
