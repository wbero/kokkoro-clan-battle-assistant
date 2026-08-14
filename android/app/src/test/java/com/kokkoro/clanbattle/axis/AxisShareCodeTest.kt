package com.kokkoro.clanbattle.axis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisShareCodeTest {
    private val axis = """
        轴类型=顺序
        轴名称=分享测试
        点击间隔=100
        角色1=日和
        角色2=优衣
        角色3=怜
        角色4=珠希
        角色5=真步

        [轴]
        1:10 | AUTO=开
        1:09 | UB后=角色1 | 点击=AUTO
    """.trimIndent()

    @Test fun roundTripPreservesNormalizedAxisText() {
        val code = AxisShareCode.encode(axis.replace("\n", "\r\n"))

        assertTrue(code.startsWith("KCA1."))
        assertEquals(axis, AxisShareCode.decode(code))
    }

    @Test(expected = IllegalArgumentException::class)
    fun damagedCodeIsRejected() {
        val code = AxisShareCode.encode(axis)
        val replacement = if (code.last() == 'A') 'B' else 'A'

        AxisShareCode.decode(code.dropLast(1) + replacement)
    }

    @Test fun whitespaceAroundOrInsideCodeIsIgnored() {
        val code = AxisShareCode.encode(axis)
        val wrapped = code.chunked(40).joinToString("\n")

        assertEquals(axis, AxisShareCode.decode("  $wrapped  "))
    }
}
