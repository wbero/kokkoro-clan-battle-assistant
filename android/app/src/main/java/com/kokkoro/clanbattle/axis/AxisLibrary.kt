package com.kokkoro.clanbattle.axis

import java.security.MessageDigest

data class StoredAxisText(
    val id: String,
    val sourceName: String,
    val text: String
)

data class StoredAxis(
    val id: String,
    val name: String,
    val sourceName: String,
    val type: AxisType,
    val eventCount: Int,
    val valid: Boolean,
    val validationMessage: String?
)

interface AxisStorage {
    fun list(): List<StoredAxisText>
    fun put(entry: StoredAxisText)
    fun remove(id: String): Boolean
    fun selectedId(): String?
    fun setSelectedId(id: String?)
    fun selectionLocked(): Boolean
    fun setSelectionLocked(locked: Boolean)
}

class AxisLibrary(
    private val storage: AxisStorage
) {

    fun import(sourceName: String, text: String): StoredAxis {
        val normalized = normalize(text)
        val entry = StoredAxisText(
            id = stableId(normalized),
            sourceName = sourceName,
            text = normalized
        )
        storage.put(entry)
        return describe(entry)
    }

    fun list(): List<StoredAxis> = storage.list().map(::describe)

    fun select(id: String): Boolean {
        if (storage.selectionLocked()) return false
        val selected = list().firstOrNull { it.id == id && it.valid } ?: return false
        storage.setSelectedId(selected.id)
        return true
    }

    fun selected(): StoredAxis? {
        val id = storage.selectedId() ?: return null
        return list().firstOrNull { it.id == id && it.valid }
    }

    fun selectedDocument(): AxisDocument? {
        val id = selected()?.id ?: return null
        val text = storage.list().firstOrNull { it.id == id }?.text ?: return null
        return runCatching { AxisParser.parse(text) }.getOrNull()
    }

    fun selectedText(): String? {
        val id = selected()?.id ?: return null
        return storage.list().firstOrNull { it.id == id }?.text
    }

    fun remove(id: String): Boolean {
        if (storage.selectionLocked()) return false
        val removed = storage.remove(id)
        if (removed && storage.selectedId() == id) storage.setSelectedId(null)
        return removed
    }

    fun lock() {
        storage.setSelectionLocked(true)
    }

    fun unlock() {
        storage.setSelectionLocked(false)
    }

    fun isLocked(): Boolean = storage.selectionLocked()

    private fun describe(entry: StoredAxisText): StoredAxis {
        val result = runCatching {
            val document = AxisParser.parse(entry.text)
            val validation = AxisValidator.validate(document)
            StoredAxis(
                id = entry.id,
                name = document.header["轴名称"]?.takeIf(String::isNotBlank)
                    ?: entry.sourceName.substringBeforeLast('.'),
                sourceName = entry.sourceName,
                type = document.type,
                eventCount = if (document.type == AxisType.SWITCH) {
                    document.switchNodes.size
                } else {
                    document.events.size
                },
                valid = validation.isValid,
                validationMessage = validation.issues.takeIf(List<AxisValidationIssue>::isNotEmpty)
                    ?.joinToString("；") { it.message }
            )
        }
        return result.getOrElse { error ->
            StoredAxis(
                id = entry.id,
                name = entry.sourceName.substringBeforeLast('.'),
                sourceName = entry.sourceName,
                type = AxisType.SEQUENCE,
                eventCount = 0,
                valid = false,
                validationMessage = error.message ?: error::class.java.simpleName
            )
        }
    }

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').trim()

    private fun stableId(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }
}
