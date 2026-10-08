package com.txtebook

import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.system.measureTimeMillis

/** 쪽 표 저장소. 잘못된 표를 꺼내 쓰지 않는 것이 핵심이다. */
@RunWith(AndroidJUnit4::class)
class PageCacheTest {

    private val cache = PageCache(ApplicationProvider.getApplicationContext())

    private fun key(name: String) = "test:$name:${System.nanoTime()}"

    @Test
    fun 적어_둔_쪽_표를_그대로_꺼낸다() {
        val key = key("book")
        val pages = IntArray(5_000) { it * 137 }
        cache.save(key, pages)
        assertArrayEquals("꺼낸 표가 다르다", pages, cache.load(key))
    }

    @Test
    fun 설정이_다르면_없는_것으로_친다() {
        val key = key("book")
        cache.save(key, intArrayOf(0, 90, 300))
        assertNull("다른 이름표로 꺼내졌다", cache.load(key + "-글자크기다름"))
    }

    @Test
    fun 적어_둔_적_없으면_null() {
        assertNull(cache.load(key("없는책")))
    }

    @Test
    fun 쪽_표를_다시_쓰면_훨씬_빨리_열린다() {
        // 실제로 문제가 됐던 9MB 책과 비슷한 규모 (400만 자).
        val sentence = "그날 아침의 공기는 유난히 투명했다. 창밖으로 번져 오는 빛이 방바닥에 " +
            "네모난 자리를 만들고, 그 안에서 먼지들이 천천히 떠올랐다 가라앉았다."
        val big = StringBuilder()
        var chapter = 0
        while (big.length < 3_800_000) {
            big.append("제 ${++chapter} 장\n")
            repeat(12) { big.append(sentence).append('\n') }
        }
        val text = big.toString()
        val paint = {
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("serif", Typeface.NORMAL)
                textSize = 36f
            }
        }
        val name = key("성능")

        val fresh = Paginator(text, paint(), 1210, 1180, true)
        val counting = measureTimeMillis {
            @Suppress("ControlFlowWithEmptyBody")
            while (fresh.paginateNext());
        }
        cache.save(name, fresh.pages)

        // 다음에 열 때 하는 일: 쪽 표를 읽고, 첫 쪽만 그릴 준비를 한다.
        var reopened: Paginator? = null
        val reusing = measureTimeMillis {
            val saved = cache.load(name) ?: error("적어 둔 쪽 표가 없다")
            reopened = Paginator(text, paint(), 1210, 1180, true).also {
                assertTrue("쪽 표를 받아들이지 않았다", it.restore(saved))
                it.pageContentHeight(0)      // 첫 쪽을 그릴 수 있을 만큼 준비
            }
        }

        Log.i(
            "txtEbookTest",
            "${text.length / 1000}k 자 ${fresh.pageCount}쪽: 처음 세기 ${counting}ms -> " +
                "쪽 표 다시 쓰기 ${reusing}ms",
        )
        assertEquals("쪽수가 달라졌다", fresh.pageCount, reopened!!.pageCount)
        assertTrue(
            "쪽 표를 다시 써도 빨라지지 않았다 (${counting}ms -> ${reusing}ms)",
            reusing < counting / 2,
        )
    }

    @Test
    fun 쌓이면_오래된_것부터_버리되_방금_적은_것은_남긴다() {
        val keys = (0 until 12).map { key("많은책$it") }
        keys.forEach { cache.save(it, intArrayOf(0, 90, 300)) }
        // 파일 시각이 초 단위인 기기에서도 방금 적은 것은 살아 있어야 한다.
        assertTrue("방금 적은 쪽 표가 사라졌다", cache.load(keys.last()) != null)
        // 12권을 적었어도 저장 칸은 자란 만큼 도로 줄어야 한다.
        val kept = keys.count { cache.load(it) != null }
        assertTrue("$kept 권이 남았다 - 버려지지 않는다", kept <= 8)
    }
}
