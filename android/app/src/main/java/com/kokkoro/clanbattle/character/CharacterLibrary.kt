package com.kokkoro.clanbattle.character

data class CharacterSkill(
    val id: Int,
    val name: String
)

data class CharacterLibraryEntry(
    val charaId: Int,
    val unitId: Int,
    val name: String,
    val aliases: List<String>,
    val iconAsset: String?,
    val ub: CharacterSkill?,
    val ubPlus: CharacterSkill?
) {
    fun ubNameForSixStar(sixStar: Boolean): String? =
        if (sixStar) ubPlus?.name ?: ub?.name else ub?.name

    fun matches(query: String): Boolean {
        val needle = normalize(query)
        if (needle.isEmpty()) return true
        return searchableNames().any { normalize(it).contains(needle) }
    }

    internal fun searchRank(query: String): Int {
        val needle = normalize(query)
        if (needle.isEmpty()) return 0
        val normalizedName = normalize(name)
        if (normalizedName == needle) return 0
        val normalizedAliases = aliases.map(::normalize)
        if (needle in normalizedAliases) return 1
        if (normalizedName.startsWith(needle)) return 2
        if (normalizedAliases.any { it.startsWith(needle) }) return 3
        if (normalizedName.contains(needle)) return 4
        if (normalizedAliases.any { it.contains(needle) }) return 5
        return Int.MAX_VALUE
    }

    private fun searchableNames(): List<String> = buildList {
        add(name)
        addAll(aliases)
    }
}

class CharacterLibrary(entries: List<CharacterLibraryEntry>) {
    val entries: List<CharacterLibraryEntry> = entries.sortedBy { it.unitId }

    fun search(query: String, limit: Int = 30): List<CharacterLibraryEntry> {
        require(limit > 0)
        val value = query.trim()
        if (value.isEmpty()) return entries.take(limit)
        return entries.asSequence()
            .map { it to it.searchRank(value) }
            .filter { (_, rank) -> rank != Int.MAX_VALUE }
            .sortedWith(compareBy<Pair<CharacterLibraryEntry, Int>> { it.second }.thenBy { it.first.unitId })
            .map { it.first }
            .take(limit)
            .toList()
    }

    fun byUnitId(unitId: Int): CharacterLibraryEntry? = entries.firstOrNull { it.unitId == unitId }
}

private fun normalize(value: String): String = value.trim().lowercase()
