package com.bakctv.app

import android.os.SystemClock
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 움직임 감지기.
 * 1) 영상을 작은 흑백(밝기) 화면으로 줄이고
 * 2) 가우시안 블러로 노이즈를 누른 뒤
 * 3) 천천히 따라오는 배경과 비교해서 바뀐 부분을 찾는다.
 * 기준값은 지금 화면의 노이즈 수준에 맞춰 자동으로 조절된다 (어두운 곳 오탐 방지).
 */
class MotionDetector(private val onEvent: (Event) -> Unit) : VideoSink {

    /** on=true: 감지 중(시작/갱신), on=false: 끝. box = 화면 기준 0~1 좌표 [x, y, w, h] */
    data class Event(val on: Boolean, val start: Boolean, val box: FloatArray?, val strength: Float)

    /** 0=끔, 1=낮음, 2=보통, 3=높음 */
    @Volatile var sensitivity = 2
        set(v) { field = v; reset() }

    private var w = 0
    private var h = 0
    private var cur = IntArray(0)
    private var tmp = IntArray(0)
    private var bg = FloatArray(0)
    private var hasBg = false

    private var lastAnalyzed = 0L
    private var warmupUntil = 0L
    private var noise = 3f
    private var consecutive = 0
    private var active = false
    private var lastMotionAt = 0L
    private var lastSentAt = 0L

    fun reset() {
        hasBg = false
        consecutive = 0
        warmupUntil = SystemClock.elapsedRealtime() + 1500
    }

    override fun onFrame(frame: VideoFrame) {
        val level = sensitivity
        val now = SystemClock.elapsedRealtime()
        if (level == 0) {
            if (active) finish()
            return
        }
        if (now - lastAnalyzed < 66) return   // 초당 최대 15번 분석
        lastAnalyzed = now

        // 1) 작게 줄여서 밝기(Y)만 가져오기
        val buf = frame.buffer
        val sw = ANALYSIS_WIDTH
        val sh = max(2, (sw * buf.height / max(1, buf.width)) and 1.inv())
        val scaled = buf.cropAndScale(0, 0, buf.width, buf.height, sw, sh)
        val i420 = scaled.toI420()
        scaled.release()
        if (i420 == null) return
        if (sw != w || sh != h) {
            w = sw; h = sh
            cur = IntArray(w * h); tmp = IntArray(w * h); bg = FloatArray(w * h)
            hasBg = false
        }
        val y = i420.dataY
        val stride = i420.strideY
        for (r in 0 until h) {
            val base = r * stride
            for (c in 0 until w) cur[r * w + c] = y.get(base + c).toInt() and 0xFF
        }
        i420.release()

        // 2) 가우시안 블러 (5칸 [1 4 6 4 1] 가로·세로)
        blur()

        val result = analyze(now, level)
        result?.let { onEvent(rotate(it, frame.rotation)) }
    }

    /** 분석 본체 (테스트하기 쉽도록 분리). 블러된 cur 를 사용 */
    private fun analyze(now: Long, level: Int): Event? {
        val n = w * h
        if (!hasBg || now < warmupUntil) {
            for (i in 0 until n) bg[i] = cur[i].toFloat()
            hasBg = true
            return null
        }

        val k = K[level]
        val thr = max(MIN_THR[level], noise * k)
        var sum = 0f
        var count = 0
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (r in 0 until h) for (c in 0 until w) {
            val i = r * w + c
            val d = abs(cur[i] - bg[i])
            sum += d
            if (d > thr) {
                count++
                if (c < x0) x0 = c; if (c > x1) x1 = c
                if (r < y0) y0 = r; if (r > y1) y1 = r
            }
        }
        val frac = count.toFloat() / n
        val meanDiff = sum / n

        // 화면 대부분이 한꺼번에 바뀜 = 조명 변화/카메라 전환/반전 → 무시하고 배경 다시 잡기
        if (frac > 0.6f) {
            for (i in 0 until n) bg[i] = cur[i].toFloat()
            consecutive = 0
            warmupUntil = now + 500
            return if (active) finish() else null
        }

        val moving = frac > AREA[level]
        if (!moving) noise = noise * 0.95f + meanDiff * 0.05f   // 조용할 때 노이즈 수준 학습

        // 배경 갱신: 움직이는 동안은 천천히 (움직이는 물체가 배경에 녹아들지 않게)
        val alpha = if (moving) 0.02f else 0.15f
        for (i in 0 until n) bg[i] += alpha * (cur[i] - bg[i])

        if (moving) {
            consecutive++
            if (consecutive >= 2) {
                lastMotionAt = now
                val box = floatArrayOf(
                    x0.toFloat() / w, y0.toFloat() / h,
                    (x1 - x0 + 1).toFloat() / w, (y1 - y0 + 1).toFloat() / h
                )
                val strength = min(1f, frac * 10f)
                if (!active) {
                    active = true
                    lastSentAt = now
                    return Event(true, true, box, strength)
                }
                if (now - lastSentAt >= 300) {
                    lastSentAt = now
                    return Event(true, false, box, strength)
                }
            }
        } else {
            consecutive = 0
            if (active && now - lastMotionAt > 2000) return finish()
        }
        return null
    }

    private fun finish(): Event? {
        if (!active) return null
        active = false
        val e = Event(false, false, null, 0f)
        onEvent(e)
        return null
    }

    private fun blur() {
        // 가로
        for (r in 0 until h) {
            val o = r * w
            for (c in 0 until w) {
                val a = cur[o + max(0, c - 2)]; val b = cur[o + max(0, c - 1)]; val m = cur[o + c]
                val d = cur[o + min(w - 1, c + 1)]; val e = cur[o + min(w - 1, c + 2)]
                tmp[o + c] = (a + 4 * b + 6 * m + 4 * d + e) shr 4
            }
        }
        // 세로
        for (r in 0 until h) {
            val r0 = max(0, r - 2) * w; val r1 = max(0, r - 1) * w; val r3 = min(h - 1, r + 1) * w; val r4 = min(h - 1, r + 2) * w
            val o = r * w
            for (c in 0 until w) {
                cur[o + c] = (tmp[r0 + c] + 4 * tmp[r1 + c] + 6 * tmp[o + c] + 4 * tmp[r3 + c] + tmp[r4 + c]) shr 4
            }
        }
    }

    /** 카메라 원본 방향 → 실제로 보이는 화면 방향으로 좌표 변환 */
    private fun rotate(e: Event, rotation: Int): Event {
        val b = e.box ?: return e
        val x0 = b[0]; val y0 = b[1]; val x1 = b[0] + b[2]; val y1 = b[1] + b[3]
        val nb = when ((rotation % 360 + 360) % 360) {
            90 -> floatArrayOf(1 - y1, x0, y1 - y0, x1 - x0)
            180 -> floatArrayOf(1 - x1, 1 - y1, x1 - x0, y1 - y0)
            270 -> floatArrayOf(y0, 1 - x1, y1 - y0, x1 - x0)
            else -> b
        }
        return e.copy(box = nb)
    }

    companion object {
        const val ANALYSIS_WIDTH = 160
        // 민감도별 설정: [끔, 낮음, 보통, 높음]
        // (어두운 노이즈 합성 영상 시뮬레이션으로 조정: 오탐 0, 감지 지연 약 0.07초)
        private val K = floatArrayOf(0f, 8f, 6f, 5f)                // 노이즈(평균 차이)의 몇 배를 넘어야 '변화'인지
        private val MIN_THR = floatArrayOf(0f, 12f, 8f, 6f)         // 최소 밝기 차이
        private val AREA = floatArrayOf(1f, 0.01f, 0.004f, 0.0015f) // 화면의 몇 % 이상 바뀌어야 '움직임'인지
        val LABELS = arrayOf("끔", "낮음", "보통", "높음")
    }
}
