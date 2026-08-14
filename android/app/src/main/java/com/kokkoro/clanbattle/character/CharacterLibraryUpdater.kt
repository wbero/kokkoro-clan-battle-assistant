package com.kokkoro.clanbattle.character

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object CharacterLibraryUpdater {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024

    fun download(url: String = AndroidCharacterLibrary.UPDATE_URL): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json,text/plain;q=0.9,*/*;q=0.1")
            connection.setRequestProperty("User-Agent", "KokkoroClanBattleAssistant/3")
            connection.connect()
            require(connection.responseCode in 200..299) {
                "服务器返回 HTTP ${connection.responseCode}"
            }
            val declaredLength = connection.contentLengthLong
            require(declaredLength <= 0 || declaredLength <= MAX_DOWNLOAD_BYTES) { "角色数据文件过大" }

            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(
                    declaredLength.takeIf { it in 1..MAX_DOWNLOAD_BYTES.toLong() }?.toInt() ?: 128 * 1024
                )
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_DOWNLOAD_BYTES) { "角色数据文件过大" }
                    output.write(buffer, 0, count)
                }
                return output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
}
