package com.bakctv.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * 앱 안에서 보는 화면 (웹 버전 박CTV 를 웹뷰로 띄움).
 * 앱에 저장된 방 코드를 자동으로 넣고 바로 "영상 보기"로 연결한다.
 */
class ViewerActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var pendingPermission: PermissionRequest? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var autoStarted = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // 보는 동안 화면 켜 두기

        root = FrameLayout(this)
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false   // 소리 자동 재생 허용
            userAgentString = "$userAgentString BakCTVApp"
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            override fun onPageFinished(view: WebView, url: String) {
                if (!autoStarted) { autoStarted = true; autoStartViewer() }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            // "말하기" 마이크 권한
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.resources)
                    } else {
                        pendingPermission = request
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
                    }
                }
            }
            // ⛶ 전체화면
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                fullscreenView?.let { root.removeView(it) }
                fullscreenView = view
                fullscreenCallback = callback
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
            override fun onHideCustomView() {
                fullscreenView?.let { root.removeView(it) }
                fullscreenView = null
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
        web.loadUrl(getString(R.string.app_url))
    }

    /** 앱에 저장된 방 코드를 웹 화면에 넣고 바로 "다른 기기 영상 보기" */
    private fun autoStartViewer() {
        val code = getSharedPreferences("bakctv", MODE_PRIVATE).getString("code", "") ?: ""
        if (code.isEmpty() || RoomCode.problem(code) != null) return
        val js = """
            (function(){
              var code = ${JSONObject.quote(code)};
              try { localStorage.setItem('fcam-code', code); } catch(e) {}
              var el = document.getElementById('code');
              if (!el) return;
              el.value = code;
              el.dispatchEvent(new Event('input'));
              var b = document.getElementById('startView');
              if (b && !b.disabled) b.click();
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val req = pendingPermission ?: return
        pendingPermission = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) req.grant(req.resources) else req.deny()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullscreenView != null) { web.webChromeClient?.onHideCustomView(); return }
        finish()
    }

    override fun onDestroy() {
        web.loadUrl("about:blank")
        web.destroy()
        super.onDestroy()
    }

    companion object {
        private const val REQ_MIC = 2
    }
}
