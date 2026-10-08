package com.txtebook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/** 읽기 화면 색. 윈도우판과 같은 세 가지다. */
data class ReaderTheme(
    val label: String,
    val desk: Int,      // 지면 바깥 배경
    val page: Int,      // 종이
    val text: Int,      // 본문
    val ui: Int,        // 막대 배경
    val onUi: Int,      // 막대 글자
    val line: Int,      // 경계선
)

val THEMES: List<ReaderTheme> = listOf(
    ReaderTheme("밝음", 0xFFD8D8D8.toInt(), Color.WHITE, 0xFF1A1A1A.toInt(),
        0xFFF2F2F2.toInt(), 0xFF2B2B2B.toInt(), 0xFFC8C8C8.toInt()),
    ReaderTheme("세피아", 0xFFCDC0A8.toInt(), 0xFFF6ECD8.toInt(), 0xFF3B3229.toInt(),
        0xFFE6DCC6.toInt(), 0xFF3B3229.toInt(), 0xFFBFB191.toInt()),
    ReaderTheme("어둠", 0xFF141414.toInt(), 0xFF1E1E1E.toInt(), 0xFFC8C4BD.toInt(),
        0xFF242424.toInt(), 0xFFC8C4BD.toInt(), 0xFF383838.toInt()),
)

/** 글꼴. 안드로이드에 나눔명조는 없으므로 기기에 있는 계열 중에서 고른다. */
enum class FontFamily(val label: String, val family: String) {
    SERIF("명조", "serif"),
    SANS("고딕", "sans-serif"),
}

/**
 * 조판 설정. 값의 단위는 화면 밀도와 무관한 dp / sp / % 다.
 *
 * 자간만 예외적으로 dp 로 두고 화면에 넘길 때 em 으로 바꾼다. 안드로이드의
 * letterSpacing 은 글자 크기 대비 비율이라, 그대로 두면 글자 크기를 바꿀 때마다
 * 자간의 실제 폭이 같이 변해 버린다.
 */
data class Settings(
    var fontIndex: Int = FontFamily.SERIF.ordinal,
    var fontSize: Float = 18f,          // sp
    var letterSpacing: Float = 0f,      // dp  (-1.5 ~ 5.0, 0.1 단위)
    var stretch: Int = 100,             // %   (50 ~ 200)
    var lineHeight: Int = 150,          // %   (100 ~ 300)
    var paraSpacing: Int = 10,           // dp  (0 ~ 40)
    var indent: Int = 0,                // dp  (0 ~ 60)
    var marginH: Int = 28,              // dp  (0 ~ 120)
    var marginV: Int = 24,              // dp  (0 ~ 120)
    var pageMode: Int = 0,              // 0 = 화면에 맞춤, 1 = 한 쪽, 2 = 두 쪽
    var themeIndex: Int = 1,            // 세피아
    var justify: Boolean = true,
    var lineMode: LineMode = LineMode.AUTO,
) {
    val theme: ReaderTheme get() = THEMES[themeIndex.coerceIn(0, THEMES.lastIndex)]
    val font: FontFamily get() = FontFamily.entries[fontIndex.coerceIn(0, FontFamily.entries.lastIndex)]

    /** 화면 방향까지 고려한 실제 쪽 수. 가로는 두 쪽, 세로는 한 쪽이 기본이다. */
    fun pagesPerSpread(landscape: Boolean): Int =
        if (pageMode == 0) (if (landscape) 2 else 1) else pageMode

    fun save(prefs: SharedPreferences) {
        prefs.edit()
            .putInt("fontIndex", fontIndex)
            .putFloat("fontSize", fontSize)
            .putFloat("letterSpacing", letterSpacing)
            .putInt("stretch", stretch)
            .putInt("lineHeight", lineHeight)
            .putInt("paraSpacing", paraSpacing)
            .putInt("indent", indent)
            .putInt("marginH", marginH)
            .putInt("marginV", marginV)
            .putInt("pageMode", pageMode)
            .putInt("themeIndex", themeIndex)
            .putBoolean("justify", justify)
            .putString("lineMode", lineMode.name)
            .apply()
    }

    companion object {
        fun load(prefs: SharedPreferences): Settings {
            val d = Settings()
            return Settings(
                fontIndex = prefs.getInt("fontIndex", d.fontIndex),
                fontSize = prefs.getFloat("fontSize", d.fontSize),
                letterSpacing = prefs.getFloat("letterSpacing", d.letterSpacing),
                stretch = prefs.getInt("stretch", d.stretch),
                lineHeight = prefs.getInt("lineHeight", d.lineHeight),
                paraSpacing = prefs.getInt("paraSpacing", d.paraSpacing),
                indent = prefs.getInt("indent", d.indent),
                marginH = prefs.getInt("marginH", d.marginH),
                marginV = prefs.getInt("marginV", d.marginV),
                pageMode = prefs.getInt("pageMode", d.pageMode),
                themeIndex = prefs.getInt("themeIndex", d.themeIndex),
                justify = prefs.getBoolean("justify", d.justify),
                lineMode = runCatching {
                    LineMode.valueOf(prefs.getString("lineMode", d.lineMode.name)!!)
                }.getOrDefault(d.lineMode),
            )
        }
    }
}

/** 책장에 꽂힌 책 한 권. */
data class BookRecord(
    val uri: String,
    val title: String,
    /** 읽던 글자 위치. */
    val anchor: Int,
    /** 본문 글자 수. 아직 한 번도 안 열어 봤으면 0. */
    val chars: Int,
    /** 마지막으로 읽은 시각. */
    val at: Long,
) {
    /**
     * 얼마나 읽었나 (0~100). 글자 수를 모르면 null.
     *
     * 쪽수가 아니라 글자 위치로 센다. 글자 크기나 여백을 바꾸면 쪽수는 통째로
     * 달라지지만 글자 위치는 그대로다.
     */
    val percent: Int?
        get() = if (chars <= 0) null
        else (anchor.toLong() * 100 / chars).toInt().coerceIn(0, 100)
}

/** 책장 정렬 방식. 고른 값은 앱을 닫아도 남는다. */
enum class BookSort(val label: String) {
    RECENT("최근순"),
    TITLE("제목순"),
    PROGRESS("진행률순"),
}

/**
 * 고른 방식으로 책을 줄 세운다.
 *
 * 저장소와 상관없는 순수 함수라 기기 없이 시험할 수 있다.
 */
fun sortBooks(books: List<BookRecord>, sort: BookSort): List<BookRecord> = when (sort) {
    BookSort.RECENT -> books.sortedByDescending { it.at }
    // 제목은 사람이 읽는 순서로. 대소문자 차이로 갈리지 않게 한다.
    BookSort.TITLE -> books.sortedBy { it.title.lowercase() }
    // 많이 본 것부터. 한 번도 안 연 책(글자 수를 모르는 책)은 맨 아래로.
    BookSort.PROGRESS -> books.sortedByDescending { it.percent ?: -1 }
}

/**
 * 어떤 책을 어디까지 읽었는지 기억한다.
 *
 * 페이지 번호가 아니라 글자 위치로 저장한다. 글자 크기나 여백을 바꾸면 쪽수가
 * 통째로 달라지기 때문에 페이지 번호는 다시 열었을 때 의미가 없다.
 *
 * 목록은 JSON 한 줄로 넣어 둔다. 책이 수십 권이라 이만하면 충분하고, 키를
 * 흩어 두면 목록을 읽을 때마다 전부 훑어야 한다.
 */
class Library(context: Context) {

    private companion object {
        /** 책장에 남겨 둘 권수. 넘으면 오래 안 본 것부터 지운다. */
        const val KEEP = 60
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("library", Context.MODE_PRIVATE)

    var lastUri: String?
        get() = prefs.getString("lastUri", null)
        set(value) = prefs.edit().putString("lastUri", value).apply()

    var lastTitle: String?
        get() = prefs.getString("lastTitle", null)
        set(value) = prefs.edit().putString("lastTitle", value).apply()

    var sort: BookSort
        get() = runCatching { BookSort.valueOf(prefs.getString("sort", null)!!) }
            .getOrDefault(BookSort.RECENT)
        set(value) = prefs.edit().putString("sort", value.name).apply()

    /**
     * 파일 목록을 보여 줄 폴더 (SAF 트리 주소).
     *
     * 기기의 고르기 화면은 정렬을 기억하지 않고 앱이 손댈 수도 없다. 폴더를 한 번
     * 받아 두면 목록을 우리가 그리고, 줄 세우는 방식도 우리가 기억한다.
     */
    var folderUri: String?
        get() = prefs.getString("folderUri", null)
        set(value) = prefs.edit().putString("folderUri", value).apply()

    /** 그 폴더에서 마지막으로 들어가 있던 자리 (문서 번호). */
    var folderAt: String?
        get() = prefs.getString("folderAt", null)
        set(value) = prefs.edit().putString("folderAt", value).apply()

    var fileSort: FileSort
        get() = runCatching { FileSort.valueOf(prefs.getString("fileSort", null)!!) }
            .getOrDefault(FileSort.NAME)
        set(value) = prefs.edit().putString("fileSort", value.name).apply()

    /** 오름차순인가. 이름은 ㄱ→ㅎ, 수정시간은 오래된 것부터. */
    var fileAscending: Boolean
        get() = prefs.getBoolean("fileAscending", true)
        set(value) = prefs.edit().putBoolean("fileAscending", value).apply()

    /** 책장에 있는 책들. 꽂힌 순서 그대로다. */
    fun books(): List<BookRecord> {
        val raw = prefs.getString("books", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                BookRecord(
                    uri = item.getString("uri"),
                    title = item.optString("title"),
                    anchor = item.optInt("anchor"),
                    chars = item.optInt("chars"),
                    at = item.optLong("at"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 고른 방식으로 줄 세운 책들. */
    fun sortedBooks(): List<BookRecord> = sortBooks(books(), sort)

    fun positionOf(uri: String): Int = books().firstOrNull { it.uri == uri }?.anchor ?: 0

    /** 읽던 자리를 적어 둔다. @param chars 본문 글자 수 (모르면 0). */
    fun remember(uri: String, title: String, charOffset: Int, chars: Int = 0) {
        val kept = books().filter { it.uri != uri }
        val previous = books().firstOrNull { it.uri == uri }
        val record = BookRecord(
            uri = uri,
            title = title,
            anchor = charOffset,
            // 글자 수를 모르는 채로 부르면 지난번에 알아 둔 값을 지킨다.
            chars = if (chars > 0) chars else previous?.chars ?: 0,
            at = System.currentTimeMillis(),
        )
        save(listOf(record) + kept)
        prefs.edit().putString("lastUri", uri).putString("lastTitle", title).apply()
    }

    /** 파일이 사라져 더 못 여는 책은 기록에서 지운다. */
    fun forget(uri: String) {
        save(books().filter { it.uri != uri })
        if (lastUri == uri) prefs.edit().remove("lastUri").remove("lastTitle").apply()
    }

    private fun save(books: List<BookRecord>) {
        val array = JSONArray()
        books.sortedByDescending { it.at }.take(KEEP).forEach { book ->
            array.put(
                JSONObject()
                    .put("uri", book.uri)
                    .put("title", book.title)
                    .put("anchor", book.anchor)
                    .put("chars", book.chars)
                    .put("at", book.at)
            )
        }
        prefs.edit().putString("books", array.toString()).apply()
    }
}
