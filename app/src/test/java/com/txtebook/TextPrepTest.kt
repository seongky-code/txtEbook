package com.txtebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 줄바꿈 판별과 문단 합치기 검증.
 *
 * 윈도우판 test_txt_ebook.py 와 같은 형태의 txt 파일들을 만들어서 확인한다.
 * 난수 대신 문장을 순서대로 돌려 쓰기 때문에 실행할 때마다 결과가 같다.
 */
class TextPrepTest {

    private val sentencesKo = listOf(
        "그날 아침의 공기는 유난히 투명했다",
        "창밖으로 번져 오는 빛이 방바닥에 네모난 자리를 만들었다",
        "그 안에서 먼지들이 천천히 떠올랐다 가라앉았다",
        "나는 오래 전에 읽다 덮어 둔 책을 꺼내 첫 장을 다시 펼쳤다",
        "문장은 그때와 같았지만 읽는 사람이 달라져 있었다",
        "그는 대답하지 않았다",
    )
    private val sentencesMix = sentencesKo + "Reading is a conversation across time"

    /** 문장을 순서대로 이어 붙여 문단을 만든다 (실행할 때마다 동일). */
    private fun paragraph(count: Int = 8, pool: List<String> = sentencesMix, seed: Int = 0): String =
        (0 until count).joinToString(". ") { pool[(it * 3 + seed) % pool.size] } + "."

    // ------------------------------------------------------------ 줄 접기 흉내

    /** 단어 단위(공백)에서 접는다 - 요즘 txt 파일의 대부분. */
    private fun wordWrap(text: String, width: Int): List<String> {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        for (word in text.split(" ")) {
            when {
                line.isEmpty() -> line.append(word)
                line.length + 1 + word.length <= width -> line.append(' ').append(word)
                else -> {
                    lines.add(line.toString())
                    line.setLength(0)
                    line.append(word)
                }
            }
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        return lines
    }

    /** 글자 수로 자른다 - 단어 중간에서도 끊긴다. */
    private fun charWrap(text: String, width: Int): List<String> =
        text.chunked(width)

    /** cp949 바이트 수로 자른다 - 옛 PC통신 시절 형식 (한글 2바이트). */
    private fun byteWrap(text: String, width: Int): List<String> {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        var size = 0
        for (ch in text) {
            val step = if (ch.code < 128) 1 else 2
            if (size + step > width) {
                lines.add(line.toString())
                line.setLength(0)
                size = 0
            }
            line.append(ch)
            size += step
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        return lines
    }

    private fun document(
        wrapper: ((String, Int) -> List<String>)?,
        width: Int,
        paragraphs: Int = 14,
        indent: Boolean = false,
        trailing: Boolean = false,
    ): List<String> {
        val lines = ArrayList<String>()
        for (i in 0 until paragraphs) {
            // 실제 소설처럼 문단 길이를 들쭉날쭉하게 만든다. 모든 문단이 같은
            // 길이면 하드랩된 파일과 구분할 수 없어서 픽스처로서 의미가 없다.
            val body = paragraph(count = 2 + (i * 5) % 11, seed = i)
            var chunk = wrapper?.invoke(body, width) ?: listOf(body)
            if (indent) chunk = listOf("  " + chunk.first()) + chunk.drop(1)
            if (trailing) chunk = chunk.map { "$it " }
            lines.addAll(chunk)
            lines.add("")
        }
        return lines
    }

    // ------------------------------------------------------------ 표시 폭

    @Test
    fun `표시 폭은 한글을 두 칸으로 센다`() {
        assertEquals(4, displayWidth("한글"))
        assertEquals(2, displayWidth("ab"))
        assertEquals(3, displayWidth("가a"))
        assertEquals(0, displayWidth(""))
    }

    // ------------------------------------------------------------ 줄바꿈 판별

    @Test
    fun `단어 단위 하드랩을 알아본다`() {
        for (width in listOf(24, 32, 40, 52, 64, 80, 96)) {
            assertEquals("폭 $width", Wrap.WORD, detectWrap(document(::wordWrap, width)))
        }
    }

    @Test
    fun `글자 수로 자른 파일을 알아본다`() {
        for (width in listOf(24, 32, 40, 52, 64, 80, 96)) {
            assertEquals("폭 $width", Wrap.CHAR, detectWrap(document(::charWrap, width)))
        }
    }

    @Test
    fun `바이트 수로 자른 파일을 알아본다`() {
        assertEquals(Wrap.CHAR, detectWrap(document(::byteWrap, 80)))
        assertEquals(Wrap.CHAR, detectWrap(document(::byteWrap, 64)))
    }

    @Test
    fun `문단 들여쓰기가 있어도 단어 단위로 본다`() {
        assertEquals(Wrap.WORD, detectWrap(document(::wordWrap, 45, indent = true)))
    }

    @Test
    fun `줄 끝 공백이 있어도 단어 단위로 본다`() {
        assertEquals(Wrap.WORD, detectWrap(document(::wordWrap, 45, trailing = true)))
    }

    @Test
    fun `문단이 한 줄인 파일은 건드리지 않는다`() {
        assertEquals(Wrap.NONE, detectWrap(document(null, 0)))
        val noBlank = (0 until 40).map { paragraph(count = 1 + it % 12, seed = it) }
        assertEquals(Wrap.NONE, detectWrap(noBlank))
    }

    @Test
    fun `문단 사이 빈 줄은 버린다`() {
        // 빈 줄은 문단을 벌리라는 표시일 뿐이다. 남겨 두면 그것까지 한 줄씩 조판돼
        // 두꺼운 책에서 조판할 줄이 두 배가 되고, 화면에서는 문단 간격과 겹친다.
        val paragraphs = (0 until 20).map { paragraph(count = 2 + it % 9, seed = it) }
        val withBlanks = paragraphs.joinToString("\n\n")
        assertEquals(paragraphs.joinToString("\n"), buildParagraphs(withBlanks))

        // 빈 줄이 여러 개여도 문단 경계 하나로 본다.
        assertEquals(
            paragraphs.joinToString("\n"),
            buildParagraphs(paragraphs.joinToString("\n\n\n\n")),
        )
    }

    @Test
    fun `원문 그대로 모드는 빈 줄을 지킨다`() {
        val paragraphs = (0 until 20).map { paragraph(count = 2 + it % 9, seed = it) }
        val withBlanks = paragraphs.joinToString("\n\n")
        assertEquals(withBlanks, buildParagraphs(withBlanks, LineMode.RAW))
    }

    @Test
    fun `대사와 서술이 섞인 파일은 건드리지 않는다`() {
        val lines = ArrayList<String>()
        repeat(10) {
            lines.add("\"그래서?\" 그가 물었다.")
            lines.add("\"모르겠어.\"")
            lines.add(paragraph(seed = it))
        }
        assertEquals(Wrap.NONE, detectWrap(lines))
    }

    @Test
    fun `표본이 적으면 판단을 보류한다`() {
        assertEquals(Wrap.NONE, detectWrap(listOf("한 줄", "다음 줄")))
        assertEquals(Wrap.NONE, detectWrap(emptyList()))
    }

    // ------------------------------------------------------------ 문단 합치기

    @Test
    fun `단어 단위로 접힌 문단은 원문 그대로 복원된다`() {
        for (width in listOf(30, 40, 56, 72, 88)) {
            val body = paragraph(count = 40)
            assertEquals("폭 $width", body, buildParagraphs(wordWrap(body, width).joinToString("\n")))
        }
    }

    @Test
    fun `글자 수로 잘린 문단은 원문 그대로 이어 붙는다`() {
        for (width in listOf(30, 40, 56, 72, 88)) {
            val body = paragraph(count = 40)
            assertEquals("폭 $width", body, buildParagraphs(charWrap(body, width).joinToString("\n")))
        }
    }

    @Test
    fun `순한글 문단도 띄어쓰기를 잃지 않는다`() {
        val body = paragraph(count = 40, pool = sentencesKo)
        assertEquals(body, buildParagraphs(wordWrap(body, 40).joinToString("\n")))
    }

    @Test
    fun `줄바꿈이 삼킨 띄어쓰기를 되살린다`() {
        val body = "창밖으로 번져 오는 빛이 방바닥에 네모난 자리를 만들었다. ".repeat(6).trim()
        val merged = buildParagraphs(wordWrap(body, 38).joinToString("\n"))
        assertTrue("붙어 버린 곳이 있다: $merged", merged.contains("방바닥에 네모난"))
    }

    @Test
    fun `빈 줄은 문단 경계다`() {
        assertEquals(
            "첫째 문단 앞부분 뒷부분이다.\n둘째 문단이다.",
            buildParagraphs("첫째 문단 앞부분\n뒷부분이다.\n\n둘째 문단이다.\n", LineMode.MERGE),
        )
    }

    @Test
    fun `빈 줄 없이 이어진 두 문단을 갈라낸다`() {
        val first = paragraph(count = 30, seed = 1)
        val second = paragraph(count = 30, seed = 5)
        val wrapped = wordWrap(first, 40) + wordWrap(second, 40)
        assertEquals("$first\n$second", buildParagraphs(wrapped.joinToString("\n"), LineMode.MERGE))
    }

    @Test
    fun `하드랩 파일 안의 대사 줄은 각각 남는다`() {
        val narrative = paragraph(count = 30, seed = 2)
        val lines = wordWrap(narrative, 40) +
            listOf("\"그래서?\" 그가 물었다.", "\"모르겠어.\"", "\"왜?\"") +
            wordWrap(narrative, 40)
        assertEquals(
            listOf(narrative, "\"그래서?\" 그가 물었다.", "\"모르겠어.\"", "\"왜?\"", narrative)
                .joinToString("\n"),
            buildParagraphs(lines.joinToString("\n"), LineMode.MERGE),
        )
    }

    @Test
    fun `원문 그대로 모드는 줄바꿈을 건드리지 않는다`() {
        assertEquals("가\n나\n\n다", buildParagraphs("가\n나\n\n다\n\n\n", LineMode.RAW))
    }

    @Test
    fun `하이픈으로 끊긴 단어는 붙인다`() {
        assertEquals("conver-sation", buildParagraphs("conver-\nsation\n", LineMode.MERGE))
    }

    @Test
    fun `영문 단어 사이에는 공백을 넣는다`() {
        assertEquals(
            "Reading is a conversation across",
            buildParagraphs("Reading is a\nconversation across\n", LineMode.MERGE),
        )
    }

    @Test
    fun `빈 입력과 공백만 있는 입력`() {
        assertEquals("", buildParagraphs("", LineMode.MERGE))
        assertEquals("", buildParagraphs("\n\n   \n", LineMode.MERGE))
        assertEquals("", buildParagraphs("", LineMode.AUTO))
    }

    @Test
    fun `줄바꿈 문자와 전각 공백을 정리한다`() {
        assertEquals("가 나", buildParagraphs("가\r\n나\r\n", LineMode.MERGE))
        assertEquals("가 나", buildParagraphs("가　나", LineMode.RAW))
    }

    @Test
    fun `끝의 빈 줄을 없애 빈 페이지를 막는다`() {
        assertEquals("본문", buildParagraphs("본문\n\n\n\n", LineMode.RAW))
    }

    // ------------------------------------------------------------ 인코딩

    @Test
    fun `UTF-8 과 CP949 를 알아서 읽는다`() {
        val text = "제 1 장\n그날 아침의 공기는 유난히 투명했다."
        assertEquals(text, readTextFile(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, readTextFile(text.toByteArray(charset("MS949"))))
        assertEquals(text, readTextFile(text.toByteArray(charset("EUC-KR"))))
    }

    @Test
    fun `BOM 이 붙은 UTF-8 을 읽는다`() {
        val text = "한글 본문"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(text, readTextFile(bom + text.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `UTF-16 을 읽는다`() {
        val text = "한글 본문"
        assertEquals(text, readTextFile(text.toByteArray(Charsets.UTF_16)))
    }

    @Test
    fun `빈 파일과 영문 파일`() {
        assertEquals("", readTextFile(ByteArray(0)))
        assertEquals("plain ascii", readTextFile("plain ascii".toByteArray()))
    }
}
