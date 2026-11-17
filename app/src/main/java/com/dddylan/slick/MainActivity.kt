package com.dddylan.slick

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
                fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                fileCallback = null
            }
        }
    }

    // ---------- WebView plumbing ----------

    private inner class AssetClient : WebViewClient() {
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
            return try {
                @Suppress("DEPRECATION")
                startActivityForResult(params.createIntent(), REQ_FILE)
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
        if (isFinishing) stopAll()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val REQ_MIC = 1
        const val REQ_PROJECTION = 2
        const val REQ_FILE = 3
    }
}
