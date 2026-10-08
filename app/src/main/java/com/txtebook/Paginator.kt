package com.txtebook

import android.graphics.Canvas
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan

/**
 * 본문을 페이지로 나눈다.
 *
 * 책 전체를 한 번에 조판하지 않고 덩어리로 잘라 차례로 조판한다. 9MB 책을 통째로
 * 조판하면 8초 넘게 걸리고 힙을 110MB 쓴다. 조금만 더 두꺼우면 기기에 따라 그대로
 * 죽는다. 덩어리로 나누면 첫 쪽은 1초 안에 나오고, 메모리는 덩어리 몇 개분으로 묶인다.
 *
 * 지켜야 할 것이 세 가지 있다.
 *
 * - **덩어리는 문단 경계에서 자른다.** 문단이 덩어리를 걸치면 줄 나눔이 달라진다.
 * - **앵커 대칭성**: `charAt(page)` 와 `pageOf(char)` 는 같은 줄 단위로 맞물려야 한다.
 *   한쪽만 어긋나면 설정을 만질 때마다 페이지가 밀린다.
 * - **쪽수는 직접 센다**: 실제 줄이 들어간 만큼만 페이지로 친다.
 *
 * 조판(`paginateNext`)과 그리기 준비(`prepare`)는 워커 스레드에서 부르고,
 * 나머지 조회는 UI 스레드에서 부른다. 그 사이는 불변 스냅샷(`pages`)으로 주고받는다.
 */
class Paginator(
    private val text: CharSequence,
    private val paint: TextPaint,
    val pageWidth: Int,
    val pageHeight: Int,
    private val justify: Boolean,
) {
    private companion object {
        /**
         * 덩어리 하나의 목표 크기 (글자).
         *
         * 크게 잡을수록 첫 쪽이 늦게 나오고 메모리를 더 쓰지만, 덩어리 끝마다 생기는
         * '덜 찬 쪽'이 줄어든다. 20만 자면 대략 400쪽마다 한 번이라 눈에 띄지 않는다.
         */
        const val CHUNK_CHARS = 200_000

        /**
         * 지면 하나를 조판할 때 끝을 얼마나 넘겨서 짜는지 (글자).
         *
         * 지면 끝에서 딱 끊으면 마지막 줄이 문단 끝인 줄 알고 양쪽 맞춤이 풀리고
         * 문단 간격까지 붙는다. 한 줄 넘게만 더 있으면 된다.
         */
        const val LOOKAHEAD_CHARS = 400

        /** 그려 둔 지면 레이아웃을 몇 장까지 들고 있을지 (앞뒤로 오갈 때를 위해). */
        const val KEEP_PAGES = 6
    }

    /** 덩어리 경계 (글자 위치). 마지막 원소는 본문 끝이다. */
    private val chunkStart: IntArray = splitAtParagraphs()

    val chunkCount: Int get() = chunkStart.size - 1

    // --- 워커 전용 상태
    private val starts = ArrayList<Int>()
    private var nextChunk = 0

    // --- UI 와 나눠 보는 스냅샷
    /** 지금까지 확정된 페이지들의 시작 글자 위치. */
    @Volatile
    var pages: IntArray = IntArray(0)
        private set

    /** 조판이 끝난 덩어리 수. */
    @Volatile
    var doneChunks: Int = 0
        private set

    val pageCount: Int get() = pages.size
    val isComplete: Boolean get() = doneChunks >= chunkCount

    /** 조판 진행률 0..1. 쪽수를 세는 동안 화면에 보여 주기 위한 값이다. */
    val progress: Float
        get() = if (chunkCount == 0) 1f else doneChunks.toFloat() / chunkCount

    /** 여기까지는 조판이 끝났다 (글자 위치). */
    val coveredChars: Int get() = chunkStart[doneChunks]

    private fun splitAtParagraphs(): IntArray {
        val bounds = ArrayList<Int>()
        bounds.add(0)
        var at = 0
        while (at < text.length) {
            val target = (at + CHUNK_CHARS).coerceAtMost(text.length)
            if (target >= text.length) break
            // 문단 경계(개행)까지 밀어 준다. 없으면 그냥 거기서 자른다.
            var cut = -1
            for (i in target until minOf(text.length, target + CHUNK_CHARS / 2)) {
                if (text[i] == '\n') { cut = i + 1; break }
            }
            at = if (cut > at) cut else target
            bounds.add(at)
        }
        bounds.add(text.length)
        // 마지막 덩어리가 너무 짧으면 앞과 합친다.
        if (bounds.size >= 3 && bounds[bounds.size - 1] - bounds[bounds.size - 2] < CHUNK_CHARS / 4) {
            bounds.removeAt(bounds.size - 2)
        }
        return bounds.toIntArray()
    }

    private fun layoutOf(chunk: Int): StaticLayout {
        val from = chunkStart[chunk]
        val to = chunkStart[chunk + 1]
        // subSequence 는 span 도 잘라서 넘겨 준다 (줄 높이·들여쓰기가 그대로 유지된다).
        return build(text.subSequence(from, to))
    }

    /**
     * 조판 설정은 한 군데서만 정한다.
     *
     * 쪽을 셀 때(덩어리)와 그릴 때(지면 하나)가 조금이라도 다르면 줄이 다른 자리에서
     * 끊겨서, 쪽 경계의 글자가 겹치거나 빠진다.
     */
    private fun build(slice: CharSequence): StaticLayout =
        StaticLayout.Builder
            .obtain(slice, 0, slice.length, paint, pageWidth.coerceAtLeast(1))
            // 줄 높이는 본문에 걸어 둔 span 이 정한다 (글꼴이 섞여도 간격이 일정하도록).
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .setJustificationMode(
                if (justify) Layout.JUSTIFICATION_MODE_INTER_WORD
                else Layout.JUSTIFICATION_MODE_NONE
            )
            .build()

    // ------------------------------------------------------------ 조판 (워커)

    /**
     * 다음 덩어리 하나를 조판한다. 더 할 것이 남았으면 true.
     *
     * 한 번에 하나씩 하는 이유는, 덩어리 하나가 끝날 때마다 화면이 지금까지의
     * 결과로 읽을 수 있게 되기 때문이다.
     */
    fun paginateNext(): Boolean {
        if (nextChunk >= chunkCount) return false
        val chunk = nextChunk
        val layout = layoutOf(chunk)
        val base = chunkStart[chunk]

        var line = 0
        while (line < layout.lineCount) {
            starts.add(base + layout.getLineStart(line))
            val top = layout.getLineTop(line)
            var next = line
            // 이 쪽에 온전히 들어가는 마지막 줄까지 담는다. 줄을 반으로 자르지 않는다.
            while (next < layout.lineCount && layout.getLineBottom(next) - top <= pageHeight) next++
            line = if (next == line) line + 1 else next
        }

        nextChunk = chunk + 1
        pages = starts.toIntArray()
        doneChunks = nextChunk
        return nextChunk < chunkCount
    }

    /**
     * 지난번에 세어 둔 쪽 표를 그대로 받는다. 맞으면 조판을 통째로 건너뛴다.
     *
     * 덩어리 경계는 본문에서 바로 나오므로 (`splitAtParagraphs`) 본문이 같으면 같다.
     * 쪽 표가 그 본문의 것인지는 부르는 쪽이 이름표로 확인한다. 여기서는 표 자체가
     * 말이 되는지만 본다 — 0 에서 시작해 본문 안에서 계속 늘어나야 한다.
     */
    fun restore(saved: IntArray): Boolean {
        if (saved.isEmpty() || saved[0] != 0) return false
        for (i in saved.indices) {
            if (saved[i] >= text.length) return false
            if (i > 0 && saved[i] <= saved[i - 1]) return false
        }
        starts.clear()
        starts.ensureCapacity(saved.size)
        for (start in saved) starts.add(start)
        pages = saved
        nextChunk = chunkCount
        doneChunks = chunkCount
        return true
    }

    // ------------------------------------------------------------ 그리기

    /**
     * 그 페이지를 지금 그릴 수 있는가. 쪽 표에 들어 있으면 바로 그린다.
     */
    fun ready(page: Int): Boolean = page in 0 until pageCount

    /** 그 페이지가 속한 덩어리. */
    fun chunkOf(page: Int): Int {
        val offset = charAt(page)
        var low = 0
        var high = chunkCount - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (chunkStart[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }

    /**
     * 그릴 지면 하나만 조판한다.
     *
     * 예전에는 덩어리(20만 자) 전체를 조판해 두고 그 안에서 한 쪽을 떼어 그렸다.
     * 낡은 태블릿에서 덩어리 하나가 5.6초 걸려서, 책을 열 때도 덩어리를 넘어갈 때도
     * 그만큼 지면이 비어 있었다. 지면 하나는 수천 자뿐이라 수십 ms 면 된다.
     *
     * 줄 나눔은 쪽을 셀 때와 같아야 한다. 안드로이드의 기본 줄 나눔은 앞에서부터
     * 채우는 방식이라, **줄이 시작하는 자리**에서 잘라 다시 짜면 같은 자리에서 끊긴다.
     * 쪽의 첫 글자는 언제나 줄 시작이므로 (`pages` 는 줄 시작만 담는다) 그대로 맞는다.
     * 대신 두 가지를 더 맞춰 준다.
     *
     *  - **지면 끝을 조금 넘겨서 짠다** (`LOOKAHEAD_CHARS`). 끝에서 딱 끊으면 마지막
     *    줄이 문단 끝처럼 보여 양쪽 맞춤이 풀린다.
     *  - **문단 가운데서 시작하는 지면은 첫 줄 들여쓰기를 지운다.** 안 지우면 들여쓴
     *    만큼 폭이 줄어 줄 나눔까지 달라진다.
     */
    private fun pageLayout(page: Int): StaticLayout? {
        if (page !in 0 until pageCount) return null
        drawCache[page]?.let { return it }
        val (from, to) = rangeOf(page)
        if (to <= from) return null
        val tail = (to + LOOKAHEAD_CHARS).coerceAtMost(text.length)
        val slice = SpannableString(text.subSequence(from, tail))
        if (from > 0 && text[from - 1] != '\n') dropFirstIndent(slice)
        return build(slice).also { drawCache[page] = it }
    }

    /** 문단 가운데서 시작하는 지면의 첫 줄에는 들여쓰기가 붙으면 안 된다. */
    private fun dropFirstIndent(slice: SpannableString) {
        val spans = slice.getSpans(0, slice.length, LeadingMarginSpan::class.java)
        if (spans.isEmpty()) return
        // 다음 문단부터는 그대로 들여쓴다.
        val nextParagraph = slice.indexOf('\n') + 1
        spans.forEach { span ->
            slice.removeSpan(span)
            if (nextParagraph in 1 until slice.length) {
                slice.setSpan(span, nextParagraph, slice.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /** 앞뒤로 오갈 때를 위해 몇 장은 들고 있는다 (오래 안 쓴 것부터 버린다). */
    private val drawCache =
        object : LinkedHashMap<Int, StaticLayout>(KEEP_PAGES * 2, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Int, StaticLayout>,
            ): Boolean = size > KEEP_PAGES
        }

    // ------------------------------------------------------------ 위치 계산

    /** 그 페이지 첫 글자의 위치. `pageOf` 와 짝을 이룬다. */
    fun charAt(page: Int): Int =
        if (pageCount == 0) 0 else pages[page.coerceIn(0, pageCount - 1)]

    /** 그 글자가 보이는 페이지. `charAt` 과 짝을 이룬다. */
    fun pageOf(charOffset: Int): Int {
        if (pageCount == 0) return 0
        val offset = charOffset.coerceIn(0, text.length)
        var low = 0
        var high = pageCount - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (pages[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }

    /** 그 글자까지 조판이 끝났는가. */
    fun covers(charOffset: Int): Boolean = isComplete || charOffset < coveredChars

    /** 그 페이지가 담은 글자 범위. */
    private fun rangeOf(page: Int): Pair<Int, Int> {
        val from = charAt(page)
        val chunk = chunkOf(page)
        val chunkEnd = chunkStart[chunk + 1]
        val to = if (page + 1 < pageCount) minOf(pages[page + 1], chunkEnd) else chunkEnd
        return from to to
    }

    /**
     * 그 페이지가 실제로 채운 높이.
     *
     * 지면 높이만큼 잘라내면 안 된다. 마지막 줄 아래 남는 자리로 다음 쪽의 첫 줄
     * 윗부분이 비쳐서, 줄이 반쯤 잘린 채 나타난다.
     */
    fun pageContentHeight(page: Int): Int {
        val layout = pageLayout(page) ?: return 0
        val (from, to) = rangeOf(page)
        val end = to - from
        // 지면 끝을 넘겨 짠 뒷부분은 이 쪽의 것이 아니다.
        val lastLine =
            if (end >= layout.text.length) layout.lineCount - 1
            else (layout.getLineForOffset(end) - 1).coerceAtLeast(0)
        return layout.getLineBottom(lastLine)
    }

    /** 페이지 하나를 그린다. 캔버스는 이미 본문 상자 왼쪽 위로 옮겨져 있어야 한다. */
    fun drawPage(canvas: Canvas, page: Int) {
        val layout = pageLayout(page) ?: return
        canvas.save()
        // 클립을 걸어 두면 Layout 이 보이는 줄만 그린다. 넘겨 짠 뒷부분도 여기서 잘린다.
        canvas.clipRect(0, 0, pageWidth, minOf(pageHeight, pageContentHeight(page)))
        layout.draw(canvas)
        canvas.restore()
    }
}
