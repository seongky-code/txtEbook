package com.txtebook

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.concurrent.thread

/** 막대가 저 혼자 사라질 때까지. */
private const val CHROME_TIMEOUT = 3000L

class MainActivity : AppCompatActivity() {

    private lateinit var reader: ReaderView
    private lateinit var panel: SettingsPanel
    private lateinit var shelf: Bookshelf
    private lateinit var browser: FolderBrowser
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleLabel: TextView
    private lateinit var pageBar: SeekBar
    private lateinit var pageLabel: TextView
    private lateinit var openButton: TextView
    private lateinit var panelButton: TextView

    private lateinit var settings: Settings
    private lateinit var library: Library

    private var currentUri: String? = null
    private var currentTitle: String = ""
    private var syncingBar = false

    private val ui = Handler(Looper.getMainLooper())

    /**
     * 슬라이더를 끌면 값이 쉬지 않고 들어온다. 두꺼운 책은 재조판이 무거우므로
     * 잠깐 멈출 때까지 모아서 한 번만 다시 짠다.
     */
    private var pendingTextRebuild = false
    private val applySettings = Runnable {
        reader.applySettings(pendingTextRebuild)
        pendingTextRebuild = false
        settings.save(prefs())
        paintChrome()
    }

    /**
     * 기기의 문서 고르기 화면.
     *
     * 형식을 하나만 넘긴다. 여럿을 넘기면 고르기 화면 위에 `형식` 고르는 칸이
     * 하나 더 붙는데, 우리가 여는 것은 txt 뿐이라 쓸데가 없다. 그 하나로 모든
     * 형식(와일드카드)을 넘기는 이유는, txt 파일에 엉뚱한 형식
     * (application/octet-stream 따위)이 붙어 오는 기기가 있어서다.
     * `text/plain` 으로 좁히면 그런 파일이 아예 안 보인다.
     */
    private val pickBook = object : ActivityResultContracts.OpenDocument() {
        /** 지난번에 연 파일. 고르기 화면이 그 폴더에서 열리게 한다. */
        var startAt: Uri? = null

        override fun createIntent(context: Context, input: Array<String>): Intent =
            super.createIntent(context, input).apply {
                startAt?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
    }

    private val picker = registerForActivityResult(pickBook) { uri ->
        uri?.let {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            openBook(it)
        }
    }

    /** 기기에서 파일을 고른다. */
    private fun browseFiles() {
        pickBook.startAt = library.lastUri?.let(Uri::parse)
        picker.launch(arrayOf("*/*"))
    }

    /**
     * 책이 담긴 폴더를 통째로 받아 둔다.
     *
     * 폴더 권한을 한 번 받아 두면 목록을 앱이 그릴 수 있다. 기기의 고르기 화면은
     * 정렬을 기억하지 않는데, 그 자리는 앱이 손댈 수 없기 때문이다.
     */
    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        it, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                library.folderUri = it.toString()
                browser.reset()
                openFolder()
            }
        }

    private fun prefs() = getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun dp(value: Number) = (value.toFloat() * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ 화면 만들기

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings.load(prefs())
        library = Library(this)

        val root = FrameLayout(this)

        // 지면이 화면 전체를 쓰고, 막대는 그 위에 겹친다. 막대가 레이아웃 자리를
        // 차지하면 여닫을 때마다 지면 높이가 달라져 재조판이 일어나고 읽던 쪽이 바뀐다.
        reader = ReaderView(this).apply {
            settings = this@MainActivity.settings
            keepScreenOn = true
            // 조판은 레이아웃 도중에 끝나기도 한다. 그 안에서 라벨 글자를 바꾸면
            // requestLayout 이 무시돼 라벨 폭이 0 인 채로 남는다. 프레임 밖으로 미룬다.
            onPageChanged = { page, count -> ui.post { syncPageBar(page, count) } }
            onChromeToggle = { toggleChrome() }
        }
        root.addView(reader, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        topBar = buildTopBar()
        root.addView(topBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.TOP))

        bottomBar = buildBottomBar()
        root.addView(bottomBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.BOTTOM))

        // 설정 패널은 지면 위에 겹친다 (레이아웃 자리를 차지하지 않는다).
        panel = SettingsPanel(this).apply {
            visibility = View.GONE
            onChanged = { rebuildText ->
                pendingTextRebuild = pendingTextRebuild || rebuildText
                ui.removeCallbacks(applySettings)
                ui.postDelayed(applySettings, 180)
            }
        }
        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panel.build(settings)

        // 책장은 지면과 막대를 다 덮는다. 목록에서 고르는 동안에는 읽던 화면이
        // 보일 필요가 없다.
        shelf = Bookshelf(this).apply {
            visibility = View.GONE
            onPick = { book -> hide(); openBook(Uri.parse(book.uri)) }
            onBrowse = { browseFiles() }
            onFolder = { openFolder() }
            onClose = { closeShelf() }
        }
        root.addView(shelf, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        browser = FolderBrowser(this).apply {
            visibility = View.GONE
            onPick = { uri, _ -> hide(); openBook(uri) }
            onChangeFolder = { reset(); folderPicker.launch(null) }
            onClose = { closeBrowser() }
        }
        root.addView(browser, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 화면 끝까지 우리가 그린다고 못박는다. 이 한 줄이 없으면 안드로이드 14 이하에서는
        // 시스템이 상태 표시줄·제스처 바 자리만큼 콘텐츠 뷰에 패딩을 넣는다. 그러면 막대를
        // 여닫을 때마다 지면 높이가 달라져 책 전체가 다시 조판된다 (3.2MB 책에서 5.7초).
        // 안드로이드 15 부터는 targetSdk 35+ 앱에 이것이 기본이라 증상이 안 보인다.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(root)
        paintChrome()
        applySystemBarInsets(root)

        root.viewTreeObserver.addOnGlobalLayoutListener { placePanel() }

        onBackPressedDispatcher.addCallback(this, object :
            androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    browser.visibility == View.VISIBLE -> closeBrowser()
                    shelf.visibility == View.VISIBLE -> closeShelf()
                    panel.visibility == View.VISIBLE -> togglePanel()
                    chromeShown -> showChrome(false)
                    else -> finish()
                }
            }
        })

        // 파일 관리자에서 열었으면 그 파일을, 아니면 마지막에 읽던 책을 이어서.
        val fromIntent = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data
        val resume = library.lastUri?.let(Uri::parse)
        (fromIntent ?: resume)?.let { openBook(it) }
    }

    private fun barButton(text: String, action: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        minWidth = dp(64)
        minHeight = dp(48)
        setPadding(dp(14), 0, dp(14), 0)
        isClickable = true
        setOnClickListener { keepChromeShown(); action() }
    }

    private fun buildTopBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        openButton = barButton("열기") { openShelf() }
        addView(openButton)
        titleLabel = TextView(this@MainActivity).apply {
            textSize = 15f
            maxLines = 1
            setPadding(dp(8), 0, dp(8), 0)
        }
        addView(titleLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panelButton = barButton("설정") { togglePanel() }
        addView(panelButton)
    }

    private fun buildBottomBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), 0)

        pageBar = SeekBar(this@MainActivity).apply {
            max = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser && !syncingBar) reader.goToPage(value)
                }
                // 먼 곳으로 옮기는 동안에는 막대가 사라지면 안 된다.
                override fun onStartTrackingTouch(bar: SeekBar) = ui.removeCallbacks(autoHideChrome)
                override fun onStopTrackingTouch(bar: SeekBar) {
                    rememberPosition()
                    keepChromeShown()
                }
            })
        }
        addView(pageBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        pageLabel = TextView(this@MainActivity).apply {
            textSize = 13f
            setPadding(dp(12), 0, 0, 0)
        }
        addView(pageLabel)
    }

    /**
     * 상태 표시줄·제스처 바 자리를 비켜 준다.
     *
     * targetSdk 35 부터는 앱이 화면 끝까지 그리므로, 직접 여백을 주지 않으면
     * 위쪽 막대가 시계와 겹치고 아래쪽 이동 바가 제스처 바에 가린다.
     */
    private fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.setPadding(bars.left, bars.top, bars.right, 0)
            topBar.layoutParams = topBar.layoutParams.apply { height = dp(52) + bars.top }
            // 책장은 화면을 다 덮으므로 시계·제스처 바 자리를 직접 비켜 준다.
            shelf.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            browser.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            bottomBar.setPadding(dp(14) + bars.left, 0, dp(14) + bars.right, bars.bottom)
            bottomBar.layoutParams = bottomBar.layoutParams.apply { height = dp(56) + bars.bottom }
            insets
        }
    }

    // ------------------------------------------------------------ 막대 여닫기

    /**
     * 위·아래 막대와 시스템 막대를 함께 여닫는다.
     *
     * 읽는 동안에는 지면만 남기고 다 치운다. 지면 가운데를 누르면 잠깐 나왔다가
     * 스스로 사라진다. 막대는 오버레이라서 여닫아도 지면 크기는 그대로다.
     */
    private var chromeShown = true

    private val autoHideChrome = Runnable {
        // 설정 패널이 열려 있으면 막대가 사라지면 안 된다. 패널이 막대에 기대어
        // 자리를 잡기 때문이다 (placePanel).
        if (panel.visibility != View.VISIBLE) showChrome(false)
    }

    /**
     * 지면 가운데를 눌렀을 때.
     *
     * 떠 있는 것이 하나라도 있으면 설정 패널까지 다 접는다. 지면만 남아 있을 때
     * 눌러야 막대가 나온다. 읽던 자리로 돌아가는 길이 늘 같은 자리라 헷갈리지 않는다.
     */
    private fun toggleChrome() {
        val anythingShown = chromeShown || panel.visibility == View.VISIBLE
        if (panel.visibility == View.VISIBLE) togglePanel()
        showChrome(!anythingShown)
    }

    private fun showChrome(show: Boolean) {
        ui.removeCallbacks(autoHideChrome)
        if (show) {
            // 책이 없으면 열기 버튼이 유일한 출구다. 스스로 사라지지 않게 둔다.
            if (reader.hasBook) ui.postDelayed(autoHideChrome, CHROME_TIMEOUT)
        }
        if (chromeShown == show) return
        chromeShown = show
        fade(topBar, show)
        fade(bottomBar, show)
        showSystemBars(show)
    }

    /** 막대를 만지는 동안에는 사라지지 않게 시계를 되감는다. */
    private fun keepChromeShown() {
        if (chromeShown) showChrome(true)
    }

    private fun fade(bar: View, show: Boolean) {
        bar.animate().cancel()
        if (show) {
            bar.alpha = 0f
            bar.visibility = View.VISIBLE
            bar.animate().alpha(1f).setDuration(120).start()
        } else {
            bar.animate().alpha(0f).setDuration(160)
                .withEndAction { bar.visibility = View.GONE }.start()
        }
    }

    private fun showSystemBars(show: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // 숨은 동안 가장자리를 쓸면 잠깐 나왔다 사라지게 한다. 그래야 뒤로 가기나
        // 홈으로 나갈 길이 막히지 않는다.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val bars = WindowInsetsCompat.Type.systemBars()
        if (show) controller.show(bars) else controller.hide(bars)
    }

    /** 패널을 가로에서는 오른쪽에, 세로에서는 아래쪽에 붙인다. */
    private fun placePanel() {
        if (panel.visibility != View.VISIBLE) return
        val landscape = reader.width > reader.height
        val params = panel.layoutParams as FrameLayout.LayoutParams
        // 지면은 이제 화면 전체다. 패널은 두 막대 사이에 들어가야 하므로
        // 막대 높이를 빼고 잡는다 (패널은 막대가 떠 있을 때만 열린다).
        val between = reader.height - topBar.height - bottomBar.height
        if (landscape) {
            params.width = minOf(dp(320), (reader.width * 0.42f).toInt())
            params.height = between
            params.gravity = Gravity.END or Gravity.TOP
            params.topMargin = topBar.height
        } else {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = (between * 0.62f).toInt()
            params.gravity = Gravity.BOTTOM
            params.bottomMargin = bottomBar.height
            params.topMargin = 0
        }
        panel.layoutParams = params
    }

    private fun togglePanel() {
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (panel.visibility == View.VISIBLE) {
            // 패널이 열려 있는 동안에는 막대를 붙잡아 둔다. 패널이 막대에 기대어
            // 자리를 잡고, 닫으려면 설정 버튼이 있어야 한다.
            ui.removeCallbacks(autoHideChrome)
            panel.repaint()
            panel.bringToFront()
            placePanel()
        } else {
            keepChromeShown()
        }
    }

    /** 테마 색을 막대들에 입힌다. */
    private fun paintChrome() {
        val theme = settings.theme
        topBar.setBackgroundColor(theme.ui)
        bottomBar.setBackgroundColor(theme.ui)
        listOf(titleLabel, pageLabel, openButton, panelButton).forEach { it.setTextColor(theme.onUi) }
        window.decorView.setBackgroundColor(theme.desk)
        panel.setBackgroundColor(theme.ui)
        shelf.applyTheme(theme)
        browser.applyTheme(theme)
        reader.invalidate()
    }

    // ------------------------------------------------------------ 책 열기

    private fun openBook(uri: Uri) {
        // 파일 관리자에서 넘어온 책도 다음에 이어 읽을 수 있게 권한을 붙잡아 둔다.
        // 붙잡을 수 없는 주소면 조용히 넘어가고, 다음 실행 때 안내한다.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        titleLabel.text = "여는 중…"
        thread {
            val result = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("파일을 읽을 수 없습니다")
                readTextFile(bytes)
            }
            ui.post {
                result.onSuccess { text ->
                    shelf.hide()
                    browser.hide()
                    currentUri = uri.toString()
                    currentTitle = displayName(uri)
                    titleLabel.text = currentTitle
                    reader.setBook(text, library.positionOf(currentUri!!), currentUri!!)
                    rememberPosition()
                    // 책이 들어왔으니 이제 막대는 스스로 물러난다.
                    showChrome(true)
                }.onFailure {
                    titleLabel.text = ""
                    library.forget(uri.toString())
                    reader.clear()
                    Toast.makeText(
                        this, "이 파일을 열 수 없습니다. 다시 골라 주세요.", Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        return cursor.getString(0).substringBeforeLast('.')
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "제목 없음"
    }

    // ------------------------------------------------------------ 위치 기억

    private fun syncPageBar(page: Int, count: Int) {
        syncingBar = true
        val spread = reader.spread
        pageBar.max = maxOf(0, count - spread)
        pageBar.progress = page.coerceIn(0, pageBar.max)
        syncingBar = false

        val last = minOf(page + spread, count)
        val span = if (last == page + 1) "${page + 1}" else "${page + 1}–$last"
        val percent = if (count > 0) (last * 100.0 / count).toInt() else 0
        pageLabel.text = "$span / $count 쪽 · $percent%"
        rememberPosition()
    }

    private fun rememberPosition() {
        val uri = currentUri ?: return
        if (!reader.hasBook) return
        // 글자 수를 함께 적어 둔다. 책장에서 몇 퍼센트 읽었는지 이 둘로 센다.
        library.remember(uri, currentTitle, reader.anchor, reader.bodyLength)
    }

    // ------------------------------------------------------------ 책장

    /** 읽던 책 목록을 연다. */
    private fun openShelf() {
        if (panel.visibility == View.VISIBLE) togglePanel()
        ui.removeCallbacks(autoHideChrome)
        shelf.show(library, settings.theme)
    }

    private fun closeShelf() {
        shelf.hide()
        keepChromeShown()
    }

    /** 폴더 목록을 연다. 아직 폴더를 안 정했으면 먼저 고르게 한다. */
    private fun openFolder() {
        if (library.folderUri == null) {
            folderPicker.launch(null)
            return
        }
        shelf.hide()
        ui.removeCallbacks(autoHideChrome)
        browser.show(library, settings.theme)
    }

    private fun closeBrowser() {
        browser.hide()
        keepChromeShown()
    }

    override fun onPause() {
        super.onPause()
        rememberPosition()
        settings.save(prefs())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { openBook(it) }
    }
}
