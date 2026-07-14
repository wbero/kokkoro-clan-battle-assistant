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
}
