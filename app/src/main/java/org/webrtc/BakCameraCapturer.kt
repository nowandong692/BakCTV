package org.webrtc

// 이 파일은 일부러 org.webrtc 패키지에 둔다.
// WebRTC 내부 도우미(CameraSession)의 회전·거울 처리 함수를 그대로 쓰기 위해서다.

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 박CTV 전용 카메라.
 * WebRTC 기본 카메라와 같은 방식으로 영상을 넘기면서, ISO·노출 시간·노이즈 제거를 직접 조절한다.
 *
 * 저조도 단계
 *  0 = 끔   : 자동 노출, 초당 최대 30장
 *  1 = 밝게 : 자동 노출 + 초당 15장 고정(노출 시간 최대 1/15초) + 노출 보정 최대
 *  2 = 최대 : 수동 — ISO 최대, 노출 1/15초 (카메라가 수동 조절을 지원할 때. 아니면 '밝게'와 같음)
 */
class BakCameraCapturer(
    private val errorCb: (String) -> Unit,
) : VideoCapturer {

    private lateinit var context: Context
    private lateinit var helper: SurfaceTextureHelper
    private lateinit var observer: CapturerObserver
    private lateinit var handler: Handler
    private lateinit var manager: CameraManager

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var listening = false

    private var cameraId: String? = null
    private var sensorOrientation = 0
    private var front = false
    private var reqW = 1280
    private var reqH = 720

    @Volatile var lowLight = 0
        private set
    @Volatile var denoise = false
        private set
    @Volatile var manualSupported = false
        private set

    private var wantFront = false

    override fun initialize(helper: SurfaceTextureHelper, context: Context, observer: CapturerObserver) {
        this.helper = helper
        this.context = context.applicationContext
        this.observer = observer
        this.handler = helper.handler
        this.manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        reqW = width; reqH = height
        handler.post { open() }
    }

    override fun stopCapture() {
        runAndWait { close(); if (listening) { helper.stopListening(); listening = false } }
        observer.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {}
    override fun dispose() {}
    override fun isScreencast() = false

    /** 전·후면 전환. 끝나면 onDone(전면 여부) */
    fun switchCamera(onDone: (Boolean) -> Unit) {
        handler.post {
            wantFront = !front
            close()
            open()
            onDone(front)
        }
    }

    fun setLowLight(level: Int) {
        lowLight = level
        handler.post { applyRequest() }
    }

    fun setDenoise(on: Boolean) {
        denoise = on
        handler.post { applyRequest() }
    }

    val isFront get() = front

    // ---------------------------------------------------------------- 열기/닫기

    @SuppressLint("MissingPermission")
    private fun open() {
        try {
            val ids = manager.cameraIdList
            val facing = if (wantFront) CameraMetadata.LENS_FACING_FRONT else CameraMetadata.LENS_FACING_BACK
            val id = ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
                ?: ids.firstOrNull() ?: run { errorCb("카메라를 찾을 수 없어요"); return }
            val ch = manager.getCameraCharacteristics(id)
            cameraId = id
            front = ch.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
            sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            manualSupported = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true

            val size = chooseSize(ch)
            helper.setTextureSize(size.width, size.height)
            surface?.release()
            surface = Surface(helper.surfaceTexture)

            if (!listening) {
                helper.startListening { frame -> onTextureFrame(frame) }
                listening = true
            }

            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    device = d
                    createSession()
                }
                override fun onDisconnected(d: CameraDevice) {
                    d.close(); if (device == d) device = null
                    errorCb("카메라 연결이 끊겼어요")
                }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close(); if (device == d) device = null
                    errorCb("카메라 오류 ($error)")
                }
            }, handler)
        } catch (e: Exception) {
            errorCb("카메라를 켤 수 없어요: ${e.message}")
            observer.onCapturerStarted(false)
        }
    }

    private fun createSession() {
        val d = device ?: return
        val s = surface ?: return
        try {
            @Suppress("DEPRECATION")
            d.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cs: CameraCaptureSession) {
                    session = cs
                    applyRequest()
                    observer.onCapturerStarted(true)
                }
                override fun onConfigureFailed(cs: CameraCaptureSession) {
                    errorCb("카메라 설정에 실패했어요")
                    observer.onCapturerStarted(false)
                }
            }, handler)
        } catch (e: Exception) {
            errorCb("카메라 설정 오류: ${e.message}")
        }
    }

    private fun close() {
        try { session?.close() } catch (_: Exception) { }
        session = null
        try { device?.close() } catch (_: Exception) { }
        device = null
    }

    // ---------------------------------------------------------------- 촬영 설정 (ISO·노출·노이즈)

    private fun applyRequest() {
        val d = device ?: return
        val cs = session ?: return
        val s = surface ?: return
        val id = cameraId ?: return
        try {
            val ch = manager.getCameraCharacteristics(id)
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(s)
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

            // 노이즈 제거: 켜면 카메라 자체의 고품질 노이즈 제거, 아니면 빠른 모드
            val nrModes = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: intArrayOf()
            val nr = if (denoise && nrModes.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY))
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY else CaptureRequest.NOISE_REDUCTION_MODE_FAST
            if (nrModes.contains(nr)) b.set(CaptureRequest.NOISE_REDUCTION_MODE, nr)

            val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: arrayOf()
            val level = lowLight
            if (level == 2 && manualSupported) {
                // 최대: 수동. ISO 최대, 노출 1/15초 (초당 15장 유지 → 감지가 늦어지지 않음)
                val iso = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val exp = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val frameNs = 66_666_666L
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                if (iso != null) b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.upper)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp?.let { minOf(frameNs, it.upper) } ?: frameNs)
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, frameNs)
            } else if (level >= 1) {
                // 밝게: 자동 노출을 초당 15장에 맞추고(노출 최대 1/15초) 노출 보정 최대
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                pickRange(ranges, 15)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let {
                    b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, it.upper)
                }
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                pickRange(ranges, 30)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }
            cs.setRepeatingRequest(b.build(), null, handler)
        } catch (e: Exception) {
            errorCb("카메라 설정 오류: ${e.message}")
        }
    }

    /** 최대 fps 가 target 에 가장 가깝고, 그 중 최소 fps 가 가장 낮은 범위 */
    private fun pickRange(ranges: Array<Range<Int>>, target: Int): Range<Int>? {
        if (ranges.isEmpty()) return null
        // 일부 기기는 1000 단위로 표시
        val scale = if (ranges.any { it.upper > 1000 }) 1000 else 1
        val best = ranges.minWith(compareBy<Range<Int>>({ abs(it.upper / scale - target) }, { if (target <= 15) -it.lower else it.lower }))
        return if (target <= 15) {
            // 저조도: 최소 fps 도 target 에 가깝게 (노출 시간을 길게 쓰도록)
            ranges.filter { it.upper == best.upper }.maxByOrNull { it.lower } ?: best
        } else best
    }

    private fun chooseSize(ch: CameraCharacteristics): Size {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(reqW, reqH)
        return sizes.minByOrNull { abs(it.width - reqW) + abs(it.height - reqH) } ?: Size(reqW, reqH)
    }

    // ---------------------------------------------------------------- 프레임 전달 (WebRTC Camera2Session 과 같은 방식)

    private fun onTextureFrame(frame: VideoFrame) {
        if (device == null) return
        val buffer = frame.buffer as? TextureBufferImpl ?: return
        val modified = VideoFrame(
            CameraSession.createTextureBufferWithModifiedTransformMatrix(buffer, front, -sensorOrientation),
            frameOrientation(),
            frame.timestampNs
        )
        observer.onFrameCaptured(modified)
        modified.release()
    }

    private fun frameOrientation(): Int {
        var rotation = CameraSession.getDeviceOrientation(context)
        if (!front) rotation = 360 - rotation
        return (sensorOrientation + rotation) % 360
    }

    private fun runAndWait(block: () -> Unit) {
        if (Thread.currentThread() == handler.looper.thread) { block(); return }
        val latch = CountDownLatch(1)
        handler.post { try { block() } finally { latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
    }
}
