package com.bakctv.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat

/**
 * 박CTV 촬영용 앱.
 * 웹 버전 박CTV 화면을 그대로 띄우고, CameraKeepAliveService 로
 * 화면이 꺼져도 카메라·마이크가 계속 동작하게 한다.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var pendingWebPermission: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == ACTION_EXIT) { finishAndRemoveTask(); return }

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "$userAgentString BakCTVApp"
        }

        // 링크는 앱 안에서 열기
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
        }

        // 웹페이지가 카메라·마이크를 요청하면 허용
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (hasMediaPermissions()) {
                        request.grant(request.resources)
                    } else {
                        pendingWebPermission = request
                        askPermissions()
                    }
                }
            }
        }

        if (hasMediaPermissions()) onPermissionsReady() else askPermissions()
    }

    private fun hasMediaPermissions() =
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun askPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (hasMediaPermissions()) {
            pendingWebPermission?.let { it.grant(it.resources) }
            pendingWebPermission = null
            onPermissionsReady()
        } else {
            pendingWebPermission?.deny()
            pendingWebPermission = null
            android.widget.Toast.makeText(this, "카메라와 마이크 권한이 있어야 촬영할 수 있어요.", android.widget.Toast.LENGTH_LONG).show()
            if (webView.url == null) webView.loadUrl(getString(R.string.app_url))
        }
    }

    private fun onPermissionsReady() {
        // 앱이 화면에 보이는 지금 서비스를 시작해야 화면이 꺼진 뒤에도 카메라를 쓸 수 있다
        ContextCompat.startForegroundService(this, Intent(this, CameraKeepAliveService::class.java))
        askBatteryExemptionOnce()
        if (webView.url == null) webView.loadUrl(getString(R.string.app_url))
    }

    /** 배터리 최적화 예외 요청 (처음 한 번만). 절전 때문에 연결이 끊기는 것을 막는다. */
    @SuppressLint("BatteryLife")
    private fun askBatteryExemptionOnce() {
        val prefs = getSharedPreferences("bakctv", MODE_PRIVATE)
        if (prefs.getBoolean("askedBattery", false)) return
        prefs.edit().putBoolean("askedBattery", true).apply()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: Exception) { }
        }
    }

    // 알림의 "종료" 버튼
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_EXIT) finishAndRemoveTask()
    }

    // 뒤로 가기: 앱을 끄지 않고 뒤로 보내기만 함 (촬영 유지)
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
    }

    // 주의: onPause/onStop 에서 webView.onPause() 를 부르지 않는다. 부르면 화면이 꺼질 때 촬영이 멈춘다.

    override fun onDestroy() {
        stopService(Intent(this, CameraKeepAliveService::class.java))
        if (::webView.isInitialized) {
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val ACTION_EXIT = "com.bakctv.app.EXIT"
        private const val REQ_PERMS = 1
    }
}
