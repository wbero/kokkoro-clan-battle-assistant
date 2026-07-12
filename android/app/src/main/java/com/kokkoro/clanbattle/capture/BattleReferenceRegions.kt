package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.EnergyRegion

data class ReferenceRegion(val x: Int, val y: Int, val width: Int, val height: Int)

object BattleReferenceRegions {
    val START_BUTTON = ReferenceRegion(1565, 850, 275, 115)
    val LOADING = ReferenceRegion(1545, 955, 190, 60)
    val ENERGY_HUD = ReferenceRegion(384, 1034, 1160, 25)
    val ENERGY_REGIONS = mapOf(
        CharacterRole.ROLE_1 to EnergyRegion(8, 6, 176, 13),
        CharacterRole.ROLE_2 to EnergyRegion(248, 6, 176, 13),
        CharacterRole.ROLE_3 to EnergyRegion(488, 6, 176, 13),
        CharacterRole.ROLE_4 to EnergyRegion(728, 6, 176, 13),
        CharacterRole.ROLE_5 to EnergyRegion(968, 6, 176, 13)
    )

    fun energyRegionsForHud(width: Int, height: Int): Map<CharacterRole, EnergyRegion> {
        require(width > 0 && height > 0)
        return ENERGY_REGIONS.mapValues { (_, region) ->
            val left = region.x * width / ENERGY_HUD.width
            val top = region.y * height / ENERGY_HUD.height
            val right = (region.x + region.width) * width / ENERGY_HUD.width
            val bottom = (region.y + region.height) * height / ENERGY_HUD.height
            EnergyRegion(left, top, maxOf(1, right - left), maxOf(1, bottom - top))
        }
    }
}
