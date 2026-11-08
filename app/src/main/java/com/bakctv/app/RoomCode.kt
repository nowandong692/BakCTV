package com.bakctv.app

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 방 코드 규칙과 연결 ID 계산. 웹 버전(web/index.html)과 반드시 똑같아야 서로 연결된다. */
object RoomCode {

    /** 규칙에 맞으면 null, 아니면 이유 */
    fun problem(code: String): String? {
        val chars = code.codePoints().toArray()
        if (chars.size < 10) return "10자 이상 필요해요 (지금 ${chars.size}자)"
        if (!code.any { it.isDigit() }) return "숫자를 하나 이상 넣어 주세요"
        if (code.all { it.isDigit() || it.isWhitespace() }) return "숫자만으로는 안 돼요. 글자도 섞어 주세요"
        if (chars.toSet().size < 6) return "같은 글자가 너무 많이 반복돼요"
        val weak = Regex("(0123|1234|2345|3456|4567|5678|6789|abcd|qwer|asdf|password|admin)", RegexOption.IGNORE_CASE)
        if (weak.containsMatchIn(code)) return "1234, abcd 같은 쉬운 패턴은 빼 주세요"
        return null
    }

    /** PBKDF2-HMAC-SHA256, 20만 회, 128비트 → "bctv-" + hex (웹과 동일) */
    fun peerId(code: String): String {
        val password = code.trim().toByteArray(Charsets.UTF_8)
        val salt = "bakctv-room-v2".toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        var u = mac.doFinal(salt + byteArrayOf(0, 0, 0, 1))
        val t = u.copyOf()
        repeat(200_000 - 1) {
            u = mac.doFinal(u)
            for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
        }
        return "bctv-" + t.copyOf(16).joinToString("") { "%02x".format(it) }
    }
}
