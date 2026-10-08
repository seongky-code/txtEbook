package com.txtebook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/**
 * 조판 엔진 검증. 실제 기기(에뮬레이터)에서 돈다.
 *
 * 윈도우판에서 겪었던 결함과, 덩어리 조판으로 바꾸면서 새로 생길 수 있는 결함
 * (덩어리 경계에서 본문이 빠지거나 겹치는 것)을 본다.
 */
@RunWith(AndroidJUnit4::class)
class PaginatorTest {

    private val sentence = "그날 아침의 공기는 유난히 투명했다. 창밖으로 번져 오는 빛이 방바닥에 " +
        "네모난 자리를 만들고, 그 안에서 먼지들이 천천히 떠올랐다 가라앉았다. 나는 오래 전에 " +
        "읽다 덮어 둔 책을 꺼내 첫 장을 다시 펼쳤다. Reading is a conversation across time."

    private fun book(paragraphs: Int = 120): String =
        (0 until paragraphs).joinToString("\n") { "제 ${it + 1} 장\n$sentence" }

    private fun paint(sizePx: Float = 36f, letterSpacingEm: Float = 0f, stretch: Float = 1f) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("serif", Typeface.NORMAL)
            textSize = sizePx
            letterSpacing = letterSpacingEm
            textScaleX = stretch
        }

    /** 끝까지 조판한 엔진을 만든다. */
    private fun paginate(
        text: CharSequence = book(),
        width: Int = 900,
        height: Int = 1200,
        paint: TextPaint = paint(),
        justify: Boolean = true,
    ) = Paginator(text, paint, width, height, justify).apply {
        @Suppress("ControlFlowWithEmptyBody")
        while (paginateNext());
    }

    private fun lineCountOf(text: CharSequence, paint: TextPaint, width: Int = 900): Int =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setLineSpacing(0f, 1f).setIncludePad(false).build().lineCount

    // ------------------------------------------------------------ 기본 불변식

    @Test
    fun 모든_페이지에서_앵커가_왕복한다() {
        val engine = paginate()
        assertTrue("페이지가 만들어지지 않았다", engine.pageCount > 5)
        var broken = 0
        for (page in 0 until engine.pageCount) {
            if (engine.pageOf(engine.charAt(page)) != page) broken++
        }
        assertEquals("앵커 왕복이 깨진 페이지 수", 0, broken)
        Log.i("txtEbookTest", "앵커 왕복 통과: ${engine.pageCount} 쪽, ${engine.chunkCount} 덩어리")
    }

    @Test
    fun 어떤_페이지도_지면_높이를_넘지_않는다() {
        val engine = paginate()
        for (page in 0 until engine.pageCount) {
            val used = engine.pageContentHeight(page)
            assertTrue("페이지 $page 높이 $used > ${engine.pageHeight}", used <= engine.pageHeight)
            assertTrue("페이지 $page 가 비었다", used > 0)
        }
    }

    @Test
    fun 페이지가_본문을_빠짐없이_덮는다() {
        // 덩어리로 나눠 조판하면서 경계에서 글자가 새거나 겹치기 쉽다.
        val text = book(200)
        val engine = paginate(text)
        assertEquals("첫 쪽이 본문 처음에서 시작하지 않는다", 0, engine.charAt(0))
        var previous = -1
        for (page in 0 until engine.pageCount) {
            val start = engine.charAt(page)
            assertTrue("페이지 $page 시작이 뒤로 갔다 ($previous -> $start)", start > previous)
            previous = start
        }
        assertEquals(
            "마지막 글자가 마지막 쪽에 없다",
            engine.pageCount - 1, engine.pageOf(text.length - 1),
        )
        // 아무 글자나 골라도 그 글자를 담은 쪽이 나와야 한다.
        for (offset in 0 until text.length step 997) {
            val page = engine.pageOf(offset)
            val next = if (page + 1 < engine.pageCount) engine.charAt(page + 1) else text.length
            assertTrue(
                "글자 $offset 가 쪽 $page (${engine.charAt(page)}..$next) 밖이다",
                offset >= engine.charAt(page) && offset < next,
            )
        }
    }

    @Test
    fun 덩어리가_여러_개로_나뉜다() {
        // 20만 자 기준으로 잘리므로 60만 자면 세 덩어리쯤 나와야 한다.
        val big = StringBuilder()
        while (big.length < 600_000) big.append(book(40)).append('\n')
        val engine = paginate(big.toString())
        Log.i("txtEbookTest", "${big.length / 1000}k 자 -> ${engine.chunkCount} 덩어리")
        assertTrue("덩어리로 나뉘지 않았다 (${engine.chunkCount})", engine.chunkCount >= 2)
    }

    // ------------------------------------------------------------ 읽던 자리

    @Test
    fun 조판을_바꿔도_읽던_글자가_화면에_남는다() {
        val text = book()
        val before = paginate(text)
        val anchor = before.charAt(before.pageCount / 2)

        val variants = listOf(
            paginate(text, paint = paint(sizePx = 48f)),
            paginate(text, paint = paint(letterSpacingEm = 0.08f)),
            paginate(text, paint = paint(stretch = 1.2f)),
            paginate(text, width = 640, height = 1500),
            paginate(text, width = 1200, height = 800),
            paginate(text, justify = false),
        )
        for ((index, engine) in variants.withIndex()) {
            val page = engine.pageOf(anchor)
            val start = engine.charAt(page)
            val end = if (page + 1 < engine.pageCount) engine.charAt(page + 1) else text.length
            assertTrue(
                "변형 $index: 앵커 $anchor 가 쪽 $page ($start..$end) 밖이다",
                anchor in start..end,
            )
        }
    }

    @Test
    fun 조판_도중에도_앞부분을_읽을_수_있다() {
        val big = StringBuilder()
        while (big.length < 600_000) big.append(book(40)).append('\n')
        val engine = Paginator(big.toString(), paint(), 900, 1200, true)

        engine.paginateNext()          // 첫 덩어리만 조판
        assertTrue("첫 덩어리 뒤에 쪽이 없다", engine.pageCount > 0)
        assertTrue("아직 다 끝나지 않아야 한다", !engine.isComplete)
        assertTrue("책 앞부분은 덮여 있어야 한다", engine.covers(0))
        assertTrue("아직 안 짠 뒷부분은 덮이지 않아야 한다", !engine.covers(big.length - 1))
        assertTrue("첫 쪽을 그릴 수 없다", engine.pageContentHeight(0) > 0)
    }

    @Test
    fun 마지막_페이지에_빈_페이지가_붙지_않는다() {
        val engine = paginate(book(20))
        val padded = paginate(book(20) + "\n\n\n\n")
        assertEquals(engine.pageCount, padded.pageCount)
    }

    @Test
    fun 뒤쪽_쪽수를_세는_동안에도_읽던_지면이_남아_있다() {
        // 실제로 있었던 결함: 책을 연 직후 몇 장만 넘겨도 지면이 비었다. 뒤에서
        // 쪽수를 세느라 조판한 덩어리가 읽고 있던 덩어리를 그리기 캐시에서 밀어냈다.
        val big = StringBuilder()
        while (big.length < 900_000) big.append(book(40)).append('\n')
        val engine = Paginator(big.toString(), paint(), 900, 1200, true)

        engine.paginateNext()
        assertTrue("첫 쪽을 그릴 수 없다", engine.pageContentHeight(0) > 0)

        @Suppress("ControlFlowWithEmptyBody")
        while (engine.paginateNext());

        assertTrue("덩어리가 적어 시험이 되지 않는다", engine.chunkCount >= 3)
        assertTrue("쪽수를 다 센 뒤에 읽던 지면이 비었다", engine.pageContentHeight(0) > 0)
    }

    @Test
    fun 지면_하나만_짜도_덩어리를_통째로_짠_것과_똑같이_그려진다() {
        // 그릴 때는 그 쪽에 들어갈 글자만 조판한다 (덩어리 전체는 낡은 기기에서 5.6초).
        // 줄 나눔·양쪽 맞춤·들여쓰기가 하나라도 어긋나면 쪽 경계에서 글자가 겹치거나
        // 빠진다. 같은 본문을 통째로 조판한 것과 픽셀까지 같은지 본다.
        val text = SpannableString(book(120)).apply {
            setSpan(FixedLineHeight(60), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(LeadingMarginSpan.Standard(40, 0), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val width = 900
        val height = 1200
        val engine = paginate(text, width, height)
        val whole = StaticLayout.Builder
            .obtain(text, 0, text.length, paint(), width)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            .build()

        assertTrue("쪽이 너무 적어 시험이 되지 않는다", engine.pageCount >= 8)
        for (page in 0 until engine.pageCount) {
            val used = engine.pageContentHeight(page)
            val mine = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(mine).also { engine.drawPage(it, page) }

            // 통째로 짠 레이아웃에서 같은 자리를 떼어 그린다.
            val theirs = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(theirs).also { canvas ->
                val top = whole.getLineTop(whole.getLineForOffset(engine.charAt(page)))
                canvas.save()
                canvas.clipRect(0, 0, width, minOf(height, used))
                canvas.translate(0f, -top.toFloat())
                whole.draw(canvas)
                canvas.restore()
            }
            assertTrue("${page}쪽이 다르게 그려진다", mine.sameAs(theirs))
            mine.recycle()
            theirs.recycle()
        }
    }

    // ------------------------------------------------------------ 쪽 표 다시 쓰기

    @Test
    fun 저장해_둔_쪽_표로_조판을_건너뛴다() {
        val text = book(200)
        val done = paginate(text)

        val reopened = Paginator(text, paint(), 900, 1200, true)
        assertTrue("쪽 표를 받아들이지 않았다", reopened.restore(done.pages))
        assertTrue("다 센 것으로 쳐야 한다", reopened.isComplete)
        assertEquals("쪽수가 달라졌다", done.pageCount, reopened.pageCount)
        for (page in 0 until done.pageCount) {
            assertEquals("${page}쪽 시작 글자가 다르다", done.charAt(page), reopened.charAt(page))
        }

        // 조판을 건너뛰었어도 그릴 수 있어야 한다.
        val middle = reopened.pageCount / 2
        assertTrue("가운데 쪽을 그릴 수 없다", reopened.pageContentHeight(middle) > 0)
        assertEquals(
            "앵커가 왕복하지 않는다",
            middle, reopened.pageOf(reopened.charAt(middle)),
        )
    }

    @Test
    fun 말이_안_되는_쪽_표는_받지_않는다() {
        val text = book(40)
        val engine = { Paginator(text, paint(), 900, 1200, true) }

        assertTrue("빈 표를 받았다", !engine().restore(IntArray(0)))
        assertTrue("0 에서 시작하지 않는 표를 받았다", !engine().restore(intArrayOf(5, 90, 300)))
        assertTrue("거꾸로 가는 표를 받았다", !engine().restore(intArrayOf(0, 300, 90)))
        assertTrue("같은 자리가 겹친 표를 받았다", !engine().restore(intArrayOf(0, 90, 90)))
        assertTrue(
            "본문 밖을 가리키는 표를 받았다",
            !engine().restore(intArrayOf(0, 90, text.length + 10)),
        )
        // 더 긴 책에서 세어 둔 표는 이 책에 맞지 않는다.
        val longer = paginate(book(200))
        assertTrue("다른 책의 표를 받았다", !engine().restore(longer.pages))
    }

    // ------------------------------------------------------------ 조판 설정

    @Test
    fun 줄_높이_span_이_실제로_먹는다() {
        val text = book(20)
        val plain = paginate(text)
        val tall = SpannableString(text).apply {
            setSpan(FixedLineHeight(120), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val spanned = paginate(tall)
        assertTrue(
            "줄 높이를 키웠는데 쪽수가 그대로다 (${plain.pageCount} vs ${spanned.pageCount})",
            spanned.pageCount > plain.pageCount,
        )
    }

    @Test
    fun 자간이_글자_폭에_반영된다() {
        val sample = "그날 아침의 공기는 유난히 투명했다"
        val plain = paint().measureText(sample)
        val spaced = paint(letterSpacingEm = 0.1f).measureText(sample)
        Log.i("txtEbookTest", "자간 0.1em: 폭 $plain -> $spaced")
        assertTrue("자간이 글자 폭에 반영되지 않았다 ($plain -> $spaced)", spaced > plain)

        val text = book(20)
        val base = lineCountOf(text, paint())
        val wide = lineCountOf(text, paint(letterSpacingEm = 0.3f))
        Log.i("txtEbookTest", "줄 수 기본 $base -> 자간0.3 $wide")
        assertTrue("자간을 크게 벌렸는데 줄 수가 그대로다 ($base)", wide > base)
    }

    @Test
    fun 장평이_줄_길이를_바꾼다() {
        val text = book(20)
        val base = lineCountOf(text, paint())
        val stretched = lineCountOf(text, paint(stretch = 1.3f))
        Log.i("txtEbookTest", "줄 수 기본 $base -> 장평130% $stretched")
        assertTrue("장평이 조판에 반영되지 않았다 ($base -> $stretched)", stretched > base)
    }

    // ------------------------------------------------------------ 성능

    @Test
    fun 두꺼운_책도_첫_쪽이_금방_나온다() {
        // 실제로 문제가 됐던 9MB 책과 비슷한 규모 (400만 자).
        val big = StringBuilder()
        while (big.length < 3_800_000) big.append(book(60)).append('\n')
        val text = big.toString()
        val runtime = Runtime.getRuntime()

        val engine = Paginator(text, paint(), 1210, 1180, true)
        val first = measureTimeMillis { engine.paginateNext() }
        val firstPageMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1048576

        val rest = measureTimeMillis {
            @Suppress("ControlFlowWithEmptyBody")
            while (engine.paginateNext());
        }
        val doneMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1048576

        Log.i(
            "txtEbookTest",
            "${text.length / 1000}k 자: 첫 덩어리 ${first}ms(${firstPageMemory}MB) / " +
                "전체 ${first + rest}ms(${doneMemory}MB) / ${engine.pageCount}쪽 / " +
                "${engine.chunkCount}덩어리 / 최대 ${runtime.maxMemory() / 1048576}MB",
        )
        assertTrue("첫 덩어리에 ${first}ms 걸렸다 - 너무 느리다", first < 3_000)
        assertTrue(
            "힙을 ${doneMemory}MB 썼다 - 덩어리 조판이 메모리를 붙잡고 있다",
            doneMemory < runtime.maxMemory() / 1048576 * 0.6,
        )
    }

    /** 테스트용: 모든 줄을 같은 높이로 만든다. */
    private class FixedLineHeight(private val height: Int) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence, start: Int, end: Int,
            spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt,
        ) {
            fm.descent = height / 2
            fm.ascent = fm.descent - height
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
    }
}
