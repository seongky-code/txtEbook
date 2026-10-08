package com.txtebook

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * 조판 설정 패널.
 *
 * 지면 위에 겹쳐 뜬다. 레이아웃에서 자리를 차지하면 패널을 여닫을 때마다 지면
 * 폭이 달라져서 조판이 다시 짜이고 읽던 쪽이 바뀐다.
 */
class SettingsPanel(context: Context) : FrameLayout(context) {

    /** 값이 바뀌었을 때. rebuildText 면 본문 가공부터 다시 해야 한다. */
    var onChanged: ((rebuildText: Boolean) -> Unit)? = null

    private lateinit var settings: Settings
    private val rows = ArrayList<SliderRow>()
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        setPadding(pad, dp(12), pad, dp(28))
    }
    private var muted = false

    private fun dp(value: Number) = (value.toFloat() * resources.displayMetrics.density).toInt()

    init {
        addView(
            ScrollView(context).apply { addView(body) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        isClickable = true      // 뒤쪽 지면으로 터치가 새지 않게
    }

    // ------------------------------------------------------------ 만들기

    fun build(current: Settings) {
        settings = current
        body.removeAllViews()
        rows.clear()

        heading("글꼴")
        choices(FontFamily.entries.map { it.label }, settings.fontIndex) { index ->
            settings.fontIndex = index
            emit(false)
        }
        slider("크기", settings.fontSize, 8f, 40f, 1, "sp") { settings.fontSize = it }

        heading("조판")
        slider("자간", settings.letterSpacing, -1.5f, 5f, 10, "dp") { settings.letterSpacing = it }
        slider("장평", settings.stretch.toFloat(), 50f, 200f, 1, "%") { settings.stretch = it.toInt() }
        slider("줄간격", settings.lineHeight.toFloat(), 100f, 300f, 1, "%") { settings.lineHeight = it.toInt() }
        slider("문단 간격", settings.paraSpacing.toFloat(), 0f, 40f, 1, "dp") { settings.paraSpacing = it.toInt() }
        slider("들여쓰기", settings.indent.toFloat(), 0f, 60f, 1, "dp") { settings.indent = it.toInt() }

        heading("여백")
        // 두 쪽 보기에서는 가운데 간격도 이 값을 따른다 (쪽마다 안쪽에도 같은 여백).
        slider("좌우", settings.marginH.toFloat(), 0f, 120f, 1, "dp") { settings.marginH = it.toInt() }
        slider("상하", settings.marginV.toFloat(), 0f, 120f, 1, "dp") { settings.marginV = it.toInt() }

        heading("보기")
        choices(listOf("화면에 맞춤", "한 쪽", "두 쪽"), settings.pageMode) { index ->
            settings.pageMode = index
            emit(false)
        }
        label("테마")
        choices(THEMES.map { it.label }, settings.themeIndex) { index ->
            settings.themeIndex = index
            emit(false)
        }
        val justify = CheckBox(context).apply {
            text = "양쪽 정렬"
            isChecked = settings.justify
            textSize = 15f
            minHeight = dp(48)
            setTextColor(settings.theme.onUi)
            setOnCheckedChangeListener { _, checked ->
                if (!muted) { settings.justify = checked; emit(false) }
            }
        }
        body.addView(justify)

        label("줄바꿈")
        choices(listOf("자동", "원문 그대로", "문단으로 합치기"), settings.lineMode.ordinal) { index ->
            settings.lineMode = LineMode.entries[index]
            emit(true)      // 본문 가공부터 다시
        }

        space(12)
        body.addView(flatButton("기본값으로") { reset() })
    }

    // ------------------------------------------------------------ 조각들

    private fun heading(text: String) {
        body.addView(TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(settings.theme.onUi)
            alpha = 0.85f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(4))
        })
    }

    private fun label(text: String) {
        body.addView(TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(settings.theme.onUi)
            alpha = 0.7f
            setPadding(0, dp(10), 0, dp(2))
        })
    }

    private fun space(height: Int) {
        body.addView(View(context), LinearLayout.LayoutParams(1, dp(height)))
    }

    /** 값 하나를 담당하는 줄: 이름 · 현재값 · [−][슬라이더][+] */
    private inner class SliderRow(
        val name: String,
        val low: Float,
        val high: Float,
        val scale: Int,          // 1 이면 정수, 10 이면 0.1 단위
        val unit: String,
        val apply: (Float) -> Unit,
    ) {
        lateinit var seek: SeekBar
        lateinit var readout: TextView

        val steps: Int get() = ((high - low) * scale).toInt()
        fun valueOf(progress: Int): Float = low + progress.toFloat() / scale
        fun progressOf(value: Float): Int = ((value - low) * scale).toInt().coerceIn(0, steps)

        fun show(progress: Int) {
            val value = valueOf(progress)
            readout.text = if (scale > 1) "%.1f %s".format(value, unit)
            else "%d %s".format(value.toInt(), unit)
        }

        fun nudge(delta: Int) {
            seek.progress = (seek.progress + delta).coerceIn(0, steps)
        }
    }

    private fun slider(
        name: String, value: Float, low: Float, high: Float,
        scale: Int, unit: String, apply: (Float) -> Unit,
    ) {
        val row = SliderRow(name, low, high, scale, unit, apply)
        rows.add(row)

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(context).apply {
                text = name
                textSize = 13f
                setTextColor(settings.theme.onUi)
                alpha = 0.7f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.readout = TextView(context).apply {
                textSize = 13f
                setTextColor(settings.theme.onUi)
            }
            addView(row.readout)
        }
        body.addView(head)

        row.seek = SeekBar(context).apply {
            max = row.steps
            progress = row.progressOf(value)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    row.show(progress)
                    if (!muted) { row.apply(row.valueOf(progress)); emit(false) }
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        row.show(row.seek.progress)

        // 슬라이더는 원하는 값에 딱 맞추기 어려워서 양옆에 한 눈금 버튼을 둔다.
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stepButton("−") { row.nudge(-1) })
            addView(row.seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(stepButton("+") { row.nudge(+1) })
        }
        body.addView(line)
    }

    /** 미세조정 버튼. 터치 최소 크기(48dp)를 지킨다. */
    private fun stepButton(glyph: String, action: () -> Unit): View =
        TextView(context).apply {
            text = glyph
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(settings.theme.onUi)
            minWidth = dp(48)
            minHeight = dp(48)
            width = dp(48)
            height = dp(48)
            background = outline()
            isClickable = true
            setOnClickListener { action() }
        }

    private fun choices(labels: List<String>, selected: Int, pick: (Int) -> Unit) {
        val group = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = ArrayList<TextView>()
        labels.forEachIndexed { index, text ->
            val button = TextView(context).apply {
                this.text = text
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setTextColor(settings.theme.onUi)
                background = outline(filled = index == selected)
                isClickable = true
                setPadding(dp(6), 0, dp(6), 0)
            }
            button.setOnClickListener {
                buttons.forEachIndexed { i, b -> b.background = outline(filled = i == index) }
                pick(index)
            }
            buttons.add(button)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            params.marginEnd = if (index < labels.lastIndex) dp(6) else 0
            group.addView(button, params)
        }
        body.addView(group)
    }

    private fun flatButton(text: String, action: () -> Unit): View =
        Button(context).apply {
            this.text = text
            textSize = 14f
            minHeight = dp(48)
            setTextColor(settings.theme.onUi)
            background = outline()
            setOnClickListener { action() }
        }

    private fun outline(filled: Boolean = false) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(6).toFloat()
        setStroke(dp(1), settings.theme.line)
        setColor(if (filled) settings.theme.line else Color.TRANSPARENT)
    }

    // ------------------------------------------------------------ 값 반영

    private fun emit(rebuildText: Boolean) {
        onChanged?.invoke(rebuildText)
    }

    private fun reset() {
        val defaults = Settings()
        settings.fontIndex = defaults.fontIndex
        settings.fontSize = defaults.fontSize
        settings.letterSpacing = defaults.letterSpacing
        settings.stretch = defaults.stretch
        settings.lineHeight = defaults.lineHeight
        settings.paraSpacing = defaults.paraSpacing
        settings.indent = defaults.indent
        settings.marginH = defaults.marginH
        settings.marginV = defaults.marginV
        settings.justify = defaults.justify
        build(settings)     // 화면을 새로 그려서 값들을 맞춘다
        emit(false)
    }

    /** 테마가 바뀌면 패널 색도 따라가야 한다. */
    fun repaint() {
        setBackgroundColor(settings.theme.ui)
        muted = true
        build(settings)
        muted = false
    }
}
