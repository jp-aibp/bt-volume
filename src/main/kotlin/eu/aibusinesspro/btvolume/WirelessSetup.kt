package eu.aibusinesspro.btvolume

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.IOException
import java.math.BigInteger
import java.net.NetworkInterface
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One-time setup without a computer.
 *
 * The user turns on Wireless debugging and opens "Pair device with pairing code". They type the
 * code into this notification (so the Settings dialog stays open), then the app pairs with the
 * phone's own adbd, runs `pm grant <this app> android.permission.DUMP` and disconnects.
 *
 * The ADB key exists only in memory for one attempt, so the app cannot reconnect later.
 */
object WirelessSetup {

    const val ACTION_PAIRING_CODE = "eu.aibusinesspro.btvolume.PAIRING_CODE"
    private const val CHANNEL = "setup"
    private const val NOTIFICATION_ID = 2
    private const val KEY_CODE = "code"
    private const val TAG = "BtVolume"
    private const val TIMEOUT_MS = 15_000L
    private const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"

    private val running = AtomicBoolean(false)

    /** Shows the notification where the user types the pairing code. */
    fun showPrompt(context: Context, message: String = context.getString(R.string.pairing_text)) {
        val input = RemoteInput.Builder(KEY_CODE).setLabel(context.getString(R.string.pairing_code_label)).build()
        val intent = Intent(context, VolumeService::class.java).setAction(ACTION_PAIRING_CODE)
        // Mutable so that the system can add the typed code; the intent is explicit.
        val pending = PendingIntent.getService(
            context, 3, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_speaker), context.getString(R.string.pairing_enter_code), pending,
        ).addRemoteInput(input).build()
        notify(context, message, action)
    }

    /** Reads the code typed into the notification and runs the setup in the background. */
    fun onCodeEntered(context: Context, intent: Intent, onGranted: () -> Unit) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.filter { it.isDigit() }?.toString()
        if (code.isNullOrEmpty()) {
            showPrompt(context)
            return
        }
        if (!running.compareAndSet(false, true)) return
        notify(context, context.getString(R.string.pairing_working), null)
        thread(name = "setup") {
            try {
                grantDump(context, code)
                notify(context, context.getString(R.string.pairing_done), null)
                onGranted()
            } catch (e: Exception) {
                Log.w(TAG, "wireless setup failed", e)
                showPrompt(context, context.getString(R.string.pairing_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                running.set(false)
            }
        }
    }

    private fun grantDump(context: Context, code: String) {
        val pairing = findPairingService(context) ?: throw IOException(context.getString(R.string.pairing_no_dialog))
        Adb().use { adb ->
            adb.pair(pairing.first, pairing.second, code)
            if (!adb.connectTls(context, TIMEOUT_MS)) throw IOException(context.getString(R.string.pairing_no_connection))
            adb.openStream("shell:pm grant ${context.packageName} ${Manifest.permission.DUMP}").use { stream ->
                val output = stream.openInputStream().bufferedReader().readText().trim()
                if (output.isNotEmpty()) Log.i(TAG, "pm grant: $output")
            }
            adb.disconnect()
        }
        if (context.checkSelfPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            throw IOException(context.getString(R.string.pairing_not_granted))
        }
    }

    /**
     * Finds the pairing service that Wireless debugging announces over mDNS while the
     * "Pair device with pairing code" dialog is open. Services of other devices on the
     * same Wi-Fi are skipped by comparing with this phone's own addresses.
     */
    private fun findPairingService(context: Context): Pair<String, Int>? {
        val nsd = context.getSystemService(NsdManager::class.java)
        val own = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .toSet()
        val found = CountDownLatch(1)
        var result: Pair<String, Int>? = null
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION") // registerServiceInfoCallback needs API 34
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress?.substringBefore('%') ?: return
                        if (host in own && result == null) {
                            result = host to info.port
                            found.countDown()
                        }
                    }
                })
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = found.countDown()
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        nsd.discoverServices(PAIRING_SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery)
        try {
            found.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
        return result
    }

    private fun notify(context: Context, text: String, action: Notification.Action?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.pairing_channel), NotificationManager.IMPORTANCE_HIGH),
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_speaker)
            .setContentTitle(context.getString(R.string.pairing_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
        if (action != null) builder.addAction(action)
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    /** ADB client with a fresh key; it only lives for one setup attempt. */
    private class Adb : AbsAdbConnectionManager() {
        private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val certificate: Certificate = selfSigned(keyPair)

        init {
            setApi(Build.VERSION.SDK_INT)
        }

        override fun getPrivateKey(): PrivateKey = keyPair.private
        override fun getCertificate(): Certificate = certificate
        override fun getDeviceName(): String = "BT Volume"

        private fun selfSigned(keyPair: KeyPair): Certificate {
            val name = X500Name("CN=BT Volume")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 60_000), Date(now + 365L * 24 * 3600 * 1000), name, keyPair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            return JcaX509CertificateConverter().getCertificate(builder.build(signer))
        }
    }
}
