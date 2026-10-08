package com.txtebook

/**
 * 사람이 세는 순서로 이름을 견준다.
 *
 * 글자 그대로 견주면 `2권` 이 `10권` 보다 뒤로 간다 ('2' > '1'). 연재물은 파일 이름에
 * 번호가 들어가는 일이 많아서, 숫자는 숫자끼리 값으로 견준다.
 *
 * 대소문자는 가리지 않는다. 같은 이름이면 원래 글자로 한 번 더 견줘서 순서가
 * 들쭉날쭉하지 않게 한다.
 */
fun compareNatural(left: String, right: String): Int {
    var i = 0
    var j = 0
    while (i < left.length && j < right.length) {
        val a = left[i]
        val b = right[j]
        if (a.isDigit() && b.isDigit()) {
            val endA = digitEnd(left, i)
            val endB = digitEnd(right, j)
            val diff = compareNumbers(left.substring(i, endA), right.substring(j, endB))
            if (diff != 0) return diff
            i = endA
            j = endB
            continue
        }
        val diff = a.lowercaseChar().compareTo(b.lowercaseChar())
        if (diff != 0) return diff
        i++
        j++
    }
    val rest = (left.length - i).compareTo(right.length - j)
    // 여기까지 같으면 대소문자만 다른 이름이다. 순서가 흔들리지 않게 그대로 견준다.
    return if (rest != 0) rest else left.compareTo(right)
}

private fun digitEnd(text: String, from: Int): Int {
    var at = from
    while (at < text.length && text[at].isDigit()) at++
    return at
}

/** 자릿수가 다르면 긴 쪽이 크다. 앞의 0 은 값에 넣지 않는다 (`007` == `7`). */
private fun compareNumbers(left: String, right: String): Int {
    val a = left.trimStart('0')
    val b = right.trimStart('0')
    if (a.length != b.length) return a.length - b.length
    return a.compareTo(b)
}
