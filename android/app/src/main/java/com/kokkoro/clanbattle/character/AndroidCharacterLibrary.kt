package com.kokkoro.clanbattle.character

import android.content.Context
import org.json.JSONObject

object AndroidCharacterLibrary {
    private const val ASSET_PATH = "characters/character_library.json"

    @Volatile private var cached: CharacterLibrary? = null

    fun load(context: Context): CharacterLibrary = cached ?: synchronized(this) {
        cached ?: CharacterLibrary(parse(context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }))
            .also { cached = it }
    }

    internal fun parse(json: String): List<CharacterLibraryEntry> {
        val root = JSONObject(json)
        require(root.optInt("schemaVersion") in 1..2) { "Unsupported character library schema" }
        val array = root.getJSONArray("characters")
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val ub = item.optJSONObject("ub")
                val ubPlus = item.optJSONObject("ubPlus")
                val aliasesJson = item.getJSONArray("aliases")
                add(
                    CharacterLibraryEntry(
                        charaId = item.getInt("charaId"),
                        unitId = item.getInt("unitId"),
                        name = item.getString("name"),
                        aliases = buildList {
                            for (aliasIndex in 0 until aliasesJson.length()) add(aliasesJson.getString(aliasIndex))
                        },
                        iconAsset = item.optString("iconAsset").takeUnless { it.isBlank() || it == "null" },
                        ub = ub?.let { CharacterSkill(it.getInt("id"), it.getString("name")) },
                        ubPlus = ubPlus?.let { CharacterSkill(it.getInt("id"), it.getString("name")) }
                    )
                )
            }
        }
    }
}
