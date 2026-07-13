package com.kokkoro.clanbattle.axis

import com.kokkoro.clanbattle.recognition.CharacterRole

object AxisParser {
    fun parse(text: String): AxisDocument {
        val header = linkedMapOf<String, String>()
        val events = mutableListOf<AxisEvent>()
        val switchOpenings = mutableListOf<SwitchAxisOpening>()
        val switchNodes = mutableListOf<SwitchAxisNode>()
        var inAxis = false
        var inSwitchAxis = false

        text.replace("\r\n", "\n").lines().forEachIndexed { index, source ->
            val lineNumber = index + 1
            val line = source.trim()
            if (line.isEmpty() || line.startsWith('#')) return@forEachIndexed
            if (line == "[轴]") {
                inAxis = true
                inSwitchAxis = false
                return@forEachIndexed
            }

            if (line.startsWith("[轴开局]")) {
                val parts = line.split('|').map(String::trim)
                require(parts.first() == "[轴开局]") { "第${lineNumber}行轴开局格式错误" }
                switchOpenings += SwitchAxisOpening(
                    sourceLine = lineNumber,
                    target = parseSwitchTarget(parseFields(parts.drop(1), lineNumber))
                )
                inSwitchAxis = true
                inAxis = false
                return@forEachIndexed
            }

            if (!inAxis && !inSwitchAxis) {
                val (key, value) = parseKeyValue(line, lineNumber)
                header[key] = value
                return@forEachIndexed
            }

            if (inSwitchAxis) {
                val parts = line.split('|').map(String::trim)
                val fields = parseFields(parts.drop(1), lineNumber)
                switchNodes += SwitchAxisNode(
                    id = "switch-line-$lineNumber",
                    sourceLine = lineNumber,
                    timeSeconds = parseTime(parts.first()),
                    trigger = parseNodeTrigger(fields),
                    target = parseSwitchTarget(fields)
                )
                return@forEachIndexed
            }

            val parts = line.split('|').map(String::trim)
            val sequenceFields = parts.drop(1).map { parseKeyValue(it, lineNumber) }
            val triggerFields = linkedMapOf<String, String>()
            sequenceFields.filter { (key, _) -> key in triggerKeys }.forEach { (key, value) ->
                require(triggerFields.put(key, value) == null) { "第${lineNumber}行字段重复：$key" }
            }
            val actions = sequenceFields
                .filterNot { (key, _) -> key in triggerKeys }
                .flatMap { (key, value) -> parseAction("$key=$value", lineNumber) }
            events += AxisEvent(
                id = "line-$lineNumber",
                sourceLine = lineNumber,
                timeSeconds = parseTime(parts.first()),
                actions = actions,
                trigger = parseNodeTrigger(triggerFields)
            )
        }

        return AxisDocument(
            type = if (header["轴类型"] == "开关" || switchOpenings.isNotEmpty()) {
                AxisType.SWITCH
            } else {
                AxisType.SEQUENCE
            },
            clickIntervalMs = header["点击间隔"]?.toIntOrNull() ?: 100,
            header = header,
            events = events,
            hasAxisSection = inAxis || switchOpenings.isNotEmpty(),
            switchOpenings = switchOpenings,
            switchNodes = switchNodes,
            hasSwitchSection = switchOpenings.isNotEmpty()
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

    private fun parseFields(parts: List<String>, lineNumber: Int): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        parts.filter(String::isNotBlank).forEach { part ->
            val (key, value) = parseKeyValue(part, lineNumber)
            require(fields.put(key, value) == null) { "第${lineNumber}行字段重复：$key" }
        }
        return fields
    }

    private fun parseSwitchTarget(fields: Map<String, String>): SwitchControlTarget {
        val rawRoles = fields["SET"]?.split(',')?.map(String::trim).orEmpty()
        val roles = CharacterRole.entries.zip(rawRoles.map(::parseToggleState)).toMap()
        val rawAuto = fields["AUTO"]
        return SwitchControlTarget(
            auto = rawAuto?.let(::parseToggleState),
            roles = roles,
            rawAuto = rawAuto,
            rawRoles = rawRoles,
            message = fields["提示"]
        )
    }

    private fun parseNodeTrigger(fields: Map<String, String>): SwitchNodeTrigger {
        val ubAfter = fields["UB后"]
        val pauseFrame = fields["卡帧"]
        if (ubAfter != null && pauseFrame != null) {
            return ConflictingSwitchTrigger(ubAfter, pauseFrame)
        }
        if (pauseFrame != null) {
            return PauseFrameTrigger(parseRole(pauseFrame), pauseFrame)
        }
        if (ubAfter == null) return TimedTrigger
        if (ubAfter.equals("BOSS", ignoreCase = true)) {
            val rawDelay = fields["延迟"]
            return BossDelayTrigger(
                minimumDelayMs = rawDelay?.toDoubleOrNull()?.let { (it * 1_000).toLong() },
                rawDelay = rawDelay
            )
        }
        return CharacterUbTrigger(parseRole(ubAfter), ubAfter)
    }

    private fun parseToggleState(raw: String): AxisToggleState? = when (raw) {
        "开" -> AxisToggleState.ON
        "关" -> AxisToggleState.OFF
        else -> null
    }

    private fun parseRole(raw: String): CharacterRole? = when (raw) {
        "角色1" -> CharacterRole.ROLE_1
        "角色2" -> CharacterRole.ROLE_2
        "角色3" -> CharacterRole.ROLE_3
        "角色4" -> CharacterRole.ROLE_4
        "角色5" -> CharacterRole.ROLE_5
        else -> null
    }

    private val triggerKeys = setOf("UB后", "延迟", "卡帧")
}
