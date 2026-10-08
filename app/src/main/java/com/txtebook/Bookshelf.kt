package com.txtebook

import android.content.Context
import android.graphics.Color
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/**
 * 읽던 책 목록.
 *
 * 기기의 문서 고르기 화면은 열 때마다 정렬이 처음으로 돌아가고, 어디까지 읽었는지도
 * 알 수 없다. 한 번 연 책은 여기에 꽂아 두고 우리가 줄 세운다. 정렬 방식은 앱을
 * 닫아도 남는다.
 */
class Bookshelf(context: Context) : FrameLayout(context) {

    /** 목록에서 책을 골랐을 때. */
    var onPick: ((BookRecord) -> Unit)? = null

    /** `기기에서 고르기` 를 눌렀을 때. */
    var onBrowse: (() -> Unit)? = null

    /** `폴더에서 찾기` 를 눌렀을 때. */
    var onFolder: (() -> Unit)? = null

    /** 목록을 닫을 때. */
    var onClose: (() -> Unit)? = null

    private lateinit var library: Library
    private var theme: ReaderTheme = THEMES[0]

    private val title = TextView(context).apply {
        text = "책장"
        textSize = 17f
    }
    private val sortButton = button("최근순") { cycleSort() }
    private val closeButton = button("닫기") { onClose?.invoke() }
    private val browseButton = button("기기에서 고르기") { onBrowse?.invoke() }
    private val folderButton = button("폴더에서 찾기") { onFolder?.invoke() }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun dp(value: Number) = (value.toFloat() * resources.displayMetrics.density).toInt()

    init {
        isClickable = true      // 뒤쪽 지면으로 터치가 새지 않게
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(8), dp(4))
                    addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
                    addView(sortButton)
                    addView(closeButton)
                },
                LinearLayout.LayoutParams(MATCH, WRAP),
            )
            // 폴더를 한 번 지정해 두면 기기 화면을 거치지 않고 여기서 바로 고른다.
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(folderButton, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                        marginEnd = dp(8)
                    })
                    addView(browseButton, LinearLayout.LayoutParams(0, WRAP, 1f))
                },
                LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    setMargins(dp(16), dp(4), dp(16), dp(8))
                },
            )
            addView(
                ScrollView(context).apply { addView(list) },
                LinearLayout.LayoutParams(MATCH, 0, 1f),
            )
        }
        addView(root, LayoutParams(MATCH, MATCH))
    }

    private fun button(label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        isClickable = true
        setOnClickListener { action() }
    }

    /** 책장을 열어 보인다. 목록은 열 때마다 새로 그린다 (몇십 권뿐이다). */
    fun show(library: Library, theme: ReaderTheme) {
        this.library = library
        this.theme = theme
        visibility = View.VISIBLE
        bringToFront()
        repaint()
        fill()
        // 막대 없이 화면을 다 덮으므로, 시스템 막대 자리는 직접 비켜 준다.
        requestApplyInsets()
    }

    fun hide() {
        visibility = View.GONE
    }

    private fun cycleSort() {
        if (!this::library.isInitialized) return
        val all = BookSort.values()
        library.sort = all[(all.indexOf(library.sort) + 1) % all.size]
        repaint()
        fill()
    }

    private fun fill() {
        list.removeAllViews()
        val books = library.sortedBooks()
        if (books.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "아직 연 책이 없습니다.\n\n" +
                    "`폴더에서 찾기` 로 책이 담긴 폴더를 한 번 정해 두면 다음부터 여기서 바로 " +
                    "고를 수 있습니다. 한 파일만 열려면 `기기에서 고르기` 를 쓰세요."
                textSize = 14f
                setTextColor(faded())
                setPadding(dp(16), dp(24), dp(16), dp(16))
            })
            return
        }
        books.forEach { list.addView(row(it)) }
    }

    /** 책 한 줄: 제목 · 진행률 막대 · 마지막으로 읽은 때. */
    private fun row(book: BookRecord) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isClickable = true
        setOnClickListener { onPick?.invoke(book) }
        setOnLongClickListener { confirmForget(book); true }

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(context).apply {
                    text = book.title.ifBlank { "제목 없음" }
                    textSize = 16f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(theme.onUi)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(TextView(context).apply {
                    // 글자 수를 아직 모르는 책 (한 번도 끝까지 안 연 책)은 자리를 비운다.
                    text = book.percent?.let { "$it%" } ?: "—"
                    textSize = 14f
                    setPadding(dp(10), 0, 0, 0)
                    setTextColor(theme.onUi)
                })
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )

        addView(progressBar(book.percent ?: 0), LinearLayout.LayoutParams(MATCH, dp(3)).apply {
            topMargin = dp(8)
        })

        addView(TextView(context).apply {
            text = DateUtils.getRelativeTimeSpanString(
                book.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            )
            textSize = 12f
            setTextColor(faded())
            setPadding(0, dp(6), 0, 0)
        })
    }

    /** 얼마나 읽었는지 가로 막대로. 가중치로 칸을 나눠 그린다. */
    private fun progressBar(percent: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val read = percent.coerceIn(0, 100)
        addView(View(context).apply { setBackgroundColor(theme.onUi) },
            LinearLayout.LayoutParams(0, MATCH, read.toFloat()))
        addView(View(context).apply { setBackgroundColor(theme.line) },
            LinearLayout.LayoutParams(0, MATCH, (100 - read).toFloat()))
    }

    private fun confirmForget(book: BookRecord) {
        AlertDialog.Builder(context)
            .setMessage("`${book.title}` 을 책장에서 뺄까요?\n파일은 지우지 않습니다.")
            .setNegativeButton("그대로 두기", null)
            .setPositiveButton("빼기") { _, _ ->
                library.forget(book.uri)
                fill()
            }
            .show()
    }

    /** 테마 색을 갈아입는다. 열려 있으면 목록까지 다시 그린다 (줄마다 색이 박혀 있다). */
    fun applyTheme(theme: ReaderTheme) {
        this.theme = theme
        repaint()
        if (visibility == View.VISIBLE && this::library.isInitialized) fill()
    }

    /** 테마 색을 입힌다. */
    private fun repaint() {
        setBackgroundColor(theme.ui)
        title.setTextColor(theme.onUi)
        listOf(sortButton, closeButton, browseButton, folderButton)
            .forEach { it.setTextColor(theme.onUi) }
        if (this::library.isInitialized) sortButton.text = library.sort.label
        browseButton.setBackgroundColor(lineTint())
        folderButton.setBackgroundColor(lineTint())
    }

    /** 글자보다 한 단계 연한 색. 날짜처럼 곁들이는 글에 쓴다. */
    private fun faded() = Color.argb(150, Color.red(theme.onUi), Color.green(theme.onUi), Color.blue(theme.onUi))

    private fun lineTint() = Color.argb(60, Color.red(theme.onUi), Color.green(theme.onUi), Color.blue(theme.onUi))

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        const val WRAP = LayoutParams.WRAP_CONTENT
    }
}
