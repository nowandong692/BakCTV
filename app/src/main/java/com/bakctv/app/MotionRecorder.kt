package com.bakctv.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import org.webrtc.EglBase
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer
import org.webrtc.VideoSink
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 움직임 감지 자동 녹화기.
 * 켜져 있는 동안 영상(H.264, 초당 15장)과 소리(AAC)를 계속 압축해서 최근 8초를 메모리에 보관하고,
 * 움직임이 감지되면 "감지 5초 전"부터 "마지막 움직임 후 10초"까지 MP4 로 저장한다.
 * 모든 작업은 전용 스레드 하나에서 처리한다.
 */
class MotionRecorder(
    private val ctx: Context,
    private val sharedEgl: EglBase.Context,
    private val onChanged: (recording: Boolean, savedName: String?) -> Unit,
) : VideoSink {

    private class Sample(val video: Boolean, val data: ByteArray, val ptsUs: Long, val flags: Int)

    private val thread = HandlerThread("BakCTV-rec").apply { start() }
    private val h = Handler(thread.looper)

    @Volatile private var active = false
    @Volatile private var drawPending = false
    private var lastFrameNs = 0L

    // 영상 압축
    private var vEnc: MediaCodec? = null
    private var vEgl: EglBase? = null
    private var vSurface: Surface? = null
    private var vFormat: MediaFormat? = null
    private var vw = 0
    private var vh = 0
    private var glDrawer: GlRectDrawer? = null
    private var frameDrawer: VideoFrameDrawer? = null

    // 소리 압축
    private var aEnc: MediaCodec? = null
    private var aFormat: MediaFormat? = null
    private var aRate = 0
    private var aChannels = 0
    private var lastAudioPts = 0L

    // 최근 8초 보관 + 파일 쓰기
    private val ring = ArrayDeque<Sample>()
    private var target: ClipStorage.Target? = null
    private var vTrack = -1
    private var aTrack = -1
    private var baseUs = 0L
    private var lastWrittenV = -1L
    private var lastWrittenA = -1L
    private var fileStartMs = 0L
    private var motionOn = false
    private var lastMotionMs = 0L

    private val info = MediaCodec.BufferInfo()
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            drainVideo(); drainAudio(); checkFile()
            h.postDelayed(this, 100)
        }
    }

    val isRecording get() = target != null

    // ---------------------------------------------------------------- 외부에서 호출

    fun setActive(on: Boolean) {
        h.post { if (on) start() else stop() }
    }

    fun onMotion(on: Boolean) {
        h.post {
            motionOn = on
            lastMotionMs = SystemClock.elapsedRealtime()
            if (on && target == null && active) startFile()
        }
    }

    /** 서비스 종료 시: 녹화 중인 파일을 마무리하고 스레드 종료 (최대 3초 대기) */
    fun release() {
        h.post { stop() }
        thread.quitSafely()
        try { thread.join(3000) } catch (_: InterruptedException) { }
    }

    /** 카메라 영상 (캡처 스레드에서 호출됨) */
    override fun onFrame(frame: VideoFrame) {
        if (!active || drawPending) return
        val now = System.nanoTime()
        if (now - lastFrameNs < 62_000_000L) return   // 초당 약 15장
        lastFrameNs = now
        frame.retain()
        drawPending = true
        val posted = h.post {
            try { encodeFrame(frame) } catch (_: Exception) { } finally { frame.release(); drawPending = false }
        }
        if (!posted) { frame.release(); drawPending = false }
    }

    /** 마이크 소리 (오디오 스레드에서 호출됨) */
    fun onAudio(s: JavaAudioDeviceModule.AudioSamples) {
        if (!active) return
        val data = s.data.copyOf()
        val rate = s.sampleRate
        val ch = s.channelCount
        val t = System.nanoTime()
        h.post { try { encodeAudio(data, rate, ch, t) } catch (_: Exception) { } }
    }

    // ---------------------------------------------------------------- 시작/정지

    private fun start() {
        if (active) return
        active = true
        h.postDelayed(tick, 100)
    }

    private fun stop() {
        if (target != null) finishFile()
        active = false
        h.removeCallbacks(tick)
        try { vEgl?.makeCurrent() } catch (_: Exception) { }
        glDrawer?.release(); glDrawer = null
        frameDrawer?.release(); frameDrawer = null
        try { vEnc?.stop() } catch (_: Exception) { }
        vEnc?.release(); vEnc = null
        vEgl?.release(); vEgl = null
        vSurface?.release(); vSurface = null
        try { aEnc?.stop() } catch (_: Exception) { }
        aEnc?.release(); aEnc = null
        vFormat = null; aFormat = null
        ring.clear()
        motionOn = false
    }

    // ---------------------------------------------------------------- 영상

    private fun encodeFrame(frame: VideoFrame) {
        if (!active) return
        if (vEnc == null) {
            vw = frame.rotatedWidth / 2 * 2
            vh = frame.rotatedHeight / 2 * 2
            createVideoEncoder()
        }
        val egl = vEgl ?: return
        egl.makeCurrent()
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        frameDrawer!!.drawFrame(frame, glDrawer!!, null, 0, 0, vw, vh)
        egl.swapBuffers(System.nanoTime())
        drainVideo()
    }

    private fun createVideoEncoder() {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)   // 1초마다 기준 화면 → 5초 전부터 자르기 쉬움
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        vSurface = surface
        enc.start()
        vEnc = enc
        vEgl = EglBase.create(sharedEgl, EglBase.CONFIG_RECORDABLE).apply {
            createSurface(surface)
            makeCurrent()
        }
        glDrawer = GlRectDrawer()
        frameDrawer = VideoFrameDrawer()
    }

    private fun drainVideo() {
        val enc = vEnc ?: return
        while (true) {
            val idx = try { enc.dequeueOutputBuffer(info, 0) } catch (_: Exception) { return }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> vFormat = enc.outputFormat
                idx < 0 -> return
                else -> {
                    val buf = enc.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        onSample(Sample(true, copy(buf, info), info.presentationTimeUs, info.flags))
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 소리

    private fun encodeAudio(pcm: ByteArray, rate: Int, ch: Int, tNs: Long) {
        if (!active) return
        if (aEnc == null || rate != aRate || ch != aChannels) createAudioEncoder(rate, ch)
        val enc = aEnc ?: return
        val bytesPerSec = rate * ch * 2L
        // 이 묶음의 시작 시각 = 받은 시각 - 길이
        var pts = tNs / 1000 - pcm.size * 1_000_000L / bytesPerSec
        if (pts <= lastAudioPts) pts = lastAudioPts + 1
        var off = 0
        while (off < pcm.size) {
            val idx = enc.dequeueInputBuffer(5_000)
            if (idx < 0) break
            val inBuf = enc.getInputBuffer(idx) ?: break
            inBuf.clear()
            val n = minOf(inBuf.remaining(), pcm.size - off)
            inBuf.put(pcm, off, n)
            val chunkPts = pts + off * 1_000_000L / bytesPerSec
            enc.queueInputBuffer(idx, 0, n, chunkPts, 0)
            lastAudioPts = chunkPts
            off += n
        }
        drainAudio()
    }

    private fun createAudioEncoder(rate: Int, ch: Int) {
        try { aEnc?.stop() } catch (_: Exception) { }
        aEnc?.release(); aEnc = null; aFormat = null
        aRate = rate; aChannels = ch
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        aEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun drainAudio() {
        val enc = aEnc ?: return
        while (true) {
            val idx = try { enc.dequeueOutputBuffer(info, 0) } catch (_: Exception) { return }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> aFormat = enc.outputFormat
                idx < 0 -> return
                else -> {
                    val buf = enc.getOutputBuffer(idx)
                    if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        onSample(Sample(false, copy(buf, info), info.presentationTimeUs, info.flags))
                    }
                    enc.releaseOutputBuffer(idx, false)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 보관 + 파일

    private fun onSample(s: Sample) {
        ring.addLast(s)
        val limit = System.nanoTime() / 1000 - 8_000_000L
        while (ring.isNotEmpty() && ring.first().ptsUs < limit) ring.removeFirst()
        if (target != null) write(s)
    }

    private fun startFile() {
        val vf = vFormat ?: return   // 아직 영상이 준비 안 됨 → 다음 tick 에서 재시도
        val nowUs = System.nanoTime() / 1000
        val keys = ring.filter { it.video && (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 }
        val startKey = keys.lastOrNull { it.ptsUs <= nowUs - 5_000_000L } ?: keys.firstOrNull() ?: return

        val name = "박CTV_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date()) + ".mp4"
        val t = ClipStorage.open(ctx, name) ?: return
        try {
            vTrack = t.muxer.addTrack(vf)
            aTrack = aFormat?.let { t.muxer.addTrack(it) } ?: -1
            t.muxer.start()
        } catch (_: Exception) {
            try { t.muxer.release() } catch (_: Exception) { }
            t.finish(false)
            return
        }
        target = t
        baseUs = startKey.ptsUs
        lastWrittenV = -1; lastWrittenA = -1
        fileStartMs = SystemClock.elapsedRealtime()
        ring.forEach { if (it.ptsUs >= baseUs) write(it) }   // 감지 5초 전부터
        onChanged(true, null)
    }

    private fun write(s: Sample) {
        val t = target ?: return
        val track = if (s.video) vTrack else aTrack
        if (track < 0) return
        val pts = s.ptsUs - baseUs
        if (pts < 0) return
        if (s.video) { if (pts <= lastWrittenV) return; lastWrittenV = pts }
        else { if (pts <= lastWrittenA) return; lastWrittenA = pts }
        val bi = MediaCodec.BufferInfo().apply { set(0, s.data.size, pts, s.flags) }
        try { t.muxer.writeSampleData(track, ByteBuffer.wrap(s.data), bi) } catch (_: Exception) { }
    }

    private fun checkFile() {
        val now = SystemClock.elapsedRealtime()
        if (target != null) {
            val quiet = !motionOn && now - lastMotionMs > 10_000      // 마지막 움직임 후 10초
            val tooLong = now - fileStartMs > 10 * 60_000             // 한 파일 최대 10분
            if (quiet || tooLong) {
                finishFile()
                if (tooLong && motionOn) startFile()
            }
        } else if (motionOn) {
            startFile()   // 감지 순간 영상이 준비 안 됐던 경우
        }
    }

    private fun finishFile() {
        val t = target ?: return
        target = null
        var ok = lastWrittenV >= 0
        try { t.muxer.stop() } catch (_: Exception) { ok = false }
        try { t.muxer.release() } catch (_: Exception) { }
        try { t.finish(ok) } catch (_: Exception) { }
        ClipStorage.enforceCap(ctx)
        onChanged(false, if (ok) t.name else null)
    }

    private fun copy(buf: ByteBuffer, bi: MediaCodec.BufferInfo): ByteArray {
        buf.position(bi.offset)
        buf.limit(bi.offset + bi.size)
        return ByteArray(bi.size).also { buf.get(it) }
    }
}
