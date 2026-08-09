package com.kokkoro.clanbattle.axis

import com.kokkoro.clanbattle.recognition.CharacterRole

/**
 * UB skill names are explicit axis metadata, independent from player-facing
 * character nicknames. The nickname can be anything; only 角色NUB participates
 * in runtime skill-banner identity matching.
 */
fun AxisDocument.roleUbSkillNames(): Map<CharacterRole, String> =
    CharacterRole.entries.mapNotNull { role ->
        val key = "角色${role.ordinal + 1}UB"
        header[key]
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { role to it }
    }.toMap()

fun CharacterRole.ubSkillHeaderKey(): String = "角色${ordinal + 1}UB"
