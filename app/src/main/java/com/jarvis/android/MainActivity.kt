
package com.jarvis.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pendingWebPermission: PermissionRequest? = null

    private val appUrl = "https://jarvis-web-1-9zml.onrender.com"
    private val permissionCode = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false

        webView.addJavascriptInterface(
            JarvisAndroidBridge(),
            "JarvisAndroid"
        )

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                url: String
            ): Boolean {
                val uri = Uri.parse(url)

                if (uri.host == "jarvis-web-1-9zml.onrender.com") {
                    return false
                }

                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val permissions = mutableListOf<String>()

                    if (request.resources.contains(
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE
                        )) {
                        permissions.add(Manifest.permission.CAMERA)
                    }

                    if (request.resources.contains(
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE
                        )) {
                        permissions.add(Manifest.permission.RECORD_AUDIO)
                    }

                    val missing = permissions.filter {
                        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
                    }

                    if (missing.isEmpty()) {
                        request.grant(request.resources)
                    } else {
                        pendingWebPermission = request
                        requestPermissions(missing.toTypedArray(), permissionCode)
                    }
                }
            }
        }

        setContentView(webView)
        webView.loadUrl(appUrl)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == permissionCode) {
            val request = pendingWebPermission
            pendingWebPermission = null

            if (request != null) {
                val allGranted = grantResults.isNotEmpty() &&
                    grantResults.all {
                        it == PackageManager.PERMISSION_GRANTED
                    }

                if (allGranted) {
                    request.grant(request.resources)
                } else {
                    request.deny()
                }
            }
        }
    }

    inner class JarvisAndroidBridge {

        private fun result(ok: Boolean, message: String): String {
            return JSONObject()
                .put("ok", ok)
                .put(if (ok) "message" else "error", message)
                .toString()
        }

        @JavascriptInterface
        fun isBridgeReady(): Boolean = true

        @JavascriptInterface
        fun getCapabilities(): String {
            return "openApp,closeApp,setFlashlight,setAlarm,adjustVolume"
        }

        @JavascriptInterface
        fun openApp(appName: String): String {
            return try {
                val apps = packageManager.getInstalledApplications(
                    PackageManager.GET_META_DATA
                )

                val match = apps.firstOrNull {
                    packageManager.getApplicationLabel(it)
                        .toString().equals(appName, ignoreCase = true)
                }

                if (match == null) {
                    return result(false, "App not found: $appName")
                }

                val launchIntent =
                    packageManager.getLaunchIntentForPackage(match.packageName)

                if (launchIntent == null) {
                    result(false, "Cannot launch $appName")
                } else {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    result(true, "Opening $appName")
                }
            } catch (e: Exception) {
                result(false, e.message ?: "Could not open app")
            }
        }

        @JavascriptInterface
        fun closeApp(appName: String?): String {
            return if (appName.isNullOrBlank() ||
                appName.equals("JARVIS", true)
            ) {
                runOnUiThread { finish() }
                result(true, "Closing JARVIS")
            } else {
                result(false, "Android does not allow JARVIS to force-close other apps")
            }
        }

        @JavascriptInterface
        fun setFlashlight(enabled: Boolean): String {
            if (checkSelfPermission(Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                runOnUiThread {
                    requestPermissions(
                        arrayOf(Manifest.permission.CAMERA),
                        permissionCode
                    )
                }
                return result(false, "Camera permission required. Please allow it and try again.")
            }

            return try {
                val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull {
                    cameraManager.getCameraCharacteristics(it)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }

                if (cameraId == null) {
                    result(false, "No flashlight found")
                } else {
                    cameraManager.setTorchMode(cameraId, enabled)
                    result(true, if (enabled) "Flashlight on" else "Flashlight off")
                }
            } catch (e: Exception) {
                result(false, e.message ?: "Flashlight control failed")
            }
        }

        @JavascriptInterface
        fun setAlarm(hour24: Int, minute: Int, label: String?): String {
            return try {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour24)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label ?: "JARVIS alarm")
                    putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                }
                startActivity(intent)
                result(true, "Opening alarm setup")
            } catch (e: Exception) {
                result(false, e.message ?: "Could not set alarm")
            }
        }

        @JavascriptInterface
        fun adjustVolume(actionOrPercent: String, stream: String?): String {
            return try {
                val audio = getSystemService(AUDIO_SERVICE) as AudioManager

                val streamType = when (stream?.lowercase()) {
                    "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    else -> AudioManager.STREAM_MUSIC
                }

                val value = actionOrPercent.lowercase()

                when (value) {
                    "up" -> audio.adjustStreamVolume(
                        streamType, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI
                    )
                    "down" -> audio.adjustStreamVolume(
                        streamType, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI
                    )
                    "mute" -> audio.adjustStreamVolume(
                        streamType, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI
                    )
                    "unmute" -> audio.adjustStreamVolume(
                        streamType, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI
                    )
                    else -> {
                        val percent = value.toIntOrNull()
                            ?: return result(false, "Invalid volume level")

                        val max = audio.getStreamMaxVolume(streamType)
                        val level = (max * percent.coerceIn(0, 100)) / 100

                        audio.setStreamVolume(
                            streamType, level, AudioManager.FLAG_SHOW_UI
                        )
                    }
                }

                result(true, "Volume updated")
            } catch (e: Exception) {
                result(false, e.message ?: "Volume control failed")
            }
        }

        @JavascriptInterface
        fun readNotifications(): String {
            return result(false, "Notification access still needs to be configured")
        }

        @JavascriptInterface
        fun searchFiles(query: String, mediaType: String?): String {
            return result(false, "File search is not implemented yet")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
