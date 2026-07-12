package com.kokkoro.clanbattle.axis

import android.content.Context
import com.kokkoro.clanbattle.config.AppPreferences
import java.io.File
import java.util.Base64

class AndroidAxisRepository(context: Context) : AxisStorage {
    private val appContext = context.applicationContext
    private val axesDir = File(appContext.filesDir, "axes").apply { mkdirs() }
    private val indexFile = File(axesDir, "index.tsv")

    override fun list(): List<StoredAxisText> = readIndex().mapNotNull { (id, sourceName) ->
        val file = contentFile(id)
        if (!file.isFile) null else StoredAxisText(id, sourceName, file.readText(Charsets.UTF_8))
    }

    override fun put(entry: StoredAxisText) {
        contentFile(entry.id).writeText(entry.text, Charsets.UTF_8)
        val index = readIndex().toMutableMap().apply { put(entry.id, entry.sourceName) }
        writeIndex(index)
    }

    override fun remove(id: String): Boolean {
        val index = readIndex().toMutableMap()
        val existed = index.remove(id) != null
        contentFile(id).delete()
        writeIndex(index)
        return existed
    }

    override fun selectedId(): String? = AppPreferences.selectedAxisId(appContext)

    override fun setSelectedId(id: String?) = AppPreferences.setSelectedAxisId(appContext, id)

    private fun contentFile(id: String) = File(axesDir, "$id.txt")

    private fun readIndex(): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        if (!indexFile.isFile) return result
        indexFile.readLines(Charsets.UTF_8).forEach { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size == 2) {
                runCatching {
                    result[parts[0]] = String(Base64.getDecoder().decode(parts[1]), Charsets.UTF_8)
                }
            }
        }
        return result
    }

    private fun writeIndex(index: Map<String, String>) {
        val text = index.entries.joinToString("\n") { (id, sourceName) ->
            "$id\t${Base64.getEncoder().encodeToString(sourceName.toByteArray(Charsets.UTF_8))}"
        }
        indexFile.writeText(text, Charsets.UTF_8)
    }
}
