package com.txtebook

import org.junit.Assert.assertEquals
import org.junit.Test

/** 폴더 목록 줄 세우기. 기기의 고르기 화면과 달리 이 순서는 저장된다. */
class FolderTest {

    private fun file(name: String, size: Long = 0, modified: Long = 0) =
        FolderEntry(name, name, isFolder = false, size = size, modified = modified)

    private fun folder(name: String) =
        FolderEntry(name, name, isFolder = true, size = 0, modified = 0)

    @Test
    fun 이름_오름차순은_사람이_세는_순서다() {
        val list = listOf(file("책 10권.txt"), file("책 2권.txt"), file("책 1권.txt"))
        assertEquals(
            listOf("책 1권.txt", "책 2권.txt", "책 10권.txt"),
            sortEntries(list, FileSort.NAME, ascending = true).map { it.name },
        )
    }

    @Test
    fun 이름_내림차순은_뒤집힌다() {
        val list = listOf(file("가.txt"), file("나.txt"), file("다.txt"))
        assertEquals(
            listOf("다.txt", "나.txt", "가.txt"),
            sortEntries(list, FileSort.NAME, ascending = false).map { it.name },
        )
    }

    @Test
    fun 수정시간_내림차순은_최근_것부터다() {
        val list = listOf(
            file("오래된.txt", modified = 100),
            file("최근.txt", modified = 300),
            file("중간.txt", modified = 200),
        )
        assertEquals(
            listOf("최근.txt", "중간.txt", "오래된.txt"),
            sortEntries(list, FileSort.MODIFIED, ascending = false).map { it.name },
        )
    }

    @Test
    fun 크기순도_된다() {
        val list = listOf(file("작은.txt", size = 10), file("큰.txt", size = 9_000_000))
        assertEquals(
            listOf("큰.txt", "작은.txt"),
            sortEntries(list, FileSort.SIZE, ascending = false).map { it.name },
        )
    }

    @Test
    fun 폴더는_내림차순에서도_위에_모인다() {
        val list = listOf(file("가.txt"), folder("하위폴더"), file("다.txt"))
        assertEquals(
            listOf("하위폴더", "다.txt", "가.txt"),
            sortEntries(list, FileSort.NAME, ascending = false).map { it.name },
        )
    }

    @Test
    fun 수정시간이_같으면_이름으로_가른다() {
        val list = listOf(
            file("나.txt", modified = 500),
            file("가.txt", modified = 500),
            file("다.txt", modified = 500),
        )
        assertEquals(
            listOf("가.txt", "나.txt", "다.txt"),
            sortEntries(list, FileSort.MODIFIED, ascending = true).map { it.name },
        )
    }
}
