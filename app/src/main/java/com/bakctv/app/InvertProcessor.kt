package com.bakctv.app

import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink
import java.nio.ByteBuffer

/**
 * 카메라 영상을 보내기 전에 색상을 반전하는 처리기.
 * YUV 영상에서 Y·U·V 값을 각각 255에서 빼면 RGB 색상 반전과 같은 결과가 된다.
 */
class InvertProcessor : VideoProcessor {

    @Volatile var enabled = false
    private var sink: VideoSink? = null
    private var row = ByteArray(0)

    override fun setSink(sink: VideoSink?) { this.sink = sink }
    override fun onCapturerStarted(success: Boolean) {}
    override fun onCapturerStopped() {}

    override fun onFrameCaptured(frame: VideoFrame) {
        val out = sink ?: return
        if (!enabled) { out.onFrame(frame); return }

        val src = frame.buffer.toI420() ?: run { out.onFrame(frame); return }
        val w = src.width
        val h = src.height
        val dst = JavaI420Buffer.allocate(w, h)
        invertPlane(src.dataY, src.strideY, dst.dataY, dst.strideY, w, h)
        invertPlane(src.dataU, src.strideU, dst.dataU, dst.strideU, (w + 1) / 2, (h + 1) / 2)
        invertPlane(src.dataV, src.strideV, dst.dataV, dst.strideV, (w + 1) / 2, (h + 1) / 2)
        src.release()

        val inverted = VideoFrame(dst, frame.rotation, frame.timestampNs)
        out.onFrame(inverted)
        inverted.release()
    }

    private fun invertPlane(src: ByteBuffer, srcStride: Int, dst: ByteBuffer, dstStride: Int, width: Int, height: Int) {
        if (row.size < width) row = ByteArray(width)
        val r = row
        for (y in 0 until height) {
            src.position(y * srcStride)
            src.get(r, 0, width)
            for (x in 0 until width) r[x] = (255 - (r[x].toInt() and 0xFF)).toByte()
            dst.position(y * dstStride)
            dst.put(r, 0, width)
        }
    }
}
