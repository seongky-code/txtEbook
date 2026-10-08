package com.txtebook

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * 지난번에 세어 둔 쪽 표를 적어 둔다.
 *
 * 조판은 글자 수에 비례해서 무겁다 (9MB 책 5.8초). 그런데 본문과 조판 설정이 같으면
 * 결과는 언제나 같다. 한 번 센 것을 적어 두면 다음에 열 때는 읽어 오기만 하면 된다.
 * 쪽 표는 쪽마다 정수 하나라, 7,299쪽짜리 책도 29KB 뿐이다.
 *
 * 이름표(key)에는 본문과 조판에 영향을 주는 값이 모두 들어간다. 글자 크기든 여백이든
 * 하나만 달라져도 다른 이름표가 되므로, 낡은 쪽 표를 잘못 꺼내 쓸 일은 없다.
 */
class PageCache(context: Context) {

    private companion object {
        /** 파일 형식이 바뀌면 이 값을 올린다. 옛 파일은 이름표 검사 전에 걸러진다. */
        const val MAGIC = 0x54455031        // "TEP1"

        /** 몇 권분까지 들고 있을지. 한 권이 조판 설정마다 한 칸씩 쓴다. */
        const val KEEP = 8
    }

    private val dir = File(context.filesDir, "pages")

    /** 그 이름표로 적어 둔 쪽 표. 없거나 깨졌으면 null. */
    fun load(key: String): IntArray? {
        val file = fileOf(key)
        val pages = runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC) return null
                // 이름표를 통째로 적어 두고 여기서 맞춰 본다. 파일 이름은 해시라서
                // 다른 책이 같은 이름을 쓸 수 있다.
                if (input.readUTF() != key) return null
                val count = input.readInt()
                // 깨진 파일이 터무니없는 크기를 부르면 여기서 막는다.
                if (count <= 0 || count > 50_000_000) return null
                IntArray(count) { input.readInt() }
            }
        }.getOrNull() ?: return null
        // 가장 최근에 쓴 것부터 남긴다.
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return pages
    }

    /** 쪽 표를 적어 둔다. 실패해도 조용히 넘어간다 (다음에 다시 세면 된다). */
    fun save(key: String, pages: IntArray) {
        if (pages.isEmpty()) return
        runCatching {
            dir.mkdirs()
            // 쓰다 만 파일을 다음 실행 때 읽지 않도록, 다 쓰고 나서 제자리로 옮긴다.
            val temp = File(dir, "tmp-${System.nanoTime()}")
            DataOutputStream(temp.outputStream().buffered()).use { out ->
                out.writeInt(MAGIC)
                out.writeUTF(key)
                out.writeInt(pages.size)
                for (page in pages) out.writeInt(page)
            }
            val target = fileOf(key)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) temp.delete()
            }
            trim(target)
        }
    }

    /**
     * 오래된 것부터 버린다. 방금 적은 것은 남긴다.
     *
     * 파일 시각은 기기에 따라 초 단위라서, 잇따라 적으면 어느 것이 최근인지 가려지지
     * 않는다. 그 틈에 방금 센 표가 지워지면 다음에 열 때 또 10초를 세게 된다.
     */
    private fun trim(keep: File) {
        val others = dir.listFiles()?.filter { it.absolutePath != keep.absolutePath } ?: return
        if (others.size < KEEP) return
        others.sortedByDescending { it.lastModified() }
            .drop(KEEP - 1)
            .forEach { runCatching { it.delete() } }
    }

    private fun fileOf(key: String) = File(dir, "%016x.pages".format(hash(key)))

    /** FNV-1a. 파일 이름을 짧게 만들기 위한 것이라 충돌은 이름표로 걸러 낸다. */
    private fun hash(key: String): Long {
        var value = -3750763034362895579L    // FNV offset basis
        for (ch in key) {
            value = value xor ch.code.toLong()
            value *= 1099511628211L          // FNV prime
        }
        return value
    }
}
