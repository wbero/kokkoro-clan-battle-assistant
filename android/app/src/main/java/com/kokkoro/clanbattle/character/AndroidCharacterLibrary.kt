package com.kokkoro.clanbattle.character

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class CharacterLibraryInfo(
    val databaseSource: String,
    val characterCount: Int,
    val downloaded: Boolean
)

enum class CharacterLibraryInstallStatus {
    UPDATED,
    UP_TO_DATE,
    OLDER_THAN_CURRENT
}

data class CharacterLibraryInstallResult(
    val status: CharacterLibraryInstallStatus,
    val info: CharacterLibraryInfo
)

object AndroidCharacterLibrary {
    const val UPDATE_URL =
        "https://raw.githubusercontent.com/wbero/kokkoro-clan-battle-assistant/master/" +
            "android/app/src/main/assets/characters/character_library.json"

    private const val ASSET_PATH = "characters/character_library.json"
    private const val UPDATE_DIR = "character-data"
    private const val UPDATE_FILE = "character_library.json"

    private data class Payload(
        val rawJson: String,
        val databaseSource: String,
        val entries: List<CharacterLibraryEntry>
    )

    private data class Snapshot(
        val payload: Payload,
        val library: CharacterLibrary,
        val downloaded: Boolean
    ) {
        val info: CharacterLibraryInfo
            get() = CharacterLibraryInfo(
                databaseSource = payload.databaseSource,
                characterCount = payload.entries.size,
                downloaded = downloaded
            )
    }

    @Volatile private var cached: Snapshot? = null

    fun load(context: Context): CharacterLibrary = snapshot(context).library

    fun info(context: Context): CharacterLibraryInfo = snapshot(context).info

    /**
     * Validates and atomically installs a downloaded finished character library.
     * Existing usable data is never replaced by malformed or older data.
     */
    fun installUpdate(context: Context, json: String): CharacterLibraryInstallResult = synchronized(this) {
        val candidate = parsePayload(json)
        val current = snapshot(context)
        val versionComparison = compareDatabaseSource(candidate.databaseSource, current.payload.databaseSource)

        if (versionComparison != null && versionComparison < 0) {
            return@synchronized CharacterLibraryInstallResult(
                CharacterLibraryInstallStatus.OLDER_THAN_CURRENT,
                current.info
            )
        }
        if (sameContent(candidate.rawJson, current.payload.rawJson)) {
            return@synchronized CharacterLibraryInstallResult(
                CharacterLibraryInstallStatus.UP_TO_DATE,
                current.info
            )
        }

        writeUpdateAtomically(context, candidate.rawJson)
        val installed = Snapshot(candidate, CharacterLibrary(candidate.entries), downloaded = true)
        cached = installed
        CharacterLibraryInstallResult(CharacterLibraryInstallStatus.UPDATED, installed.info)
    }

    internal fun parse(json: String): List<CharacterLibraryEntry> = parsePayload(json).entries

    internal fun databaseSource(json: String): String = parsePayload(json).databaseSource

    internal fun compareDatabaseSource(left: String, right: String): Int? {
        if (left == right) return 0
        val leftVersion = databaseVersion(left) ?: return null
        val rightVersion = databaseVersion(right) ?: return null
        return leftVersion.compareTo(rightVersion)
    }

    private fun snapshot(context: Context): Snapshot = cached ?: synchronized(this) {
        cached ?: loadBestSnapshot(context.applicationContext).also { cached = it }
    }

    private fun loadBestSnapshot(context: Context): Snapshot {
        val bundled = parsePayload(
            context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        )
        val updateFile = updateFile(context)
        val downloaded = if (updateFile.isFile) {
            runCatching { parsePayload(updateFile.readText(Charsets.UTF_8)) }
                .onFailure { runCatching { updateFile.delete() } }
                .getOrNull()
        } else {
            null
        }

        val chosenDownloaded = downloaded?.takeUnless { candidate ->
            compareDatabaseSource(candidate.databaseSource, bundled.databaseSource)?.let { it < 0 } == true
        }
        if (downloaded != null && chosenDownloaded == null) runCatching { updateFile.delete() }

        val chosen = chosenDownloaded ?: bundled
        return Snapshot(
            payload = chosen,
            library = CharacterLibrary(chosen.entries),
            downloaded = chosenDownloaded != null
        )
    }

    private fun parsePayload(json: String): Payload {
        val normalized = json.trim()
        require(normalized.isNotEmpty()) { "角色数据为空" }
        val root = JSONObject(normalized)
        require(root.optInt("schemaVersion") in 1..2) { "不支持的角色数据版本" }
        val databaseSource = root.optString("databaseSource").trim()
        require(databaseSource.isNotEmpty()) { "角色数据缺少 databaseSource" }
        val array = root.getJSONArray("characters")
        require(array.length() > 0) { "角色数据没有角色" }
        val entries = buildList(array.length()) {
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
        require(entries.map { it.unitId }.distinct().size == entries.size) { "角色数据包含重复 unitId" }
        return Payload(normalized, databaseSource, entries)
    }

    private fun writeUpdateAtomically(context: Context, json: String) {
        val directory = File(context.filesDir, UPDATE_DIR)
        require(directory.exists() || directory.mkdirs()) { "无法创建角色数据目录" }
        val destination = File(directory, UPDATE_FILE)
        val temporary = File(directory, "$UPDATE_FILE.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            // The candidate was parsed before writing. Re-read the temp file as a final
            // guard against a partial/corrupt filesystem write before replacing data.
            parsePayload(temporary.readText(Charsets.UTF_8))
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun updateFile(context: Context): File = File(File(context.filesDir, UPDATE_DIR), UPDATE_FILE)

    private fun sameContent(left: String, right: String): Boolean = left.trim() == right.trim()

    private fun databaseVersion(source: String): Long? =
        Regex("^database-cn-(\\d+)$").matchEntire(source)?.groupValues?.get(1)?.toLongOrNull()
}
