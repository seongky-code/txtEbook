package com.txtebook

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.provider.DocumentsContract
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * 폴더에서 책 고르기.
 *
 * 기기의 문서 고르기 화면(DocumentsUI)은 열 때마다 정렬이 기본값으로 돌아가고, 앱이
 * 손댈 수 있는 자리가 없다 — 넘길 수 있는 것은 형식 목록과 시작 폴더뿐이다. 그래서
 * 폴더를 한 번만 받아 두고(SAF 트리 권한), 목록은 우리가 그린다. 이름·수정시간·크기와
 * 오름/내림은 고른 대로 저장된다.
 */
class FolderBrowser(context: Context) : FrameLayout(context) {

    /** 파일을 골랐을 때. */
    var onPick: ((uri: Uri, title: String) -> Unit)? = null

    /** `폴더 바꾸기` 를 눌렀을 때. */
    var onChangeFolder: (() -> Unit)? = null

    var onClose: (() -> Unit)? = null

    private lateinit var library: Library
    private var theme: ReaderTheme = THEMES[0]

    /** 어디까지 들어와 있는지. 마지막이 지금 보고 있는 폴더다 (문서 번호 to 이름). */
    private val path = ArrayList<Pair<String, String>>()

    private val upButton = button("위로") { goUp() }
    private val title = TextView(context).apply {
        textSize = 16f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
    }
    private val closeButton = button("닫기") { onClose?.invoke() }
    private val sortButtons = FileSort.values().map { sort ->
        sort to button(sort.label) { chooseSort(sort) }
    }
    private val changeButton = button("폴더 바꾸기") { onChangeFolder?.invoke() }
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
                    setPadding(dp(8), dp(6), dp(8), 0)
                    addView(upButton)
                    addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
                    addView(closeButton)
                },
                LinearLayout.LayoutParams(MATCH, WRAP),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(8), dp(4))
                    sortButtons.forEach { (_, view) -> addView(view) }
                    addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                    addView(changeButton)
                },
                LinearLayout.LayoutParams(MATCH, WRAP),
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
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true
        setOnClickListener { action() }
    }

    /** 폴더를 연다. 지난번에 보던 자리에서 다시 시작한다. */
    fun show(library: Library, theme: ReaderTheme) {
        this.library = library
        this.theme = theme
        visibility = View.VISIBLE
        bringToFront()
        // 막대 없이 화면을 다 덮으므로 시스템 막대 자리는 직접 비켜 준다.
        requestApplyInsets()
        repaint()
        val tree = library.folderUri ?: return
        if (path.isEmpty()) {
            val root = DocumentsContract.getTreeDocumentId(Uri.parse(tree))
            path.add(root to folderName(tree))
            // 지난번에 들어가 있던 하위 폴더가 있으면 그리로 바로 간다.
            library.folderAt?.takeIf { it != root }?.let { path.add(it to "…") }
        }
        load()
    }

    fun hide() {
        visibility = View.GONE
    }

    /** 폴더를 새로 고를 때는 들어와 있던 자리도 버린다. */
    fun reset() {
        path.clear()
        if (this::library.isInitialized) library.folderAt = null
    }

    private fun goUp() {
        if (path.size <= 1) {
            onClose?.invoke()
            return
        }
        path.removeAt(path.size - 1)
        library.folderAt = path.last().first
        load()
    }

    private fun chooseSort(sort: FileSort) {
        // 보고 있던 기준을 또 누르면 오름/내림이 뒤집힌다. 다른 것을 누르면 방향은 그대로.
        if (library.fileSort == sort) library.fileAscending = !library.fileAscending
        else library.fileSort = sort
        repaint()
        load()
    }

    // ------------------------------------------------------------ 목록 읽기

    private fun load() {
        val tree = library.folderUri ?: return
        val at = path.lastOrNull()?.first ?: return
        title.text = path.joinToString(" / ") { it.second }
        message("읽는 중…")
        val sort = library.fileSort
        val ascending = library.fileAscending
        // 폴더 조회는 저장매체를 타므로 화면을 멈추지 않게 워커에서 한다.
        thread {
            val read = runCatching { query(Uri.parse(tree), at) }
            post {
                read.onSuccess { entries ->
                    render(Uri.parse(tree), sortEntries(entries, sort, ascending))
                }.onFailure {
                    message("이 폴더를 읽을 수 없습니다. `폴더 바꾸기` 로 다시 골라 주세요.")
                }
            }
        }
    }

    private fun query(tree: Uri, docId: String): List<FolderEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val found = ArrayList<FolderEntry>()
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: ""
                val folder = mime == DocumentsContract.Document.MIME_TYPE_DIR
                // txt 만 보여 준다. 형식이 엉뚱하게 붙어 오는 기기가 있어 이름도 같이 본다.
                val looksText =
                    name.endsWith(".txt", ignoreCase = true) || mime.startsWith("text/")
                if (!folder && !looksText) continue
                found.add(
                    FolderEntry(
                        docId = id,
                        name = name,
                        isFolder = folder,
                        size = cursor.getLong(3),
                        modified = cursor.getLong(4),
                    )
                )
            }
        }
        return found
    }

    // ------------------------------------------------------------ 그리기

    private fun render(tree: Uri, entries: List<FolderEntry>) {
        list.removeAllViews()
        if (entries.isEmpty()) {
            message("이 폴더에는 txt 파일이 없습니다.")
            return
        }
        // 이미 읽던 책이면 어디까지 읽었는지 같이 보여 준다.
        val read = library.books().associateBy({ it.uri }, { it.percent })
        entries.forEach { entry ->
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, entry.docId)
            list.addView(row(entry, uri, read[uri.toString()]))
        }
    }

    private fun row(entry: FolderEntry, uri: Uri, percent: Int?) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isClickable = true
        setOnClickListener {
            if (entry.isFolder) {
                path.add(entry.docId to entry.name)
                library.folderAt = entry.docId
                load()
            } else {
                onPick?.invoke(uri, entry.name.substringBeforeLast(DOT))
            }
        }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    TextView(context).apply {
                        text = if (entry.isFolder) "[폴더]  ${entry.name}" else entry.name
                        textSize = 16f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                        setTextColor(theme.onUi)
                    },
                    LinearLayout.LayoutParams(0, WRAP, 1f),
                )
                if (percent != null) {
                    addView(TextView(context).apply {
                        text = "$percent%"
                        textSize = 14f
                        setPadding(dp(10), 0, 0, 0)
                        setTextColor(theme.onUi)
                    })
                }
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
        if (!entry.isFolder) {
            addView(TextView(context).apply {
                val ago = DateUtils.getRelativeTimeSpanString(
                    entry.modified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                )
                text = "${size(entry.size)} · $ago"
                textSize = 12f
                setTextColor(faded())
                setPadding(0, dp(6), 0, 0)
            })
        }
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1048576f)
        bytes >= 1024 -> "${bytes / 1024}KB"
        else -> "${bytes}B"
    }

    private fun message(text: String) {
        list.removeAllViews()
        list.addView(TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(faded())
            setPadding(dp(16), dp(24), dp(16), dp(16))
        })
    }

    /** 트리 주소에서 사람이 읽을 만한 폴더 이름을 뽑는다. */
    private fun folderName(tree: String): String =
        Uri.decode(tree).substringAfterLast(COLON).substringAfterLast(SLASH).ifBlank { "폴더" }

    /** 테마 색을 갈아입는다. 열려 있으면 목록까지 다시 그린다. */
    fun applyTheme(theme: ReaderTheme) {
        this.theme = theme
        repaint()
        if (visibility == View.VISIBLE && this::library.isInitialized) load()
    }

    private fun repaint() {
        setBackgroundColor(theme.ui)
        title.setTextColor(theme.onUi)
        listOf(upButton, closeButton, changeButton).forEach { it.setTextColor(theme.onUi) }
        if (!this::library.isInitialized) return
        sortButtons.forEach { (sort, view) ->
            val chosen = sort == library.fileSort
            // 보고 있는 기준에만 화살표를 달아 오름/내림을 알린다.
            view.text =
                if (chosen) "${sort.label} ${if (library.fileAscending) "↑" else "↓"}"
                else sort.label
            view.setTextColor(if (chosen) theme.onUi else faded())
            view.setBackgroundColor(if (chosen) tint() else Color.TRANSPARENT)
        }
    }

    private fun faded() =
        Color.argb(150, Color.red(theme.onUi), Color.green(theme.onUi), Color.blue(theme.onUi))

    private fun tint() =
        Color.argb(60, Color.red(theme.onUi), Color.green(theme.onUi), Color.blue(theme.onUi))

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        const val WRAP = LayoutParams.WRAP_CONTENT
        const val DOT = '.'
        const val COLON = ':'
        const val SLASH = '/'
    }
}
