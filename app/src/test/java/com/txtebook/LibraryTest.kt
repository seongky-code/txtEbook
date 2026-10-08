package com.txtebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 책장 줄 세우기와 진행률. 저장소를 타지 않는 부분이라 기기 없이 돈다. */
class LibraryTest {

    private fun book(
        title: String,
        anchor: Int = 0,
        chars: Int = 0,
        at: Long = 0,
    ) = BookRecord("content://$title", title, anchor, chars, at)

    @Test
    fun 진행률은_글자_위치로_센다() {
        assertEquals(25, book("가", anchor = 250, chars = 1000).percent)
        assertEquals(0, book("나", anchor = 0, chars = 1000).percent)
        assertEquals(100, book("다", anchor = 1000, chars = 1000).percent)
    }

    @Test
    fun 글자_수를_모르면_진행률도_없다() {
        assertNull(book("가", anchor = 300).percent)
    }

    @Test
    fun 진행률은_0에서_100_사이로_묶인다() {
        // 줄바꿈 방식을 바꾸면 본문 길이가 달라져 앵커가 잠깐 넘칠 수 있다.
        assertEquals(100, book("가", anchor = 5000, chars = 1000).percent)
        assertEquals(0, book("나", anchor = -10, chars = 1000).percent)
    }

    @Test
    fun 최근순은_마지막으로_읽은_때부터() {
        val books = listOf(book("가", at = 100), book("나", at = 300), book("다", at = 200))
        assertEquals(
            listOf("나", "다", "가"),
            sortBooks(books, BookSort.RECENT).map { it.title },
        )
    }

    @Test
    fun 제목순은_대소문자를_가리지_않는다() {
        val books = listOf(book("banana"), book("Apple"), book("cherry"))
        assertEquals(
            listOf("Apple", "banana", "cherry"),
            sortBooks(books, BookSort.TITLE).map { it.title },
        )
    }

    @Test
    fun 진행률순은_많이_본_것부터이고_안_연_책은_맨_아래() {
        val books = listOf(
            book("조금", anchor = 10, chars = 100),
            book("모름"),
            book("많이", anchor = 90, chars = 100),
        )
        assertEquals(
            listOf("많이", "조금", "모름"),
            sortBooks(books, BookSort.PROGRESS).map { it.title },
        )
    }

    @Test
    fun 줄_세워도_권수는_그대로다() {
        val books = listOf(book("가", at = 1), book("나", at = 2), book("다", at = 3))
        BookSort.values().forEach { sort ->
            assertEquals("$sort 에서 책이 사라졌다", 3, sortBooks(books, sort).size)
        }
    }
}
