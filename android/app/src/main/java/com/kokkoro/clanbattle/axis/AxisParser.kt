package com.kokkoro.clanbattle.axis

object AxisParser {
    fun parse(text: String): AxisDocument {
        val header = linkedMapOf<String, String>()
        val events = mutableListOf<AxisEvent>()
        var inAxis = false

        text.replace("\r\n", "\n").lines().forEachIndexed { index, source ->
            val lineNumber = index + 1
            val line = source.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            if (line == "[轴]") {
                inAxis = true
                return@forEachIndexed
            }

            if (!inAxis) {
                val (key, value) = parseKeyValue(line, lineNumber)
                header[key] = value
                return@forEachIndexed
            }

            val parts = line.split('|').map(String::trim)
            val actions = parts.drop(1).flatMap { parseAction(it, lineNumber) }
            events += AxisEvent(
                id = "line-$lineNumber",
                sourceLine = lineNumber,
                timeSeconds = parseTime(parts.first()),
                actions = actions
            )
        }

        return AxisDocument(
            type = if (header["轴类型"] == "开关") AxisType.SWITCH else AxisType.SEQUENCE,
            clickIntervalMs = header["点击间隔"]?.toIntOrNull() ?: 100,
            header = header,
            events = events,
            hasAxisSection = inAxis
        )
    }

    fun parseTime(raw: String): Int {
        val match = Regex("^(\\d):([0-5]\\d)$").matchEntire(raw.trim())
            ?: error("时间格式必须为 M:SS：$raw")
        return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
    }

    private fun parseAction(field: String, lineNumber: Int): List<AxisAction> {
        val (key, value) = parseKeyValue(field, lineNumber)
        return when (key) {
            "点击" -> value.split(',').map { name ->
                when (val trimmed = name.trim()) {
                    "AUTO" -> AxisAction(ActionType.CLICK_AUTO)
                    "BOSS" -> AxisAction(ActionType.BOSS)
                    else -> AxisAction(ActionType.CLICK_ROLE, role = trimmed)
                }
            }

            "提示" -> listOf(AxisAction(ActionType.NOTIFY, message = value))
            "AUTO" -> listOf(
                AxisAction(
                    ActionType.TOGGLE_AUTO,
                    value = when (value) {
                        "开" -> "on"
                        "关" -> "off"
                        else -> null
                    },
                    rawValue = value
                )
            )
            "SET" -> listOf(AxisAction(ActionType.SET_ROLES, values = value.split(',').map(String::trim)))
            else -> error("第${lineNumber}行未知字段：$key")
        }
    }

    private fun parseKeyValue(line: String, lineNumber: Int): Pair<String, String> {
        val index = line.indexOf('=')
        require(index >= 0) { "第${lineNumber}行缺少等号：$line" }
        return line.substring(0, index).trim() to line.substring(index + 1).trim()
    }
}
