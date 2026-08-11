package com.kokkoro.clanbattle.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CharacterLibraryTest {
    private val tamaki = CharacterLibraryEntry(
        charaId = 1046,
        unitId = 104601,
        name = "珠希",
        aliases = listOf("珠希", "タマキ", "Tamaki", "猫剑", "🐱剑"),
        iconAsset = "characters/icons/icon_unit_104631.png",
        ub = CharacterSkill(1046001, "猫猫决胜爪"),
        ubPlus = CharacterSkill(1046011, "猫猫幻影斩击")
    )
    private val rei = CharacterLibraryEntry(
        charaId = 1003,
        unitId = 100301,
        name = "怜",
        aliases = listOf("怜", "Rei", "剑圣"),
        iconAsset = null,
        ub = CharacterSkill(1003001, "斩刃风暴"),
        ubPlus = null
    )

    @Test fun aliasesCanFindCharacter() {
        val library = CharacterLibrary(listOf(rei, tamaki))
        assertEquals(tamaki, library.search("猫剑").single())
        assertEquals(tamaki, library.search("tama").single())
    }

    @Test fun exactMatchRanksBeforeContainsMatch() {
        val other = rei.copy(name = "猫剑测试", aliases = listOf("猫剑测试"))
        val library = CharacterLibrary(listOf(other, tamaki))
        assertEquals(tamaki, library.search("猫剑").first())
    }

    @Test fun sixStarUsesUbPlusAndFallsBackToNormalUb() {
        assertEquals("猫猫幻影斩击", tamaki.ubNameForSixStar(true))
        assertEquals("猫猫决胜爪", tamaki.ubNameForSixStar(false))
        assertEquals("斩刃风暴", rei.ubNameForSixStar(true))
        assertNull(rei.ubPlus)
    }

    @Test fun characterWithoutDatabaseUbStillRemainsSearchable() {
        val nephi = CharacterLibraryEntry(
            charaId = 1297,
            unitId = 129701,
            name = "涅妃‧涅罗",
            aliases = listOf("涅妃‧涅罗", "涅妃", "Nephi-Nera"),
            iconAsset = "characters/icons/icon_unit_129731.webp",
            ub = null,
            ubPlus = null
        )
        val library = CharacterLibrary(listOf(nephi))

        assertEquals(nephi, library.search("涅妃").single())
        assertNull(nephi.ubNameForSixStar(false))
        assertNull(nephi.ubNameForSixStar(true))
    }
}
