package com.txtebook

import java.nio.ByteBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * txt 파일의 인코딩을 알아내서 읽는다.
 *
 * 한국어 txt 는 UTF-8 아니면 CP949(=MS949, EUC-KR 확장)다. 순서대로 엄격하게
 * 디코딩해 보고 처음 성공하는 것을 쓴다. 마지막 ISO-8859-1 은 어떤 바이트든
 * 통과하므로 최후의 보루가 된다.
 */
private val CANDIDATES = listOf("UTF-8", "MS949", "EUC-KR", "UTF-16", "ISO-8859-1")

fun readTextFile(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""

    // BOM 이 있으면 그대로 믿는다.
    if (bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    ) {
        decodeStrict(bytes, 3, "UTF-8")?.let { return it }
    }
    if (bytes.size >= 2) {
        val head = (bytes[0].toInt() and 0xFF) to (bytes[1].toInt() and 0xFF)
        if (head == 0xFF to 0xFE || head == 0xFE to 0xFF) {
            decodeStrict(bytes, 0, "UTF-16")?.let { return it }
        }
    }

    for (name in CANDIDATES) {
        decodeStrict(bytes, 0, name)?.let { return it }
    }
    return String(bytes, Charsets.UTF_8)     // 여기까지 오지 않지만 안전망
}

/** 잘못된 바이트가 하나라도 있으면 null 을 돌려준다 (파이썬의 strict 디코딩과 같다). */
private fun decodeStrict(bytes: ByteArray, offset: Int, charsetName: String): String? = try {
    val decoder: CharsetDecoder = charset(charsetName).newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
} catch (_: Exception) {
    null        // 이 인코딩은 아니다
}
