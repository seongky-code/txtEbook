package com.txtebook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 책 지면을 그리는 화면.
 *
 * 한 장 또는 두 장을 나란히 보여 주고, 눌러서 넘긴다. 조판이 다시 짜여도 읽던
 * 자리를 잃지 않는 것이 이 화면의 핵심 책임이다.
 */
class ReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private companion object {
        /** 이만큼 걸린 조판만 쪽 표로 적어 둔다. 금방 끝나는 책은 그냥 다시 센다. */
        const val CACHE_WORTH_MS = 600L
    }

    var settings: Settings = Settings()
    var onPageChanged: ((page: Int, pageCount: Int) -> Unit)? = null

    /** 지면 가운데를 눌렀을 때. 위·아래 막대를 여닫는다. */
    var onChromeToggle: (() -> Unit)? = null

    private var body: String = ""
    private var paginator: Paginator? = null

    /**
     * 아직 화면에 올리지 못한, 지금 짜고 있는 조판.
     *
     * 책 중간에서 이어 읽을 때는 그 자리가 나올 때까지 기다려야 한다. 얼마나
     * 남았는지 보여 주려고 들고 있는다.
     */
    private var pending: Paginator? = null

    /** 왼쪽(또는 단독) 지면의 쪽 번호. */
    var page: Int = 0
        private set

    /**
     * 읽던 자리 (글자 위치).
     *
     * 페이지를 넘길 때만 갱신한다. 조판을 바꿀 때마다 다시 계산하면 페이지 첫
     * 글자로 조금씩 당겨져서 설정을 만질수록 뒤로 밀린다.
     */
    var anchor: Int = 0
        private set

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Number) = value.toFloat() * density

    val pageCount: Int get() = paginator?.pageCount ?: 0

    /** 본문 글자 수. 책장에서 몇 퍼센트 읽었는지 셀 때 쓴다. */
    val bodyLength: Int get() = body.length
    val spread: Int get() = settings.pagesPerSpread(width > height)

    /** 책이 들어와 있는가. 조판이 끝났는지와는 별개다 (조판 중에도 책은 있다). */
    val hasBook: Boolean get() = body.isNotEmpty()

    // ------------------------------------------------------------ 조판 스레드

    private val worker = HandlerThread("paginate").apply { start() }
    private val work = Handler(worker.looper)
    private val ui = Handler(Looper.getMainLooper())

    /**
     * 지금 유효한 조판 요청 번호.
     *
     * 조판은 도중에 멈출 수가 없다. 뒤늦게 끝난 옛 요청이 새 조판을 덮어쓰지 않도록
     * 번호를 붙여 보내고, 돌아왔을 때 번호가 바뀌었으면 결과를 버린다.
     */
    @Volatile
    private var generation = 0

    // ------------------------------------------------------------ 본문 넣기

    /** 줄바꿈 처리 방식을 바꾸면 다시 가공해야 하므로 원문을 들고 있는다. */
    private var rawBody: String = ""

    /** 지난번에 세어 둔 쪽 표. 같은 책을 같은 설정으로 열면 조판을 건너뛴다. */
    private val pageCache = PageCache(context)

    /** 어느 책인가 (파일 주소). 쪽 표를 찾을 때 이름표의 일부로 쓴다. */
    private var bookId: String = ""

    /**
     * 조판 결과를 가리키는 이름표.
     *
     * 같은 조판이면 같은 문자열이 나와야 하고, 결과가 달라지는 값이 하나라도 바뀌면
     * 달라져야 한다. 본문은 길이와 해시로 가른다 — 파일을 고쳐 쓰면 여기서 걸린다.
     */
    private fun layoutKey(pageW: Int, pageH: Int) = buildString {
        append(bookId).append('|')
        append(body.length).append(':').append(body.hashCode()).append('|')
        append(settings.font.family).append('|')
        append(textPaint.textSize).append('|')
        append(textPaint.letterSpacing).append('|')
        append(textPaint.textScaleX).append('|')
        append(settings.lineHeight).append('|')
        append(settings.paraSpacing).append('|')
        append(settings.indent).append('|')
        append(settings.justify).append('|')
        append(pageW).append('x').append(pageH)
    }

    /** 새 책을 연다. @param startChar 이어 읽을 글자 위치. @param id 쪽 표를 찾을 이름. */
    fun setBook(rawText: String, startChar: Int = 0, id: String = "") {
        rawBody = rawText
        bookId = id
        prepareBody(rawText, settings.lineMode) { prepared ->
            body = prepared
            anchor = startChar.coerceIn(0, prepared.length)
            rebuild()
        }
    }

    /**
     * 문단 정리를 워커에서 한다.
     *
     * 9MB 책은 이 일에만 1.3초가 걸린다. UI 스레드에서 하면 그동안 화면이 멎어서
     * 손가락에 아무 반응이 없다.
     */
    private fun prepareBody(raw: String, mode: LineMode, then: (String) -> Unit) {
        val token = ++generation        // 돌고 있던 조판을 무효로 만든다
        paginator = null
        pending = null
        invalidate()
        work.post {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val prepared = buildParagraphs(raw, mode)
            android.util.Log.i(
                "txtEbookPerf",
                "문단 정리 ${android.os.SystemClock.elapsedRealtime() - t0}ms | " +
                    "원문 ${raw.length / 1000}k -> 본문 ${prepared.length / 1000}k 글자 | " +
                    "문단 ${prepared.count { it == '\n' } + 1}개"
            )
            ui.post apply@{
                if (token != generation) return@apply
                then(prepared)
            }
        }
    }

    fun clear() {
        rawBody = ""
        bookId = ""
        body = ""
        generation++          // 돌고 있는 조판이 돌아와도 물러나게 한다
        paginator = null
        pending = null
        invalidate()
    }

    /** 설정이 바뀌었을 때. 읽던 자리를 유지한 채 다시 조판한다. */
    fun applySettings(rebuildText: Boolean = false) {
        if (rebuildText && rawBody.isNotEmpty()) {
            val previous = body.length.coerceAtLeast(1)
            val at = anchor
            prepareBody(rawBody, settings.lineMode) { prepared ->
                // 문단을 합치면 본문 길이가 달라지므로 앵커를 비례로 옮긴다.
                anchor = (at.toLong() * prepared.length / previous).toInt()
                    .coerceIn(0, prepared.length)
                body = prepared
                rebuild()
            }
            return
        }
        // 본문이 그대로면 지금 지면을 계속 보여 준 채로 새 조판을 짠다. 슬라이더를
        // 만질 때마다 화면이 `조판 중…` 으로 깜빡이지 않게 하기 위해서다.
        rebuild(keepCurrent = true)
    }

    // ------------------------------------------------------------ 조판

    private fun buildPaint() {
        textPaint.typeface = Typeface.create(settings.font.family, Typeface.NORMAL)
        // scaledDensity 는 deprecated 다. 글자 크기 배율을 제대로 반영하는 변환을 쓴다.
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, settings.fontSize, resources.displayMetrics,
        )
        textPaint.color = settings.theme.text
        // 장평. Qt 의 setStretch 와 같은 자리다.
        textPaint.textScaleX = settings.stretch / 100f
        // 자간은 em 단위라서 글자 크기로 나눠 준다. 그래야 dp 로 지정한 값이
        // 글자 크기와 상관없이 같은 폭으로 보인다.
        textPaint.letterSpacing =
            if (textPaint.textSize > 0) dp(settings.letterSpacing) / textPaint.textSize else 0f
    }

    /**
     * 두 쪽을 나란히 놓을 때 가운데 간격.
     *
     * 쪽마다 안쪽에도 바깥과 같은 여백을 준다. 그래야 네 변의 여백이 같아 보이고,
     * 여백 설정이 가운데에도 그대로 먹는다. 여백을 0 까지 줄여도 가운데 선이
     * 글자를 물지 않도록 조금은 남긴다.
     */
    private fun gutter(): Float = (dp(settings.marginH) * 2).coerceAtLeast(dp(8))

    private fun pageBox(): Pair<Int, Int> {
        val marginH = dp(settings.marginH).toInt()
        val marginV = dp(settings.marginV).toInt()
        val columns = spread
        val usable = width - marginH * 2 - if (columns == 2) gutter().toInt() else 0
        val pageW = (usable / columns).coerceAtLeast(1)
        val pageH = (height - marginV * 2).coerceAtLeast(1)
        return pageW to pageH
    }

    /**
     * 줄 높이 · 문단 들여쓰기 · 문단 사이 간격을 입힌다.
     *
     * 줄 높이를 직접 못박는 이유가 있다. 안드로이드는 줄마다 그 줄에 실제로 쓰인
     * 글꼴의 metrics 로 높이를 정하는데, 한글 글꼴과 라틴 글꼴의 metrics 가 달라서
     * 영문이 섞인 줄만 유독 넓어진다. 배율(setLineSpacing)을 쓰면 그 차이까지 같이
     * 곱해져 줄 간격이 들쭉날쭉해진다. 책은 줄 간격이 일정해야 읽힌다.
     */
    private fun styledBody(): CharSequence {
        val metrics = textPaint.fontMetricsInt
        val natural = (metrics.descent - metrics.ascent).coerceAtLeast(1)
        val lineHeight = (natural * settings.lineHeight / 100f).toInt().coerceAtLeast(1)
        val indent = dp(settings.indent).toInt()
        val gap = dp(settings.paraSpacing).toInt()

        val spannable = SpannableString(body)
        // span 은 문단마다가 아니라 본문 전체에 하나만 건다.
        //
        // SpannableString 의 span 조회(nextSpanTransition, getSpans)는 span 목록을
        // 처음부터 끝까지 훑는 선형 검색이고, StaticLayout 은 그것을 문단마다 부른다.
        // 문단마다 span 을 달면 O(문단²) 이 되어, 문단 수만 개짜리 책은 조판이
        // 사실상 끝나지 않는다 (문단 4.7만 개 책에서 메인 스레드 38초 후 ANR).
        // 문단 경계는 span 이 아니라 줄 끝의 개행으로 가른다.
        spannable.setSpan(
            ParagraphMetrics(lineHeight, gap),
            0, body.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (indent > 0) {
            // LeadingMarginSpan 은 문단 단위 span 이라, 전체에 걸어도 문단마다
            // 첫 줄에만 들여쓰기가 들어간다.
            spannable.setSpan(
                LeadingMarginSpan.Standard(indent, 0),
                0, body.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return spannable
    }

    /** 문단 안의 모든 줄을 같은 높이로 맞추고, 마지막 줄 아래에만 간격을 준다. */
    private class ParagraphMetrics(
        private val lineHeight: Int,
        private val gap: Int,
    ) : LineHeightSpan {
        override fun chooseHeight(
            text: CharSequence, start: Int, end: Int,
            spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt,
        ) {
            // 빈 줄은 손대지 않는다. 문단마다 span 을 달던 때도 빈 줄에는 span 이
            // 걸리지 않아 글꼴 본래 높이로 남았다. 조판을 그대로 지킨다.
            if (end - start <= 1 && (start >= end || text[start] == '\n')) return

            val natural = fm.descent - fm.ascent
            if (natural > 0) {
                // 글자는 아래쪽에 맞추고 남는 높이는 위로 준다.
                val ratio = this.lineHeight.toFloat() / natural
                fm.descent = Math.round(fm.descent * ratio)
                fm.ascent = fm.descent - this.lineHeight
            }
            // 문단의 마지막 줄인가. 줄 끝(end)은 개행을 포함하므로 그것으로 가른다.
            if (gap > 0 && (end >= text.length || text[end - 1] == '\n')) fm.descent += gap
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
    }

    /**
     * 조판을 백그라운드에 맡긴다.
     *
     * `StaticLayout` 을 짜는 데 두꺼운 책은 몇 초가 걸린다 (탭 S6 에서 3.2MB 책 5.7초).
     * 메인 스레드에서 하면 그동안 화면이 통째로 멈추고, ANR 한계에도 닿는다.
     *
     * @param keepCurrent 짜는 동안 지금 지면을 계속 보여 줄지. 설정만 바뀐 때는 그대로 두고
     *   (깜빡임 없이 새 조판으로 갈아탄다), 지면 크기나 본문이 달라진 때는 폭이 안 맞으므로
     *   치우고 `조판 중…` 을 보여 준다.
     */
    private fun rebuild(keepCurrent: Boolean = false) {
        if (body.isEmpty() || width == 0 || height == 0) {
            generation++
            paginator = null
            pending = null
            invalidate()
            return
        }
        buildPaint()
        val (pageW, pageH) = pageBox()

        // 워커에 넘길 것은 여기서 다 만들어 둔다. settings 와 textPaint 는 UI 스레드가
        // 계속 고치므로 워커가 직접 들여다보면 안 된다. 특히 paint 는 복사본을 준다.
        // 그래야 다음 설정 변경이 아직 화면에 떠 있는 옛 조판의 글자까지 바꿔 놓지 않는다.
        val text = styledBody()
        val paint = TextPaint(textPaint)
        val justify = settings.justify
        val key = layoutKey(pageW, pageH)
        val token = ++generation

        if (!keepCurrent) {
            paginator = null
            invalidate()
        }

        // 아직 시작도 안 한 옛 요청은 버린다. 이미 돌고 있는 것은 멈출 수 없으므로
        // 끝나고 나서 번호를 보고 스스로 물러난다.
        work.removeCallbacksAndMessages(null)
        work.post paginate@{
            if (token != generation) return@paginate
            val t0 = android.os.SystemClock.elapsedRealtime()
            val job = PaginateJob(
                token = token,
                built = Paginator(text, paint, pageW, pageH, justify),
                target = anchor,
                key = key,
                t0 = t0,
            )
            val ready = android.os.SystemClock.elapsedRealtime()

            // 덩어리를 하나씩 조판한다. 읽던 자리가 나오는 순간 바로 화면에 올리고,
            // 나머지 쪽수는 뒤에서 계속 센다. 책 전체를 다 짜고 나서 보여 주면
            // 9MB 책은 8초를 기다려야 한다.
            ui.post { if (token == generation) { pending = job.built; invalidate() } }

            // 지난번에 세어 둔 쪽 표가 있으면 조판을 통째로 건너뛴다.
            val saved = pageCache.load(key)
            val loaded = android.os.SystemClock.elapsedRealtime()
            if (saved != null && job.built.restore(saved)) {
                android.util.Log.i(
                    "txtEbookPerf",
                    "쪽 표 되살리기: 덩어리 나누기 ${ready - t0}ms + " +
                        "읽기 ${loaded - ready}ms + 검사 " +
                        "${android.os.SystemClock.elapsedRealtime() - loaded}ms " +
                        "(${saved.size}쪽)"
                )
                showFirstPage(job, "저장해 둔 쪽 표")
                return@paginate
            }
            paginateStep(job)
        }
    }

    /** 한 번의 조판 요청. 워커 스레드에서만 만진다. */
    private class PaginateJob(
        val token: Int,
        val built: Paginator,
        val target: Int,
        val key: String,
        val t0: Long,
    ) {
        /** 읽던 자리를 화면에 올렸는가. */
        var shown = false
    }

    /** 읽던 자리를 화면에 올린다. 지면은 그릴 때 그 쪽만 조판한다. */
    private fun showFirstPage(job: PaginateJob, how: String) {
        if (job.token != generation) return
        job.shown = true
        android.util.Log.i(
            "txtEbookPerf",
            "첫 쪽까지 ${android.os.SystemClock.elapsedRealtime() - job.t0}ms ($how, " +
                "덩어리 ${job.built.doneChunks}/${job.built.chunkCount})"
        )
        ui.post show@{
            if (job.token != generation) return@show
            pending = null
            paginator = job.built
            goToChar(job.target)
        }
    }

    /**
     * 덩어리 하나를 조판하고, 남았으면 다음 덩어리를 새 메시지로 건다.
     *
     * 한 메시지 안에서 끝까지 도는 편이 짧지만, 이 워커는 그리기 준비(`prepare`)도
     * 맡는다. 조판이 큐를 통째로 붙들고 있으면 덩어리 경계를 넘어간 지면은 책 전체
     * 조판이 끝날 때까지 빈 채로 남는다 (9MB 책에서 8초). 덩어리마다 끊어 주면
     * 기다림이 덩어리 하나 몫으로 줄어든다.
     */
    private fun paginateStep(job: PaginateJob) {
        if (job.token != generation) return
        val more = job.built.paginateNext()
        if (job.token != generation) return

        if (!job.shown && (job.built.covers(job.target) || !more)) {
            showFirstPage(job, "조판")
        } else if (!job.shown) {
            // 아직 읽던 자리가 안 나왔다. 얼마나 왔는지만 알린다.
            ui.post { if (job.token == generation) invalidate() }
        } else {
            // 쪽수가 늘어난 것만 알린다. 읽던 자리는 건드리지 않는다.
            ui.post count@{
                if (job.token != generation) return@count
                onPageChanged?.invoke(page, job.built.pageCount)
            }
        }

        if (more) {
            work.post { paginateStep(job) }
            return
        }
        finishPagination(job)
    }

    /** 다 셌다. 다음에 열 때 쓰도록 쪽 표를 적어 둔다. */
    private fun finishPagination(job: PaginateJob) {
        val spent = android.os.SystemClock.elapsedRealtime() - job.t0
        // 금방 끝나는 책까지 적어 두면 저장 칸만 차지한다. 기다릴 만했던 것만 남긴다.
        val saved = spent >= CACHE_WORTH_MS && job.token == generation
        if (saved) pageCache.save(job.key, job.built.pages)

        val rt = Runtime.getRuntime()
        android.util.Log.i(
            "txtEbookPerf",
            "조판 완료 ${spent}ms | 덩어리 ${job.built.chunkCount} | " +
                "쪽 ${job.built.pageCount} | 쪽 표 ${if (saved) "저장" else "안 함"} | " +
                "힙 ${(rt.totalMemory() - rt.freeMemory()) / 1048576}MB / " +
                "최대 ${rt.maxMemory() / 1048576}MB"
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        worker.quit()
    }

    // ------------------------------------------------------------ 페이지 이동

    /** 그 글자가 보이는 페이지로 가고, 그 위치를 앵커로 유지한다. */
    fun goToChar(charOffset: Int) {
        val engine = paginator ?: run { invalidate(); return }
        goToPage(engine.pageOf(charOffset), keepAnchor = charOffset)
    }

    fun goToPage(target: Int, keepAnchor: Int? = null) {
        val engine = paginator ?: return
        val step = spread
        val clamped = target.coerceIn(0, engine.pageCount - 1)
        page = (clamped / step) * step
        anchor = keepAnchor ?: engine.charAt(page)
        invalidate()
        onPageChanged?.invoke(page, engine.pageCount)
    }

    fun nextPage() = goToPage(page + spread)
    fun prevPage() = goToPage(page - spread)

    // ------------------------------------------------------------ 그리기

    override fun onDraw(canvas: Canvas) {
        val theme = settings.theme
        canvas.drawColor(theme.desk)

        val engine = paginator
        if (engine == null) {
            hintPaint.color = theme.text
            hintPaint.textSize = dp(15)
            // 두꺼운 책을 중간부터 이어 읽을 때는 그 자리가 나올 때까지 기다려야 한다.
            // 얼마나 왔는지 보여 줘야 멈춘 것으로 보이지 않는다.
            val waiting = pending
            val message = when {
                body.isEmpty() -> "위쪽의 열기 버튼으로 txt 파일을 고르세요"
                waiting != null && waiting.chunkCount > 1 ->
                    "조판 중… ${(waiting.progress * 100).toInt()}%"
                else -> "조판 중…"
            }
            canvas.drawText(message, width / 2f, height / 2f, hintPaint)
            return
        }

        pagePaint.color = theme.page
        edgePaint.color = theme.line

        val marginH = dp(settings.marginH)
        val marginV = dp(settings.marginV)
        val gap = gutter()
        val columns = spread
        val pageW = engine.pageWidth.toFloat()

        // 종이를 화면 전체에 깔고, 그 위에 가운데 선을 긋고, 마지막에 글자를 얹는다.
        // 여백은 종이의 일부다. 종이 바깥 색이 비치면 여백이 아니라 지면이 좁아진
        // 것처럼 읽힌다.
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pagePaint)
        if (columns == 2) {
            val seam = marginH + pageW + gap / 2f
            canvas.drawLine(seam, 0f, seam, height.toFloat(), edgePaint)
        }
        for (column in 0 until columns) {
            val index = page + column
            if (index >= engine.pageCount) continue
            canvas.save()
            canvas.translate(marginH + column * (pageW + gap), marginV)
            engine.drawPage(canvas, index)
            canvas.restore()
        }
    }

    // ------------------------------------------------------------ 손가락

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (paginator == null) return false
            // 지면을 세로로 셋으로 나눈다. 왼쪽은 '이전', 가운데는 막대 여닫기,
            // 나머지는 '다음'. '이전'을 좁게 둬서 실수로 되돌아가지 않게 한다.
            when {
                e.x < width * 0.25f -> prevPage()
                e.x < width * 0.55f -> onChromeToggle?.invoke()
                else -> nextPage()
            }
            return true
        }

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float,
        ): Boolean {
            if (paginator == null || e1 == null) return false
            val dx = e2.x - e1.x
            if (abs(dx) < dp(40) || abs(dx) < abs(e2.y - e1.y)) return false
            if (dx < 0) nextPage() else prevPage()   // 왼쪽으로 밀면 다음 장
            return true
        }
    })

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)
}
