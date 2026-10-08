package com.txtebook

/**
 * 원문 txt 를 읽을 수 있는 문단으로 정리한다.
 *
 * 윈도우판(txt_ebook.py)의 판별 로직을 그대로 옮긴 것이다. 이 파일에는 안드로이드
 * 의존성이 없어서 JUnit 으로 바로 검증할 수 있다.
 */

/** 줄바꿈 방식. */
enum class Wrap {
    /** 한 줄이 곧 한 문단. 합치면 글이 뭉개지므로 그대로 둔다. */
    NONE,

    /** 단어 단위(공백)에서 접힘. 줄을 이을 때 사라진 공백을 되살려야 한다. */
    WORD,

    /** 글자 수(또는 바이트 수)로 잘림. 단어 중간에서 끊기므로 그대로 붙인다. */
    CHAR,
}

/** 사용자가 고르는 줄바꿈 처리 방식. */
enum class LineMode { AUTO, RAW, MERGE }

/**
 * 한글·한자·가나를 두 칸으로 세는 표시 폭.
 *
 * 자바에는 East Asian Width 표가 없어서 필요한 구간만 직접 센다.
 */
fun displayWidth(text: String): Int {
    var width = 0
    for (ch in text) width += if (isWide(ch)) 2 else 1
    return width
}

private fun isWide(ch: Char): Boolean {
    val code = ch.code
    return when (code) {
        in 0x1100..0x115F,      // 한글 자모
        in 0x2E80..0x303E,      // CJK 부수, 한중일 기호
        in 0x3041..0x33FF,      // 가나, 한글 호환 자모, 각종 조합 문자
        in 0x3400..0x4DBF,      // CJK 확장 A
        in 0x4E00..0x9FFF,      // CJK 통합 한자
        in 0xA000..0xA4CF,      // 이문자
        in 0xAC00..0xD7A3,      // 한글 음절
        in 0xF900..0xFAFF,      // CJK 호환 한자
        in 0xFE30..0xFE6F,      // CJK 호환 형태
        in 0xFF00..0xFF60,      // 전각 영숫자·기호
        in 0xFFE0..0xFFE6,      // 전각 통화 기호
        -> true
        else -> false
    }
}

/** 줄 길이가 '천장'에 붙어 있는 비율. 하드랩 여부를 가르는 신호. */
private fun nearMaxRatio(lengths: IntArray): Double {
    val width = lengths.max()
    if (width < 12 || width > 400) return 0.0
    // 허용 범위는 폭에 비례해야 한다. 고정값을 쓰면 한글을 두 칸으로 세는
    // 표시 폭에서 범위가 사실상 절반으로 좁아진다.
    val tolerance = maxOf(3, Math.round(width * 0.08).toInt())
    return lengths.count { it >= width - tolerance }.toDouble() / lengths.size
}

/**
 * 잘린 자리에 공백이 남은 비율.
 *
 * 기계적으로 자른 파일은 공백도 그냥 잘려 나가므로, 이어지는 줄이 공백으로
 * 시작하거나 줄이 공백으로 끝나는 일이 꾸준히 생긴다. 단어 단위로 접은
 * 파일에서는 그런 자리가 아예 없다.
 *
 * 문단 첫 줄은 들여쓰기 때문에 세지 않고, 모든 줄이 공백으로 끝나는 파일은
 * 편집기가 남긴 흔적이므로 신호에서 뺀다.
 */
private fun cutSpaceRatio(lines: List<String>): Double {
    val body = lines.filter { it.isNotBlank() }
    if (body.isEmpty()) return 0.0

    var leading = 0
    var continuations = 0
    var afterBlank = true
    for (line in lines) {
        if (line.isBlank()) {
            afterBlank = true
            continue
        }
        if (!afterBlank) {
            continuations++
            if (line.first().isWhitespace()) leading++
        }
        afterBlank = false
    }

    var trailing = body.count { it.last().isWhitespace() }.toDouble() / body.size
    if (trailing > 0.9) trailing = 0.0          // 편집기가 넣은 줄 끝 공백
    val leadingRatio = if (continuations > 0) leading.toDouble() / continuations else 0.0
    return maxOf(leadingRatio, trailing)
}

/**
 * 파일의 줄바꿈 방식을 판별한다.
 *
 * 하드랩된 파일은 정해진 폭을 넘는 줄이 없고 대부분의 줄이 그 폭에 붙어 있다.
 * 자른 기준이 글자 수인 파일과 바이트 수인 파일이 섞여 있어서 두 자를 다 대 본다.
 */
fun detectWrap(lines: List<String>): Wrap {
    val body = lines.filter { it.isNotBlank() }
    if (body.size < 8) return Wrap.NONE

    val byChars = nearMaxRatio(IntArray(body.size) { body[it].length })
    val byDisplay = nearMaxRatio(IntArray(body.size) { displayWidth(body[it]) })
    if (maxOf(byChars, byDisplay) < 0.5) return Wrap.NONE

    return if (cutSpaceRatio(lines) >= 0.03) Wrap.CHAR else Wrap.WORD
}

/**
 * 원문을 문단 단위 텍스트로 정리한다.
 *
 * @param mode AUTO 는 판별에 맡기고, RAW 는 원문 그대로, MERGE 는 무조건 합친다.
 */
fun buildParagraphs(text: String, mode: LineMode = LineMode.AUTO): String {
    val normalized = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('　', ' ')      // 전각 공백
        .trimEnd()                   // 끝의 빈 줄은 빈 페이지를 만든다

    if (mode == LineMode.RAW || normalized.isEmpty()) return normalized

    val lines = normalized.split("\n")
    var style = detectWrap(lines)
    if (mode == LineMode.AUTO && style == Wrap.NONE) {
        // 한 줄이 곧 한 문단인 파일. 줄은 그대로 두되 사이의 빈 줄은 버린다.
        //
        // 빈 줄은 문단 사이를 벌리라는 표시일 뿐인데, 남겨 두면 그것까지 한 줄씩
        // 조판된다. 문단마다 빈 줄을 넣는 웹소설 파일에서는 조판할 줄이 두 배가 되어
        // 시간과 메모리를 두 배로 먹고, 화면에서는 문단 간격과 겹쳐 두 번 벌어진다.
        // 문단 사이는 '문단 간격' 설정이 그린다.
        return lines.filter { it.isNotBlank() }.joinToString("\n") { it.trim() }
    }
    if (style == Wrap.NONE) style = Wrap.WORD   // MERGE 인데 판별이 안 될 때

    val width = lines.filter { it.isNotBlank() }.maxOf { it.length }

    // 글자 수로 자른 파일은 단어 중간에서 끊겼으므로 무엇도 끼워 넣지 않는다.
    // 단어 단위로 접힌 파일은 줄바꿈이 공백을 삼켰으므로 되살린다.
    // 앞 줄의 마지막 글자만 보면 된다. 버퍼 전체를 문자열로 만들면 줄마다 통째로
    // 복사가 일어나 긴 문단에서 O(길이²) 이 된다.
    fun joiner(last: Char): String = when {
        style == Wrap.CHAR || last == '-' -> ""
        last.isWhitespace() -> ""
        else -> " "
    }

    /**
     * 이 줄에서 문단이 끝났는가.
     *
     * 단어 단위로 접힌 파일에서 줄이 넘어간 이유는 '다음 단어가 안 들어가서'다.
     * 다음 줄의 첫 단어가 이 줄에 들어갈 자리가 남아 있었다면, 줄바꿈이 아니라
     * 글쓴이가 문단을 끊은 것이다. 빈 줄 없이 문단이 바뀌는 파일과 대사가
     * 이어지는 대목을 이 규칙으로 가른다.
     */
    fun endsParagraph(line: String, following: String): Boolean {
        if (style != Wrap.WORD) return false
        val word = following.trim().substringBefore(' ')
        return line.length + 1 + word.length <= width
    }

    val paragraphs = ArrayList<String>()
    val buf = StringBuilder()
    for ((index, line) in lines.withIndex()) {
        if (line.isBlank()) {
            if (buf.isNotEmpty()) {
                paragraphs.add(buf.toString())
                buf.setLength(0)
            }
            continue
        }
        // 글자 수로 자른 파일은 공백 한 칸도 본문이므로 손대지 않는다.
        val piece = if (style == Wrap.WORD) line.trim() else line
        if (buf.isEmpty()) buf.append(piece)
        else buf.append(joiner(buf[buf.length - 1])).append(piece)

        val following = if (index + 1 < lines.size) lines[index + 1] else ""
        if (following.isNotBlank() && endsParagraph(piece, following)) {
            paragraphs.add(buf.toString())
            buf.setLength(0)
        }
    }
    if (buf.isNotEmpty()) paragraphs.add(buf.toString())
    return paragraphs.joinToString("\n")
}
