package com.kokkoro.clanbattle.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UbSkillNameMatcherTest {
    private val names = mapOf(
        CharacterRole.ROLE_1 to "公主突袭",
        CharacterRole.ROLE_2 to "新年棱镜",
        CharacterRole.ROLE_3 to "猫咪组合技",
        CharacterRole.ROLE_4 to "星光斩击",
        CharacterRole.ROLE_5 to "极光圣域"
    )

    @Test fun `exact OCR text uniquely identifies role`() {
        val result = UbSkillNameMatcher().match(listOf("猫咪组合技"), names)

        assertEquals(CharacterRole.ROLE_3, result?.role)
        assertEquals(1.0, result?.score ?: 0.0, 0.0001)
    }

    @Test fun `normalizes spaces punctuation and full width forms`() {
        val matcher = UbSkillNameMatcher()

        val result = matcher.match(listOf("  新年・棱镜！ "), names)

        assertEquals(CharacterRole.ROLE_2, result?.role)
    }

    @Test fun `single OCR character error still matches among five candidates`() {
        val result = UbSkillNameMatcher().match(listOf("猫咪组台技"), names)

        assertEquals(CharacterRole.ROLE_3, result?.role)
        assertTrue((result?.score ?: 0.0) >= 0.80)
    }

    @Test fun `similar candidates stay unconfirmed when margin is too small`() {
        val similar = mapOf(
            CharacterRole.ROLE_1 to "星光斩击甲",
            CharacterRole.ROLE_2 to "星光斩击乙",
            CharacterRole.ROLE_3 to "猫咪组合技"
        )

        val result = UbSkillNameMatcher().match(listOf("星光斩击"), similar)

        assertNull(result?.role)
        assertTrue((result?.margin ?: 1.0) < UbSkillNameMatcher.DEFAULT_MINIMUM_MARGIN)
    }

    @Test fun `unrelated boss skill does not identify a character`() {
        val result = UbSkillNameMatcher().match(listOf("终焉毁灭炮"), names)

        assertNull(result?.role)
    }

    @Test fun `short partial OCR does not confirm a role`() {
        val result = UbSkillNameMatcher().match(listOf("猫咪"), names)

        assertNull(result?.role)
    }
}
