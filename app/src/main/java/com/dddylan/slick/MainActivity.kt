package com.dddylan.slick

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.IOException

/**
 * A full-screen WebView running the scene, plus the native audio routes.
 * The scene is served from a reserved https origin so it counts as a secure page
 * (needed for the in-page microphone option).
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var vis: VisualizerRoute? = null
    private var resumeVisOnStart = false
    private var micGranted: (() -> Unit)? = null
    private var micDenied: (() -> Unit)? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var signals: SignalScanner
    private var signalsWanted = false
    private var permGranted: ((Map<String, Boolean>) -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferHighestRefreshRate()
        WebView.setWebContentsDebuggingEnabled(true) // lets chrome://inspect on a PC see the scene's console

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            webViewClient = AssetClient()
            webChromeClient = Chrome()
            addJavascriptInterface(Bridge(), "SlickNative")
        }
        setContentView(web)
        hideSystemBars()

        signals = SignalScanner(this) { state, message ->
            runOnUiThread {
                web.evaluateJavascript("window.__signalStatus&&__signalStatus(${JSONObject.quote(state)},${JSONObject.quote(message)})", null)
            }
        }
        AudioEngine.listener = { route, state, message ->
            runOnUiThread {
                web.evaluateJavascript(
                    "window.__slickStatus&&__slickStatus(${JSONObject.quote(route)},${JSONObject.quote(state)},${JSONObject.quote(message)})",
                    null,
                )
            }
        }
        web.loadUrl("https://$HOST/assets/index.html")
    }

    // ---------- bridge the page calls ----------

    inner class Bridge {
        @JavascriptInterface fun read(): String = AudioEngine.read()
        @JavascriptInterface fun start(route: String) = runOnUiThread { if (route == "cap") startCapture() else startVisualizer() }
        @JavascriptInterface fun stop() = runOnUiThread { stopAll() }

        /** "auto" rotates with the sensor even when the phone's own rotation lock is on. */
        @JavascriptInterface fun setOrientation(mode: String) = runOnUiThread {
            requestedOrientation = when (mode) {
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                "reverseLandscape" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "reversePortrait" -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            }
        }

        @JavascriptInterface fun currentOrientation(): String = currentOrientationName()

        @JavascriptInterface fun startSignals() = runOnUiThread { startSignalScan() }
        @JavascriptInterface fun stopSignals() = runOnUiThread { signalsWanted = false; signals.stop() }
        @JavascriptInterface fun readSignals(): String = signals.read()
    }

    private fun startVisualizer() {
        stopAll()
        withMic(
            granted = {
                try {
                    vis = VisualizerRoute().also { it.start() }
                    AudioEngine.route = "phone"
                    AudioEngine.status("vis", "running", "Listening to phone audio. Play something in Spotify or any app.")
                } catch (e: Exception) {
                    vis = null
                    AudioEngine.status("vis", "error", "Phone audio could not start on this device (${e.javaClass.simpleName}). Try Capture or Mic.")
                }
            },
            denied = {
                AudioEngine.status("vis", "error", "Android files visualizers under the microphone permission. Slick reads the phone's output, not the mic, but needs that permission to do it.")
            },
        )
    }

    private fun startCapture() {
        stopAll()
        withMic(
            granted = {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                @Suppress("DEPRECATION")
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
            },
            denied = {
                AudioEngine.status("cap", "error", "Capture needs the microphone permission. Allow it in Android settings to use this route.")
            },
        )
    }

    /** Wi-Fi results need location; Bluetooth LE scanning needs Nearby devices on Android 12+, location before that. */
    private fun startSignalScan() {
        signalsWanted = true
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) wanted += Manifest.permission.BLUETOOTH_SCAN
        withPerms(wanted.toTypedArray()) { got ->
            if (!signalsWanted) return@withPerms
            val loc = got[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val ble = if (Build.VERSION.SDK_INT >= 31) got[Manifest.permission.BLUETOOTH_SCAN] == true else loc
            signals.start(loc, ble)
        }
    }

    private fun withPerms(perms: Array<String>, done: (Map<String, Boolean>) -> Unit) {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { done(perms.associateWith { true }); return }
        permGranted = { res -> done(perms.associateWith { p -> res[p] ?: (checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) }) }
        requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    private fun stopAll() {
        vis?.stop()
        vis = null
        stopService(Intent(this, CaptureService::class.java))
        AudioEngine.route = "off"
    }

    // ---------- permissions and results ----------

    private fun withMic(granted: () -> Unit, denied: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            granted()
        } else {
            micGranted = granted
            micDenied = denied
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val cb = permGranted
            permGranted = null
            cb?.invoke(permissions.indices.associate { permissions[it] to (grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED) })
            return
        }
        if (requestCode != REQ_MIC) return
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        val g = micGranted
        val d = micDenied
        micGranted = null
        micDenied = null
        if (ok) g?.invoke() else d?.invoke()
    }

    @Deprecated("Platform Activity result API; this shell avoids AndroidX on purpose.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    startForegroundService(
                        Intent(this, CaptureService::class.java)
                            .putExtra(CaptureService.EXTRA_CODE, resultCode)
                            .putExtra(CaptureService.EXTRA_DATA, data)
                    )
                } else {
                    AudioEngine.status("cap", "error", "Capture was not allowed. Phone audio works without it.")
                }
            }
            REQ_FILE -> {
                // Multi-select returns ClipData; single-select returns data.data.
                val uris: Array<Uri>? = if (resultCode != RESULT_OK || data == null) null else {
                    data.clipData?.let { c -> Array(c.itemCount) { c.getItemAt(it).uri } } ?: data.data?.let { arrayOf(it) }
                }
                fileCallback?.onReceiveValue(uris)
                fileCallback = null
            }
        }
    }

    // ---------- WebView plumbing ----------

    private inner class AssetClient : WebViewClient() {
        // A scene switch loads a new page; scanning only runs while the Signal scene asks for it.
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            signalsWanted = false
            signals.stop()
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host != HOST) return null
            val path = url.path?.removePrefix("/assets/") ?: return null
            return try {
                WebResourceResponse(mimeFor(path), "utf-8", assets.open(path))
            } catch (e: IOException) {
                WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), null)
            }
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources) {
                    withMic(
                        granted = { request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) },
                        denied = { request.deny() },
                    )
                } else {
                    request.deny()
                }
            }
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            // Build the picker ourselves so "image/*,video/*" and multi-select both work.
            val types = params.acceptTypes.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
            val intent = Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (types.size == 1) types[0] else "*/*")
            if (types.size > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
            if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_FILE)
                true
            } catch (e: Exception) {
                fileCallback = null
                false
            }
        }
    }

    private fun mimeFor(path: String) = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "js" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    // ---------- display ----------

    /** Name of the orientation the screen is in right now, so the lock can hold exactly this one. */
    private fun currentOrientationName(): String {
        @Suppress("DEPRECATION")
        val rot = windowManager.defaultDisplay.rotation
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val naturalPortrait = (rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180) != land
        return if (naturalPortrait) when (rot) {
            Surface.ROTATION_0 -> "portrait"
            Surface.ROTATION_90 -> "landscape"
            Surface.ROTATION_180 -> "reversePortrait"
            else -> "reverseLandscape"
        } else when (rot) {
            Surface.ROTATION_0 -> "landscape"
            Surface.ROTATION_90 -> "reversePortrait"
            Surface.ROTATION_180 -> "reverseLandscape"
            else -> "portrait"
        }
    }

    /** Ask for the panel's fastest mode (90/120 Hz where available) at the current resolution. */
    private fun preferHighestRefreshRate() {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // ---------- lifecycle ----------

    override fun onStart() {
        super.onStart()
        if (signalsWanted && !signals.running) startSignalScan()
        if (resumeVisOnStart) {
            resumeVisOnStart = false
            startVisualizer()
        }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onStop() {
        if (signals.running) signals.stop()   // signalsWanted stays set, so returning to the app resumes scanning
        // Release the output-mix tap while Slick is out of sight; capture keeps running in its service.
        if (vis != null) {
            vis?.stop()
            vis = null
            resumeVisOnStart = true
        }
        super.onStop()
    }

    override fun onDestroy() {
        AudioEngine.listener = null
        signals.stop()
        if (isFinishing) stopAll()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val REQ_MIC = 1
        const val REQ_PROJECTION = 2
        const val REQ_FILE = 3
        const val REQ_PERMS = 4
    }
}
