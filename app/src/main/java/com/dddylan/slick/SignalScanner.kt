package com.dddylan.slick

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects nearby Wi-Fi networks and Bluetooth LE advertisers for the Signal scene.
 * Nothing is stored or sent anywhere: the page polls read() and the list lives in memory only.
 */
class SignalScanner(private val ctx: Context, private val status: (String, String) -> Unit) {
    private class Sig(val id: String, val type: String, var name: String, var rssi: Int, var seen: Long)

    private val map = ConcurrentHashMap<String, Sig>()
    private val handler = Handler(Looper.getMainLooper())
    private val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
    private val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var wifiOn = false
    private var bleOn = false
    var running = false
        private set

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = readWifi()
    }

    private val wifiLoop = object : Runnable {
        override fun run() {
            if (!wifiOn) return
            // Android throttles this to about 4 scans per 2 minutes unless throttling is off in Developer options.
            @Suppress("DEPRECATION") try { wifi?.startScan() } catch (_: Exception) {}
            readWifi()
            handler.postDelayed(this, 6000)
        }
    }

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = addBle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { addBle(it) } }
        override fun onScanFailed(errorCode: Int) { status("error", "Bluetooth scan failed (code $errorCode).") }
    }

    // Long unfiltered scans get downgraded by Android after 30 minutes, so restart well before that.
    private val bleRestart = object : Runnable {
        override fun run() {
            if (!bleOn) return
            stopBle(); startBle()
            handler.postDelayed(this, 9 * 60 * 1000L)
        }
    }

    fun start(wifiAllowed: Boolean, bleAllowed: Boolean) {
        stop()
        running = true
        val notes = mutableListOf<String>()
        if (wifiAllowed && wifi != null) {
            val lm = ctx.getSystemService(LocationManager::class.java)
            if (Build.VERSION.SDK_INT >= 28 && lm != null && !lm.isLocationEnabled) notes += "Turn on Location to see Wi-Fi networks."
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            wifiOn = true
            handler.post(wifiLoop)
        } else if (!wifiAllowed) notes += "Wi-Fi needs the location permission."
        if (bleAllowed) {
            if (bt == null || !bt.isEnabled) notes += "Turn on Bluetooth to see nearby devices."
            else { bleOn = true; startBle(); handler.postDelayed(bleRestart, 9 * 60 * 1000L) }
        } else notes += "Bluetooth needs the Nearby devices permission."
        status(if (wifiOn || bleOn) "running" else "error",
            if (notes.isEmpty()) "Scanning for Wi-Fi and Bluetooth signals." else notes.joinToString(" "))
    }

    fun stop() {
        if (wifiOn) { try { ctx.unregisterReceiver(wifiReceiver) } catch (_: Exception) {} }
        wifiOn = false
        handler.removeCallbacks(wifiLoop)
        handler.removeCallbacks(bleRestart)
        if (bleOn) stopBle()
        bleOn = false
        running = false
    }

    /** JSON array: i = id, t = "w"/"b", n = name, r = RSSI dBm, a = ms since last heard. Signals unheard for 2 min are dropped. */
    fun read(): String {
        val now = SystemClock.elapsedRealtime()
        val out = JSONArray()
        val it = map.values.iterator()
        while (it.hasNext()) {
            val s = it.next()
            val age = now - s.seen
            if (age > 120_000) { it.remove(); continue }
            out.put(JSONObject().put("i", s.id).put("t", s.type).put("n", s.name).put("r", s.rssi).put("a", age))
        }
        return out.toString()
    }

    @SuppressLint("MissingPermission")
    private fun readWifi() {
        val list = try { wifi?.scanResults } catch (_: SecurityException) { null } ?: return
        val now = SystemClock.elapsedRealtime()
        for (r in list) {
            // ScanResult.timestamp is in microseconds since boot; use it so stale cached results look stale.
            val heard = r.timestamp / 1000
            val seen = if (heard in 1..now) heard else now
            @Suppress("DEPRECATION") val ssid = r.SSID?.trim('"').orEmpty()
            val id = "w:" + r.BSSID
            val s = map[id]
            if (s == null) map[id] = Sig(id, "w", ssid, r.level, seen)
            else if (seen >= s.seen) { s.rssi = r.level; s.seen = seen; if (ssid.isNotEmpty()) s.name = ssid }
        }
    }

    @SuppressLint("MissingPermission")
    private fun addBle(r: ScanResult) {
        val id = "b:" + r.device.address
        val name = r.scanRecord?.deviceName.orEmpty()   // the advertised name; reading device.name would need another permission
        val now = SystemClock.elapsedRealtime()
        val s = map[id]
        if (s == null) map[id] = Sig(id, "b", name, r.rssi, now)
        else { s.rssi = r.rssi; s.seen = now; if (name.isNotEmpty()) s.name = name }
    }

    @SuppressLint("MissingPermission")
    private fun startBle() {
        try {
            bt?.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), bleCallback)
        } catch (e: SecurityException) { status("error", "Bluetooth scanning was not allowed.") }
    }

    @SuppressLint("MissingPermission")
    private fun stopBle() {
        try { bt?.bluetoothLeScanner?.stopScan(bleCallback) } catch (_: Exception) {}
    }
}
