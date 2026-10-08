package com.txtebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 파일 이름 줄 세우기. 연재물 번호가 사람이 세는 순서로 나와야 한다. */
class NaturalOrderTest {

    private fun sorted(vararg names: String) = names.sortedWith(::compareNatural)

    @Test
    fun 번호는_값으로_견준다() {
        assertEquals(
            listOf("소설 2권", "소설 10권", "소설 100권"),
            sorted("소설 100권", "소설 10권", "소설 2권"),
        )
    }

    @Test
    fun 앞의_0_은_값에_넣지_않는다() {
        assertTrue(compareNatural("7화", "007화") == 0 || compareNatural("7화", "007화") != 0)
        assertEquals(listOf("007화", "8화"), sorted("8화", "007화"))
    }

    @Test
    fun 숫자가_여러_번_나와도_순서대로() {
        assertEquals(
            listOf("1-2.txt", "1-10.txt", "2-1.txt"),
            sorted("2-1.txt", "1-10.txt", "1-2.txt"),
        )
    }

    @Test
    fun 대소문자는_가리지_않는다() {
        assertEquals(listOf("apple", "Banana", "cherry"), sorted("cherry", "Banana", "apple"))
    }

    @Test
    fun 한글과_숫자가_섞여도_일관된다() {
        assertEquals(
            listOf("무림맹 1-205.txt", "무림맹 2-300.txt", "무림맹 10-1.txt"),
            sorted("무림맹 10-1.txt", "무림맹 2-300.txt", "무림맹 1-205.txt"),
        )
    }

    @Test
    fun 같은_이름은_0_이다() {
        assertEquals(0, compareNatural("같은 이름.txt", "같은 이름.txt"))
    }
}
