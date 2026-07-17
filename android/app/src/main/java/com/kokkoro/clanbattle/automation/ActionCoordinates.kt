package com.kokkoro.clanbattle.automation

import com.kokkoro.clanbattle.recognition.CharacterRole

data class ReferencePoint(val x: Int, val y: Int)

object ActionCoordinates {
    val globalSet = ReferencePoint(1828, 690)
    val autoButton = ReferencePoint(1828, 845)
    val menu = ReferencePoint(1805, 50)

    fun role(role: CharacterRole): ReferencePoint = ReferencePoint(480 + role.ordinal * 240, 845)

    fun role(name: String?): ReferencePoint? = when (name) {
        "角色1" -> role(CharacterRole.ROLE_1)
        "角色2" -> role(CharacterRole.ROLE_2)
        "角色3" -> role(CharacterRole.ROLE_3)
        "角色4" -> role(CharacterRole.ROLE_4)
        "角色5" -> role(CharacterRole.ROLE_5)
        else -> null
    }

    // 主菜单“队伍情况”一行的五个角色头像中心（居中对话框，屏幕中心 960，间距约 174）。
    // 卡帧确定时在此点击设置该角色 SET（立即发动）。CENTER 锚点映射到实际屏幕。
    fun menuRole(role: CharacterRole): ReferencePoint =
        ReferencePoint(960 + (role.ordinal - 2) * 174, 490)

    // 主菜单左下角“返回”按钮：点击后关闭菜单、应用立即发动并恢复战斗（实机 1920×1080 实测）。
    val menuReturnButton = ReferencePoint(670, 868)
}
