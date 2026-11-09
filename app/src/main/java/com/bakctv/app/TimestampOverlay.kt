package com.bakctv.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import org.webrtc.VideoFrame
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

/**
 * 영상에 촬영 폰의 현재 시각을 새긴다 (CCTV 처럼).
 * 화면에 보이는 방향 기준 오른쪽 위에 표시되도록, 카메라 원본 방향(rotation)에 맞춰 좌표를 바꿔 그린다.
 * 글자 그림은 1초에 한 번만 새로 만든다.
 */
class TimestampOverlay {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    private var lastSecond = -1L
    private var lastDW = 0
    private var lastDH = 0
    private var tw = 0
    private var th = 0
    private var lum = IntArray(0)     // 글자 밝기
    private var alpha = IntArray(0)   // 글자 불투명도 0~255

    fun apply(buf: VideoFrame.I420Buffer, rotation: Int) {
        val w = buf.width
        val h = buf.height
        val rot = ((rotation % 360) + 360) % 360
        val dw = if (rot == 90 || rot == 270) h else w   // 화면에 보이는 가로
        val dh = if (rot == 90 || rot == 270) w else h   // 화면에 보이는 세로
        val sec = System.currentTimeMillis() / 1000
        if (sec != lastSecond || dw != lastDW || dh != lastDH) render(sec, dw, dh)
        if (tw == 0 || tw >= dw || th >= dh) return

        val margin = (min(dw, dh) * 0.03f).toInt()
        val x0 = dw - tw - margin
        val y0 = (dh * 0.07f).toInt()
        val yPlane = buf.dataY; val sy = buf.strideY
        val uPlane = buf.dataU; val su = buf.strideU
        val vPlane = buf.dataV; val sv = buf.strideV

        for (ty in 0 until th) {
            for (tx in 0 until tw) {
                val i = ty * tw + tx
                val a = alpha[i]
                if (a == 0) continue
                val dx = x0 + tx
                val dy = y0 + ty
                // 화면 좌표 → 카메라 원본 좌표
                val x: Int; val y: Int
                when (rot) {
                    90 -> { x = dy; y = h - 1 - dx }
                    180 -> { x = w - 1 - dx; y = h - 1 - dy }
                    270 -> { x = w - 1 - dy; y = dx }
                    else -> { x = dx; y = dy }
                }
                if (x < 0 || y < 0 || x >= w || y >= h) continue
                val idx = y * sy + x
                val old = yPlane.get(idx).toInt() and 0xFF
                yPlane.put(idx, ((lum[i] * a + old * (255 - a)) / 255).toByte())
                if (a > 128 && (x and 1) == 0 && (y and 1) == 0) {
                    uPlane.put((y / 2) * su + x / 2, 128.toByte())   // 글자는 무채색(흰/검)
                    vPlane.put((y / 2) * sv + x / 2, 128.toByte())
                }
            }
        }
    }

    private fun render(sec: Long, dw: Int, dh: Int) {
        lastSecond = sec; lastDW = dw; lastDH = dh
        val text = fmt.format(Date(sec * 1000))
        val size = min(dw, dh) * 0.045f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = size; typeface = Typeface.MONOSPACE
        }
        val stroke = Paint(fill).apply {
            color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = size * 0.16f
        }
        val pad = (size * 0.25f).toInt()
        tw = ceil(fill.measureText(text)).toInt() + pad * 2
        th = (size * 1.35f).toInt() + pad
        if (tw <= 0 || th <= 0) { tw = 0; return }
        val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val baseline = pad / 2 - fill.ascent()
        c.drawText(text, pad.toFloat(), baseline, stroke)
        c.drawText(text, pad.toFloat(), baseline, fill)
        val px = IntArray(tw * th)
        bmp.getPixels(px, 0, tw, 0, 0, tw, th)
        bmp.recycle()
        lum = IntArray(px.size); alpha = IntArray(px.size)
        for (i in px.indices) {
            val p = px[i]
            val a = (p ushr 24) and 0xFF
            if (a == 0) continue
            // getPixels 는 알파가 곱해지지 않은 색을 돌려줌
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = min(255, (r * 77 + g * 150 + b * 29) shr 8)
            alpha[i] = a
        }
    }
}
