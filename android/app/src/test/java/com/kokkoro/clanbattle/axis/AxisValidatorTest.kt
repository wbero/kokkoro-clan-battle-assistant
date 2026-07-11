package com.kokkoro.clanbattle.axis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisValidatorTest {
    @Test
    fun `rejects a document without an axis section`() {
        val document = AxisParser.parse(
            """
            轴类型=顺序
            点击间隔=100
            """.trimIndent()
        )

        val result = AxisValidator.validate(document)

        assertTrue(result.issues.any { it.code == "missing-axis-section" })
    }

    @Test
    fun `rejects unknown axis type and invalid click interval`() {
        val document = AxisParser.parse(
            """
            轴类型=未知
            点击间隔=0
            [轴]
            1:16 | 提示=测试
            """.trimIndent()
        )

        val result = AxisValidator.validate(document)

        assertEquals(
            setOf("invalid-axis-type", "invalid-click-interval"),
            result.issues.map { it.code }.toSet()
        )
    }

    @Test
    fun `rejects invalid role auto and set actions with source line`() {
        val document = AxisParser.parse(
            """
            轴类型=开关
            点击间隔=100
            角色3=原晶
            [轴]
            1:16 | 点击=不存在
            1:15 | AUTO=也许
            1:14 | SET=开,关,错误
            """.trimIndent()
        )

        val result = AxisValidator.validate(document)

        assertTrue(result.issues.any { it.code == "unknown-role" && it.line == 5 })
        assertTrue(result.issues.any { it.code == "invalid-auto-value" && it.line == 6 })
        assertTrue(result.issues.any { it.code == "invalid-set-values" && it.line == 7 })
    }

    @Test
    fun `accepts a valid sequence axis and role alias`() {
        val document = AxisParser.parse(
            """
            轴类型=顺序
            点击间隔=100
            角色3=原晶
            [轴]
            1:16 | 点击=原晶
            1:15 | 点击=AUTO | 提示=测试
            """.trimIndent()
        )

        val result = AxisValidator.validate(document)

        assertTrue(result.isValid)
        assertTrue(result.issues.isEmpty())
    }
}
