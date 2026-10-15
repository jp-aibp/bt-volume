package eu.aibusinesspro.btvolume

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var value: TextView
    private lateinit var slider: SeekBar
    private lateinit var quieter: Button
    private lateinit var louder: Button
    private lateinit var saved: TextView
    private lateinit var setup: View
    private var device: BtDevice? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, VolumeService::class.java))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // Re-post the notification now that it may be shown.
        startForegroundService(Intent(this, VolumeService::class.java))
    }

    override fun onStart() {
        super.onStart()
        prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        getSystemService(AudioManager::class.java)
            .registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        refresh()
    }

    override fun onStop() {
        prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(deviceCallback)
        super.onStop()
    }

    private fun refresh() {
        device = currentBtDevice(this)
        val current = device
        val enabled = current != null
        slider.isEnabled = enabled
        quieter.isEnabled = enabled
        louder.isEnabled = enabled
        setup.visibility =
            if (checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED) View.GONE else View.VISIBLE
        if (current == null) {
            status.text = getString(R.string.status_no_device)
            value.text = "–"
        } else {
            val db = getDb(this, current.key)
            status.text = getString(R.string.status_connected, current.name)
            value.text = formatDb(db)
            slider.progress = ((db - MIN_DB) / STEP_FINE).roundToInt()
        }
        val list = savedDevices(this)
        saved.text = if (list.isEmpty()) "" else
            getString(R.string.saved_devices) + "\n" + list.joinToString("\n") { (name, db) -> "• $name: ${formatDb(db)}" }
    }

    private fun change(delta: Float) {
        val current = device ?: return
        setDb(this, current, getDb(this, current.key) + delta)
    }

    private fun buildUi(): View {
        val pad = (16 * resources.displayMetrics.density).roundToInt()

        status = TextView(this).apply { textSize = 18f }
        value = TextView(this).apply {
            textSize = 44f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, pad)
        }
        slider = SeekBar(this).apply {
            max = (-MIN_DB / STEP_FINE).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    val current = device
                    if (fromUser && current != null) setDb(this@MainActivity, current, MIN_DB + progress * STEP_FINE)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        quieter = Button(this).apply {
            text = getString(R.string.step_down, formatStep())
            setOnClickListener { change(-STEP_FINE) }
        }
        louder = Button(this).apply {
            text = getString(R.string.step_up, formatStep())
            setOnClickListener { change(STEP_FINE) }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(quieter, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(louder, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val help = TextView(this).apply {
            setPadding(0, pad, 0, pad)
            text = getString(R.string.help)
        }
        saved = TextView(this)
        setup = buildSetup(pad)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(setup)
            addView(status)
            addView(value)
            addView(slider)
            addView(buttons)
            addView(help)
            addView(saved)
            val supportUrl = getString(R.string.support_url)
            if (supportUrl.isNotBlank()) {
                addView(Button(this@MainActivity).apply {
                    text = getString(R.string.support)
                    setOnClickListener { open(supportUrl) }
                })
            }
        }
        return ScrollView(this).apply {
            addView(column)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
                insets
            }
        }
    }

    /** Shown until the DUMP permission is granted. */
    private fun buildSetup(pad: Int): View {
        val command = "adb shell pm grant $packageName android.permission.DUMP"
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, pad)
            addView(TextView(this@MainActivity).apply {
                textSize = 20f
                text = getString(R.string.setup_title)
            })
            addView(TextView(this@MainActivity).apply { text = getString(R.string.setup_text) })
            addView(TextView(this@MainActivity).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, pad / 2, 0, pad / 2)
                text = command
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(this@MainActivity).apply {
                    text = getString(R.string.copy_command)
                    setOnClickListener {
                        getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("adb", command))
                        Toast.makeText(this@MainActivity, R.string.command_copied, Toast.LENGTH_SHORT).show()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(this@MainActivity).apply {
                    text = getString(R.string.setup_guide)
                    setOnClickListener { open(getString(R.string.guide_url)) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }

    private fun open(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
