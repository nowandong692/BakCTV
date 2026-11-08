package com.bakctv.app

import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 보내기 전 영상 처리 (한 번에 처리해서 S8 부담 최소화)
 *  - 밝기 보정(감마): 어두운 부분을 끌어올림. 0=끔, 1=약하게, 2=강하게
 *  - 노이즈 줄이기: 가우시안 블러([1 2 1] 가로·세로) — 밝기·색 모두
 *  - 색상 반전: YUV 각각 255 에서 뺌 = RGB 반전
 */
class InvertProcessor : VideoProcessor {

    @Volatile var enabled = false            // 색상 반전
    @Volatile var boost = 0
        set(v) { field = v; lutDirty = true }
    @Volatile var denoise = false

    private var sink: VideoSink? = null
    private var lutDirty = true
    private var lutY = ByteArray(256)
    private var lutBuiltFor = -1
    private var planeA = ByteArray(0)
    private var planeB = ByteArray(0)

    override fun setSink(sink: VideoSink?) { this.sink = sink }
    override fun onCapturerStarted(success: Boolean) {}
    override fun onCapturerStopped() {}

    override fun onFrameCaptured(frame: VideoFrame) {
        val out = sink ?: return
        val inv = enabled
        val b = boost
        val dn = denoise
        if (!inv && b == 0 && !dn) { out.onFrame(frame); return }

        buildLut(inv, b)
        val src = frame.buffer.toI420() ?: run { out.onFrame(frame); return }
        val w = src.width
        val h = src.height
        val cw = (w + 1) / 2
        val chh = (h + 1) / 2
        val dst = JavaI420Buffer.allocate(w, h)
        processPlane(src.dataY, src.strideY, dst.dataY, dst.strideY, w, h, lutY, dn)
        val uvLut = if (inv) INVERT_LUT else IDENTITY_LUT
        processPlane(src.dataU, src.strideU, dst.dataU, dst.strideU, cw, chh, uvLut, dn)
        processPlane(src.dataV, src.strideV, dst.dataV, dst.strideV, cw, chh, uvLut, dn)
        src.release()

        val processed = VideoFrame(dst, frame.rotation, frame.timestampNs)
        out.onFrame(processed)
        processed.release()
    }

    private fun buildLut(inv: Boolean, b: Int) {
        val key = (if (inv) 10 else 0) + b
        if (!lutDirty && key == lutBuiltFor) return
        val gamma = when (b) { 1 -> 1.6; 2 -> 2.2; else -> 1.0 }
        val gain = if (b == 2) 1.15 else 1.0
        for (i in 0..255) {
            var v = 255.0 * (i / 255.0).pow(1.0 / gamma) * gain
            v = v.coerceIn(0.0, 255.0)
            var o = v.roundToInt()
            if (inv) o = 255 - o
            lutY[i] = o.toByte()
        }
        lutBuiltFor = key
        lutDirty = false
    }

    /** 평면 하나: (블러) → LUT 적용 → 출력 */
    private fun processPlane(
        src: ByteBuffer, srcStride: Int, dst: ByteBuffer, dstStride: Int,
        width: Int, height: Int, lut: ByteArray, blur: Boolean,
    ) {
        val n = width * height
        if (planeA.size < n) { planeA = ByteArray(n); planeB = ByteArray(n) }
        val a = planeA
        for (y in 0 until height) {
            src.position(y * srcStride)
            src.get(a, y * width, width)
        }
        if (blur) {
            val t = planeB
            // 가로 [1 2 1]
            for (y in 0 until height) {
                val o = y * width
                for (x in 0 until width) {
                    val l = a[o + if (x > 0) x - 1 else 0].toInt() and 0xFF
                    val m = a[o + x].toInt() and 0xFF
                    val r = a[o + if (x < width - 1) x + 1 else x].toInt() and 0xFF
                    t[o + x] = ((l + 2 * m + r + 2) shr 2).toByte()
                }
            }
            // 세로 [1 2 1]
            for (y in 0 until height) {
                val up = (if (y > 0) y - 1 else 0) * width
                val o = y * width
                val dn = (if (y < height - 1) y + 1 else y) * width
                for (x in 0 until width) {
                    val u = t[up + x].toInt() and 0xFF
                    val m = t[o + x].toInt() and 0xFF
                    val d = t[dn + x].toInt() and 0xFF
                    a[o + x] = ((u + 2 * m + d + 2) shr 2).toByte()
                }
            }
        }
        if (lut !== IDENTITY_LUT) {
            for (i in 0 until n) a[i] = lut[a[i].toInt() and 0xFF]
        }
        for (y in 0 until height) {
            dst.position(y * dstStride)
            dst.put(a, y * width, width)
        }
    }

    companion object {
        private val IDENTITY_LUT = ByteArray(256) { it.toByte() }
        private val INVERT_LUT = ByteArray(256) { (255 - it).toByte() }
    }
}
