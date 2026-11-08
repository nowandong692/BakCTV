package com.bakctv.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 박CTV 촬영 서비스.
 * 카메라·마이크를 앱이 직접 다루고(WebRTC), PeerJS 규칙으로 웹 시청자에게 영상을 보낸다.
 * 포그라운드 서비스라서 화면이 꺼져도 계속 동작한다.
 */
class StreamService : Service(), PeerJsSignaling.Listener {

    /** 화면에 표시할 상태 */
    data class Status(
        val running: Boolean,
        val text: String,
        val viewers: Int = 0,
        val talking: Boolean = false,
        val frontCamera: Boolean = false,
        val micOn: Boolean = true,
        val inverted: Boolean = false,
        val motionLevel: Int = 2,
        val motionActive: Boolean = false,
        val lastMotionAt: Long = 0L,
    )

    /** 연결 하나 (데이터 연결 또는 영상/말하기 연결) */
    private class Conn(val id: String, val peer: String, val type: String, val isTalk: Boolean = false) {
        lateinit var pc: PeerConnection
        var remoteSet = false
        val pending = mutableListOf<IceCandidate>()
        var dc: DataChannel? = null   // 시청자에게 움직임 알림을 보내는 통로
    }

    // 모든 상태 변경은 이 한 줄짜리 작업 큐에서만 처리 (동시 접근 방지)
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null

    private var signaling: PeerJsSignaling? = null
    private var heartbeatTask: ScheduledFuture<*>? = null
    private var roomCode: String? = null
    private var stopped = false

    private val conns = mutableMapOf<String, Conn>()
    private val orphanCandidates = mutableMapOf<String, MutableList<IceCandidate>>()

    private val invert = InvertProcessor()
    private val detector = MotionDetector { e -> onMotion(e) }
    private var motionActive = false
    private var lastMotionAt = 0L
    private var front = false
    private var micOn = true
    private var lastText = "준비 중…"

    /** 작업 큐에 넣기 (서비스 종료 후 들어오는 콜백은 조용히 무시) */
    private fun post(block: () -> Unit) {
        try { worker.execute { block() } } catch (_: Exception) { }
    }
    private fun later(seconds: Long, block: () -> Unit) {
        try { worker.schedule(Runnable { block() }, seconds, TimeUnit.SECONDS) } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- 서비스 수명

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        current = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNow()
        val code = intent?.getStringExtra(EXTRA_CODE)
        if (code != null && roomCode == null) {
            roomCode = code
            acquireLocks()
            post { startEngine(code) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopped = true
        current = null
        publish(Status(false, "촬영이 종료되었습니다"))
        post { teardown() }
        worker.shutdown()
        try { worker.awaitTermination(3, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
        releaseLocks()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 엔진 시작/종료

    private fun startEngine(code: String) {
        setStatus("카메라 켜는 중…")
        try {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
                )
                factoryInitialized = true
            }
            val egl = EglBase.create().also { eglBase = it }
            val adm = JavaAudioDeviceModule.builder(applicationContext)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()
            val f = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            factory = f
            adm.release()  // 팩토리가 참조를 가지고 있음

            // 카메라 (기본: 후면)
            val enumerator = Camera2Enumerator(applicationContext)
            val names = enumerator.deviceNames
            val back = names.firstOrNull { enumerator.isBackFacing(it) }
            val frontName = names.firstOrNull { enumerator.isFrontFacing(it) }
            val camName = back ?: frontName ?: names.firstOrNull()
                ?: throw IllegalStateException("카메라를 찾을 수 없어요")
            front = camName == frontName && back == null
            val cap = enumerator.createCapturer(camName, null)
            capturer = cap
            val helper = SurfaceTextureHelper.create("CaptureThread", egl.eglBaseContext)
            textureHelper = helper
            val vSrc = f.createVideoSource(false)
            videoSource = vSrc
            invert.enabled = getSharedPreferences("bakctv", MODE_PRIVATE).getBoolean("invert", false)
            vSrc.setVideoProcessor(invert)
            cap.initialize(helper, applicationContext, vSrc.capturerObserver)
            cap.startCapture(1280, 720, 30)
            val vt = f.createVideoTrack("v0", vSrc)
            videoTrack = vt
            detector.sensitivity = getSharedPreferences("bakctv", MODE_PRIVATE).getInt("motion", 2)
            vt.addSink(detector)

            // 마이크
            val ac = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            val aSrc = f.createAudioSource(ac)
            audioSource = aSrc
            audioTrack = f.createAudioTrack("a0", aSrc)
            setupSpeaker()

            main.post { previewSinks.forEach { videoTrack?.addSink(it) } }
        } catch (e: Exception) {
            setStatus("카메라를 켤 수 없어요: ${e.message}")
            return
        }

        setStatus("연결 준비 중…")
        val id = RoomCode.peerId(code)   // 일부러 느린 계산 (약 0.5초)
        if (stopped) return
        signaling = PeerJsSignaling(id, this)
        signaling?.connect()
        heartbeatTask = worker.scheduleWithFixedDelay(Runnable { signaling?.heartbeat() }, 5, 5, TimeUnit.SECONDS)
    }

    private fun teardown() {
        heartbeatTask?.cancel(false)
        signaling?.close(); signaling = null
        conns.values.toList().forEach { closeConn(it.id) }
        val vt = videoTrack
        try { vt?.removeSink(detector) } catch (_: Exception) { }
        main.post { previewSinks.forEach { s -> try { vt?.removeSink(s) } catch (_: Exception) { } } }
        try { capturer?.stopCapture() } catch (_: Exception) { }
        capturer?.dispose(); capturer = null
        videoTrack?.dispose(); videoTrack = null
        audioTrack?.dispose(); audioTrack = null
        videoSource?.dispose(); videoSource = null
        audioSource?.dispose(); audioSource = null
        textureHelper?.dispose(); textureHelper = null
        factory?.dispose(); factory = null
        eglBase?.release(); eglBase = null
        restoreSpeaker()
    }

    // ---------------------------------------------------------------- 연결 서버 이벤트

    override fun onOpen() = post {
        setStatus("촬영 중 · 시청 대기")
    }

    override fun onIdTaken() = post {
        setStatus("같은 방 코드로 촬영 중인 기기가 있어요. 다시 시도하는 중…")
        signaling?.close()
        later(5) { if (!stopped) signaling?.connect() }
    }

    override fun onDisconnected() = post {
        if (!stopped) {
            setStatus("인터넷 연결이 끊겼어요. 다시 연결하는 중…")
            later(3) { if (!stopped) signaling?.connect() }
        }
    }

    override fun onServerMessage(type: String, src: String?, payload: JSONObject?) = post {
        if (!stopped) {
            try {
                handleServerMessage(type, src, payload)
            } catch (_: Exception) { }
        }
    }

    private fun handleServerMessage(type: String, src: String?, payload: JSONObject?) {
        when (type) {
            "OFFER" -> {
                if (src == null || payload == null) return
                when (payload.optString("type")) {
                    "data" -> handleOffer(src, payload, "data", isTalk = false)
                    "media" -> handleOffer(src, payload, "media", isTalk = true) // 시청자의 "말하기"
                    else -> {}
                }
            }
            "ANSWER" -> { if (payload != null) handleAnswer(payload) }
            "CANDIDATE" -> { if (payload != null) handleCandidate(payload) }
            "LEAVE", "EXPIRE" -> { if (src != null) closePeer(src) }
            else -> {}
        }
    }

    // ---------------------------------------------------------------- WebRTC 연결 처리

    /** 시청자가 보낸 연결 요청에 응답 */
    private fun handleOffer(src: String, payload: JSONObject, type: String, isTalk: Boolean) {
        val connId = payload.getString("connectionId")
        val sdp = payload.getJSONObject("sdp").getString("sdp")
        val conn = createConn(connId, src, type, isTalk) ?: return
        conn.pc.setRemoteDescription(SdpObs(onSet = {
            post {
                conn.remoteSet = true
                flushCandidates(conn)
                conn.pc.createAnswer(SdpObs(onCreate = { answer ->
                    post {
                        conn.pc.setLocalDescription(SdpObs(), answer)
                        signaling?.send("ANSWER", src, JSONObject()
                            .put("sdp", JSONObject().put("type", "answer").put("sdp", answer.description))
                            .put("type", type)
                            .put("connectionId", connId)
                            .put("browser", "android"))
                    }
                }), MediaConstraints())
            }
        }), SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    /** 데이터 연결이 열리면 → 이쪽에서 영상을 걸어 준다 (웹 버전과 같은 순서) */
    private fun startMediaCall(viewer: String) {
        val vt = videoTrack ?: return
        val at = audioTrack ?: return
        val connId = "mc_" + (1..12).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        val conn = createConn(connId, viewer, "media", isTalk = false) ?: return
        conn.pc.addTrack(vt, listOf("bakctv"))
        conn.pc.addTrack(at, listOf("bakctv"))
        conn.pc.createOffer(SdpObs(onCreate = { offer ->
            post {
                conn.pc.setLocalDescription(SdpObs(), offer)
                signaling?.send("OFFER", viewer, JSONObject()
                    .put("sdp", JSONObject().put("type", "offer").put("sdp", offer.description))
                    .put("type", "media")
                    .put("connectionId", connId)
                    .put("browser", "android"))
            }
        }), MediaConstraints())
        updateCounts()
    }

    private fun handleAnswer(payload: JSONObject) {
        val conn = conns[payload.getString("connectionId")] ?: return
        val sdp = payload.getJSONObject("sdp").getString("sdp")
        conn.pc.setRemoteDescription(SdpObs(onSet = {
            post { conn.remoteSet = true; flushCandidates(conn) }
        }), SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun handleCandidate(payload: JSONObject) {
        val connId = payload.getString("connectionId")
        val c = payload.getJSONObject("candidate")
        val cand = IceCandidate(c.optString("sdpMid", "0"), c.optInt("sdpMLineIndex", 0), c.getString("candidate"))
        val conn = conns[connId]
        when {
            conn == null -> orphanCandidates.getOrPut(connId) { mutableListOf() }.add(cand)
            conn.remoteSet -> conn.pc.addIceCandidate(cand)
            else -> conn.pending.add(cand)
        }
    }

    private fun flushCandidates(conn: Conn) {
        orphanCandidates.remove(conn.id)?.let { conn.pending.addAll(it) }
        conn.pending.forEach { conn.pc.addIceCandidate(it) }
        conn.pending.clear()
    }

    private fun createConn(connId: String, peer: String, type: String, isTalk: Boolean): Conn? {
        val f = factory ?: return null
        val conn = Conn(connId, peer, type, isTalk)
        val config = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = f.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) = post {
                signaling?.send("CANDIDATE", peer, JSONObject()
                    .put("candidate", JSONObject()
                        .put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex))
                    .put("type", type)
                    .put("connectionId", connId))
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                when (state) {
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> post { closePeerIfConn(connId) }
                    PeerConnection.IceConnectionState.DISCONNECTED ->
                        later(15) {
                            val p = conns[connId]?.pc
                            if (p != null && p.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED) closePeerIfConn(connId)
                        }
                    else -> {}
                }
            }
            override fun onDataChannel(dc: DataChannel) {
                post { conns[connId]?.dc = dc }
                dc.registerObserver(object : DataChannel.Observer {
                    override fun onStateChange() {
                        when (dc.state()) {
                            DataChannel.State.OPEN -> post { sendTo(dc, helloJson()); startMediaCall(peer) }
                            DataChannel.State.CLOSED -> post { closePeer(peer) }
                            else -> {}
                        }
                    }
                    override fun onBufferedAmountChange(previousAmount: Long) {}
                    override fun onMessage(buffer: DataChannel.Buffer) {}
                })
            }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                if (isTalk) post { updateCounts() }   // 시청자 목소리는 스피커로 자동 재생
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onRenegotiationNeeded() {}
        }) ?: return null
        conn.pc = pc
        conns[connId] = conn
        return conn
    }

    private fun closePeerIfConn(connId: String) {
        val conn = conns[connId] ?: return
        // 영상 연결이 끊기면 그 시청자 전체 정리, 말하기 연결은 그것만 정리
        if (conn.isTalk) closeConn(connId) else closePeer(conn.peer)
    }

    private fun closePeer(peer: String) {
        conns.values.filter { it.peer == peer }.map { it.id }.forEach { closeConn(it) }
    }

    private fun closeConn(connId: String) {
        val conn = conns.remove(connId) ?: return
        conn.dc?.let { try { it.unregisterObserver(); it.dispose() } catch (_: Exception) { } }
        conn.dc = null
        try { conn.pc.dispose() } catch (_: Exception) { }
        orphanCandidates.remove(connId)
        updateCounts()
    }

    private fun updateCounts() {
        val viewers = conns.values.filter { it.type == "media" && !it.isTalk }.map { it.peer }.toSet().size
        val talking = conns.values.any { it.isTalk }
        if (signaling != null) {
            lastText = if (viewers == 0) "촬영 중 · 시청 대기" else "촬영 중"
        }
        publish(Status(true, lastText, viewers, talking, front, micOn, invert.enabled,
            detector.sensitivity, motionActive, lastMotionAt))
    }

    // ---------------------------------------------------------------- 조작 (화면에서 호출)

    fun switchCamera() = post {
        capturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) = post { front = isFront; detector.reset(); updateCounts() }
            override fun onCameraSwitchError(error: String?) {}
        })
    }

    fun toggleMic() = post {
        micOn = !micOn
        audioTrack?.setEnabled(micOn)
        updateCounts()
    }

    /** 움직임 감지 민감도: 끔 → 낮음 → 보통 → 높음 → 끔 … */
    fun cycleMotion() = post {
        val next = (detector.sensitivity + 1) % 4
        detector.sensitivity = next
        getSharedPreferences("bakctv", MODE_PRIVATE).edit().putInt("motion", next).apply()
        if (next == 0) motionActive = false
        broadcast(helloJson())
        updateCounts()
    }

    // ---------------------------------------------------------------- 움직임 알림

    private fun onMotion(e: MotionDetector.Event) = post {
        motionActive = e.on
        val msg = JSONObject().put("t", "motion").put("on", e.on).put("start", e.start)
        if (e.start) {
            lastMotionAt = System.currentTimeMillis()
            msg.put("at", lastMotionAt)
        }
        e.box?.let { b -> msg.put("box", org.json.JSONArray().apply { b.forEach { put(it.toDouble()) } }) }
        msg.put("s", e.strength.toDouble())
        broadcast(msg)
        if (e.start || !e.on) updateCounts()
    }

    private fun helloJson() = JSONObject()
        .put("t", "hello")
        .put("motion", detector.sensitivity)
        .put("label", MotionDetector.LABELS[detector.sensitivity])
        .put("active", motionActive)

    private fun broadcast(msg: JSONObject) {
        conns.values.mapNotNull { it.dc }.forEach { sendTo(it, msg) }
    }

    private fun sendTo(dc: DataChannel, msg: JSONObject) {
        try {
            if (dc.state() != DataChannel.State.OPEN) return
            // 웹(PeerJS 'json' 방식)은 UTF-8 바이트로 받아 JSON 으로 해석한다
            val bytes = msg.toString().toByteArray(Charsets.UTF_8)
            dc.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(bytes), true))
        } catch (_: Exception) { }
    }

    fun toggleInvert() = post {
        invert.enabled = !invert.enabled
        getSharedPreferences("bakctv", MODE_PRIVATE).edit().putBoolean("invert", invert.enabled).apply()
        updateCounts()
    }

    fun eglContext(): EglBase.Context? = eglBase?.eglBaseContext

    // ---------------------------------------------------------------- 스피커 (말하기 소리 크게)

    private fun setupSpeaker() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { am.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        }
    }

    private fun restoreSpeaker() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 31) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        }
        am.mode = AudioManager.MODE_NORMAL
    }

    // ---------------------------------------------------------------- 상태 알림

    private fun setStatus(text: String) {
        lastText = text
        publish(Status(true, text, 0, false, front, micOn, invert.enabled,
            detector.sensitivity, motionActive, lastMotionAt))
    }

    private fun publish(s: Status) {
        latestStatus = s
        main.post { statusListener?.invoke(s) }
    }

    // ---------------------------------------------------------------- 포그라운드·잠금

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "촬영 상태", NotificationManager.IMPORTANCE_LOW))
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle("박CTV 촬영 중")
            .setContentText("화면을 꺼도 촬영이 계속됩니다")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "종료", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 30)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BakCTV::stream").apply {
            setReferenceCounted(false); acquire()
        }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "BakCTV::wifi").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null; wifiLock = null
    }

    /** SDP 콜백을 짧게 쓰기 위한 도우미 */
    private class SdpObs(
        val onCreate: (SessionDescription) -> Unit = {},
        val onSet: () -> Unit = {},
    ) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = onCreate(sdp)
        override fun onSetSuccess() = onSet()
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        const val ACTION_STOP = "com.bakctv.app.STOP"
        const val EXTRA_CODE = "code"
        private const val CHANNEL_ID = "bakctv_stream"
        private const val NOTIFICATION_ID = 1
        private var factoryInitialized = false

        /** PeerJS 웹 라이브러리 기본값과 같은 STUN/TURN 서버 */
        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder(listOf("turn:eu-0.turn.peerjs.com:3478", "turn:us-0.turn.peerjs.com:3478"))
                .setUsername("peerjs").setPassword("peerjsp").createIceServer(),
        )

        @Volatile var current: StreamService? = null
            private set
        @Volatile var latestStatus: Status = Status(false, "")
            private set
        var statusListener: ((Status) -> Unit)? = null

        /** 미리보기 화면 (메인 스레드에서만 사용) */
        val previewSinks = mutableSetOf<VideoSink>()

        fun addPreview(sink: VideoSink) {
            previewSinks.add(sink)
            current?.videoTrack?.addSink(sink)
        }

        fun removePreview(sink: VideoSink) {
            previewSinks.remove(sink)
            try { current?.videoTrack?.removeSink(sink) } catch (_: Exception) { }
        }
    }
}
