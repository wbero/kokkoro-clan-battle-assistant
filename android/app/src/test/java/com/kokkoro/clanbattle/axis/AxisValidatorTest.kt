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

    @Test
    fun `validates sequence trigger roles and conflicts without requiring boss delay`() {
        val document = AxisParser.parse(
            """
            轴类型=顺序
            [轴]
            1:20 | UB后=角色9 | 点击=角色1
            1:10 | UB后=角色2 | 卡帧=角色2 | 点击=角色1
            0:30 | UB后=BOSS | 点击=AUTO
            """.trimIndent()
        )

        val result = AxisValidator.validate(document)

        assertTrue(result.issues.any { it.code == "invalid-character-ub-role" && it.line == 3 })
        assertTrue(result.issues.any { it.code == "conflicting-sequence-triggers" && it.line == 4 })
        assertTrue(result.issues.none { it.code == "boss-delay-required" })
    }

    @Test
    fun `accepts sequence boss ub without delay`() {
        val document = AxisParser.parse(
            """
            轴类型=顺序
            [轴]
            0:56 | UB后=BOSS | 点击=角色5 | 点击=角色2
            """.trimIndent()
        )

        assertTrue(AxisValidator.validate(document).isValid)
    }

    @Test
    fun `accepts sequence pause frame without a second action`() {
        val document = AxisParser.parse(
            """
            轴类型=顺序
            [轴]
            1:10 | 卡帧=角色4
            """.trimIndent()
        )

        assertTrue(AxisValidator.validate(document).isValid)
    }
}
