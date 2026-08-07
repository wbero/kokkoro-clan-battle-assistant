package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.recognition.CharacterRole
import com.kokkoro.clanbattle.recognition.EnergyRegion

data class ReferenceRegion(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * 按素材与整体控件的尺寸比例，取 [outer] 内部居中的子矩形。
 * 用于"素材只裁了控件内部一部分"的模板，避免手工写死一组无来源的坐标。
 */
fun centeredSubRegion(
    outer: ReferenceRegion,
    templateWidth: Int,
    templateHeight: Int,
    fullTemplateWidth: Int,
    fullTemplateHeight: Int
): ReferenceRegion {
    require(templateWidth in 1..fullTemplateWidth)
    require(templateHeight in 1..fullTemplateHeight)
    val width = (outer.width.toLong() * templateWidth / fullTemplateWidth).toInt().coerceAtLeast(1)
    val height = (outer.height.toLong() * templateHeight / fullTemplateHeight).toInt().coerceAtLeast(1)
    return ReferenceRegion(
        x = outer.x + (outer.width - width) / 2,
        y = outer.y + (outer.height - height) / 2,
        width = width,
        height = height
    )
}

object BattleReferenceRegions {
    val START_BUTTON = ReferenceRegion(1565, 850, 275, 115)

    /**
     * 模拟战开始按钮的文字区域。素材是按钮内的文字裁剪（216×51），不含按钮边框，
     * 因此这里取 [START_BUTTON] 内部等比居中的子矩形，而不是整颗按钮。
     * 前提是模拟战按钮与正式战斗按钮占据同一块屏幕位置——需要用调试叠加层实测确认。
     */
    val SIMULATION_START_BUTTON = centeredSubRegion(START_BUTTON, 216, 51, 277, 118)

    val LOADING = ReferenceRegion(1545, 955, 190, 60)
    val MENU_BUTTON = ReferenceRegion(1761, 33, 87, 37)
    val GLOBAL_SET_BUTTON = ReferenceRegion(1788, 644, 87, 86)
    val AUTO_BUTTON = ReferenceRegion(1783, 795, 95, 78)
    val ROLE_SET_BADGES = CharacterRole.entries.associateWith { role ->
        // The badge has a breathing animation. Keep enough surrounding pixels for
        // scale/position search instead of assuming that it always fills 54x53.
        ReferenceRegion(540 + role.ordinal * 240, 761, 74, 73)
    }
    val ENERGY_HUD = ReferenceRegion(384, 1034, 1160, 25)
    /** Five portrait slots where the universal character-UB activation flash originates. */
    val ROLE_UB_FLASH_HUD = ReferenceRegion(375, 610, 1170, 400)
    /** Generic character/BOSS skill-name banner; only the fixed background is detected. */
    val UB_NAME_BANNER = ReferenceRegion(560, 120, 800, 110)
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
