package com.txtebook

/** 폴더 목록의 한 줄. 주소가 아니라 문서 번호만 들고 있어 기기 없이도 시험할 수 있다. */
data class FolderEntry(
    val docId: String,
    val name: String,
    val isFolder: Boolean,
    val size: Long,
    val modified: Long,
)

/** 파일 목록을 무엇으로 줄 세울지. */
enum class FileSort(val label: String) {
    NAME("이름"),
    MODIFIED("수정시간"),
    SIZE("크기"),
}

/**
 * 폴더 목록을 줄 세운다.
 *
 * 폴더는 오르내림과 상관없이 언제나 위에 모아 둔다. 내림차순이라고 폴더가 파일 뒤로
 * 가 버리면 폴더를 찾으려고 끝까지 훑어야 한다.
 */
fun sortEntries(
    entries: List<FolderEntry>,
    sort: FileSort,
    ascending: Boolean,
): List<FolderEntry> {
    val byField = when (sort) {
        FileSort.NAME -> Comparator<FolderEntry> { a, b -> compareNatural(a.name, b.name) }
        FileSort.MODIFIED -> Comparator { a, b -> a.modified.compareTo(b.modified) }
        FileSort.SIZE -> Comparator { a, b -> a.size.compareTo(b.size) }
    }
    val directed = if (ascending) byField else byField.reversed()
    // 값이 같으면 이름으로 가른다. 수정 시간이 같은 파일들이 열 때마다 뒤바뀌지 않게.
    val stable = directed.thenComparator { a, b -> compareNatural(a.name, b.name) }
    return entries.sortedWith(compareBy<FolderEntry> { !it.isFolder }.thenComparing(stable))
}
