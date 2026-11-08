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
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** 박CTV 촬영 화면 (앱 전용). 실제 촬영·전송은 StreamService 가 한다. */
class MainActivity : Activity() {

    private lateinit var preview: SurfaceViewRenderer
    private lateinit var placeholder: TextView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var talkBadge: TextView
    private lateinit var code: EditText
    private lateinit var codeCheck: TextView
    private lateinit var startStop: Button
    private lateinit var flip: Button
    private lateinit var mic: Button
    private lateinit var invertBtn: Button
    private lateinit var motionBtn: Button

    private var previewAttached = false
    private var visible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.preview)
        placeholder = findViewById(R.id.placeholder)
        status = findViewById(R.id.status)
        detail = findViewById(R.id.detail)
        talkBadge = findViewById(R.id.talkBadge)
        code = findViewById(R.id.code)
        codeCheck = findViewById(R.id.codeCheck)
        startStop = findViewById(R.id.startStop)
        flip = findViewById(R.id.flip)
        mic = findViewById(R.id.mic)
        invertBtn = findViewById(R.id.invert)
        motionBtn = findViewById(R.id.motion)

        val prefs = getSharedPreferences("bakctv", MODE_PRIVATE)
        code.setText(prefs.getString("code", ""))
        code.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = checkCode()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        startStop.setOnClickListener {
            if (StreamService.latestStatus.running) stopStreaming() else startStreaming()
        }
        flip.setOnClickListener { StreamService.current?.switchCamera() }
        mic.setOnClickListener { StreamService.current?.toggleMic() }
        invertBtn.setOnClickListener { StreamService.current?.toggleInvert() }
        motionBtn.setOnClickListener { StreamService.current?.cycleMotion() }
        findViewById<Button>(R.id.openWeb).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.app_url))))
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        StreamService.statusListener = { render(it) }
        render(StreamService.latestStatus)
    }

    override fun onStop() {
        // 화면이 꺼지면 미리보기만 끈다 (촬영·전송은 서비스에서 계속)
        visible = false
        detachPreview()
        StreamService.statusListener = null
        super.onStop()
    }

    // ---------------------------------------------------------------- 시작/종료

    private fun startStreaming() {
        val c = code.text.toString().trim()
        RoomCode.problem(c)?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show(); return }
        getSharedPreferences("bakctv", MODE_PRIVATE).edit().putString("code", c).apply()
        if (!hasMediaPermissions()) { askPermissions(); return }
        ContextCompat.startForegroundService(
            this, Intent(this, StreamService::class.java).putExtra(StreamService.EXTRA_CODE, c)
        )
        askBatteryExemptionOnce()
    }

    private fun stopStreaming() {
        stopService(Intent(this, StreamService::class.java))
    }

    // ---------------------------------------------------------------- 화면 갱신

    private fun render(s: StreamService.Status) {
        val running = s.running
        code.isEnabled = !running
        flip.isEnabled = running
        mic.isEnabled = running
        invertBtn.isEnabled = running
        invertBtn.text = if (s.inverted) "🎨 반전 켜짐" else "🎨 색상 반전"
        startStop.text = if (running) "■ 촬영 종료" else "이 기기로 촬영하기"
        mic.text = if (s.micOn) "🎤 소리 켜짐" else "🔇 소리 꺼짐"
        status.text = when {
            running -> "● " + s.text
            s.text.isNotEmpty() -> s.text
            else -> "대기 중"
        }
        motionBtn.isEnabled = running
        motionBtn.text = "👁 움직임 감지: " + MotionDetector.LABELS[s.motionLevel.coerceIn(0, 3)]
        detail.text = if (!running) "" else buildString {
            append("시청 ${s.viewers}명")
            if (s.motionActive) append("   ⚠ 움직임 감지 중!")
            else if (s.lastMotionAt > 0) append("   마지막 감지 " +
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.KOREA).format(java.util.Date(s.lastMotionAt)))
        }
        detail.setTextColor(if (s.motionActive) 0xFFFF6B6B.toInt() else 0xFFCCCCCC.toInt())
        talkBadge.visibility = if (s.talking) View.VISIBLE else View.GONE
        placeholder.visibility = if (running) View.GONE else View.VISIBLE
        if (running && visible) attachPreview() else detachPreview()
        if (previewAttached) preview.setMirror(s.frontCamera)
        if (!running) checkCode()
    }

    private fun checkCode() {
        if (StreamService.latestStatus.running) { codeCheck.text = ""; return }
        val c = code.text.toString().trim()
        val p = if (c.isEmpty()) "방 코드를 입력하세요" else RoomCode.problem(c)
        codeCheck.text = if (p == null) "✔ 사용할 수 있는 코드예요" else "⚠ $p"
        codeCheck.setTextColor(if (p == null) 0xFF30A46C.toInt() else 0xFFF2A33A.toInt())
        startStop.isEnabled = p == null
    }

    private fun attachPreview() {
        if (previewAttached) return
        val ctx = StreamService.current?.eglContext() ?: return
        preview.init(ctx, null)
        preview.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        StreamService.addPreview(preview)
        previewAttached = true
    }

    private fun detachPreview() {
        if (!previewAttached) return
        StreamService.removePreview(preview)
        preview.release()
        previewAttached = false
    }

    // ---------------------------------------------------------------- 권한

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
        if (hasMediaPermissions()) startStreaming()
        else Toast.makeText(this, "카메라와 마이크 권한이 있어야 촬영할 수 있어요.", Toast.LENGTH_LONG).show()
    }

    /** 배터리 최적화 예외 요청 (처음 한 번만) */
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

    companion object {
        private const val REQ_PERMS = 1
    }
}
