package com.kokkoro.clanbattle.axis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchAxisValidatorTest {
    @Test fun `accepts valid legacy switch axis without explicit type header`() {
        val axis = AxisParser.parse(
            """
            [轴开局] | SET=关,关,关,关,开 | AUTO=开
            1:12 | UB后=角色5 | SET=关,关,关,关,关 | AUTO=开
            0:26 | UB后=BOSS | 延迟=1.20 | SET=关,开,关,关,开 | AUTO=开
            0:18 | 卡帧=角色3 | SET=开,关,开,关,开 | AUTO=开
            """.trimIndent()
        )

        assertTrue(AxisValidator.validate(axis).issues.toString(), AxisValidator.validate(axis).isValid)
    }

    @Test fun `reports missing and duplicate switch opening`() {
        val missing = AxisParser.parse(
            """
            轴类型=开关
            [轴]
            1:00 | 提示=无开局
            """.trimIndent()
        )
        val duplicate = AxisParser.parse(
            """
            轴类型=开关
            [轴开局] | SET=关,关,关,关,开 | AUTO=开
            [轴开局] | SET=开,关,关,关,开 | AUTO=开
            """.trimIndent()
        )

        assertTrue(AxisValidator.validate(missing).issues.any { it.code == "missing-switch-opening" })
        assertTrue(AxisValidator.validate(duplicate).issues.any { it.code == "duplicate-switch-opening" })
    }

    @Test fun `reports missing targets invalid roles boss delay and conflicting triggers`() {
        val axis = AxisParser.parse(
            """
            轴类型=开关
            [轴开局] | SET=关,关,关 | AUTO=也许
            1:12 | UB后=角色9 | SET=关,关,关,关,关 | AUTO=开
            0:26 | UB后=BOSS | SET=关,开,关,关,开 | AUTO=开
            0:18 | UB后=角色3 | 卡帧=角色3 | SET=开,关,开,关,开 | AUTO=开
            0:10 | 卡帧=角色9 | 提示=缺少目标
            """.trimIndent()
        )

        val codes = AxisValidator.validate(axis).issues.map { it.code }.toSet()

        assertEquals(
            setOf(
                "switch-target-required",
                "invalid-character-ub-role",
                "boss-delay-required",
                "conflicting-switch-triggers",
                "pause-frame-target-required"
            ),
            codes
        )
    }

    @Test fun `reports invalid boss delay`() {
        val axis = AxisParser.parse(
            """
            [轴开局] | SET=关,关,关,关,开 | AUTO=开
            0:26 | UB后=BOSS | 延迟=-0.1 | SET=关,开,关,关,开 | AUTO=开
            """.trimIndent()
        )

        assertTrue(AxisValidator.validate(axis).issues.any { it.code == "invalid-boss-delay" })
    }
}
