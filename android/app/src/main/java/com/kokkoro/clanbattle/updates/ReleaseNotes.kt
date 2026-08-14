package com.kokkoro.clanbattle.updates

object ReleaseNotes {
    fun extractVersion(markdown: String, version: String): String? {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').lines()
        val start = lines.indexOfFirst { line ->
            line.trim().let { heading ->
                heading == "## $version" || heading.startsWith("## $version ")
            }
        }
        if (start < 0) return null

        val end = (start + 1 until lines.size)
            .firstOrNull { lines[it].trim().startsWith("## ") }
            ?: lines.size

        return lines.subList(start + 1, end)
            .map { line ->
                when {
                    line.startsWith("### ") -> line.removePrefix("### ")
                    line.startsWith("- ") -> "• " + line.removePrefix("- ")
                    else -> line
                }
            }
            .joinToString("\n")
            .replace("**", "")
            .replace("`", "")
            .trim()
            .takeIf { it.isNotBlank() }
    }
}
