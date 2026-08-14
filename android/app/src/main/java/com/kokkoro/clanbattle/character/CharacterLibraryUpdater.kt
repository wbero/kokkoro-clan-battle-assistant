package com.kokkoro.clanbattle.character

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

object CharacterLibraryUpdater {
    private const val CONNECT_TIMEOUT_MS = 7_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024
    private const val ATTEMPTS_PER_SOURCE = 2
    private const val RETRY_DELAY_MS = 500L

    private const val JSDELIVR_URL =
        "https://cdn.jsdelivr.net/gh/wbero/kokkoro-clan-battle-assistant@master/" +
            "android/app/src/main/assets/characters/character_library.json"

    private val DEFAULT_SOURCES = listOf(
        AndroidCharacterLibrary.UPDATE_URL,
        JSDELIVR_URL
    )

    private class HttpStatusException(
        val statusCode: Int
    ) : IOException("服务器返回 HTTP $statusCode")

    fun download(url: String = AndroidCharacterLibrary.UPDATE_URL): String {
        val sources = if (url == AndroidCharacterLibrary.UPDATE_URL) DEFAULT_SOURCES else listOf(url)
        return downloadFromSources(sources)
    }

    internal fun downloadFromSources(sources: List<String>): String {
        require(sources.isNotEmpty()) { "没有可用的角色数据源" }

        val failures = mutableListOf<Exception>()
        for (source in sources.distinct()) {
            for (attempt in 1..ATTEMPTS_PER_SOURCE) {
                try {
                    return downloadOnce(source)
                } catch (error: Exception) {
                    failures += error
                    val shouldRetry = when (error) {
                        is HttpStatusException -> error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500
                        is IOException -> true
                        else -> false
                    }
                    if (!shouldRetry || attempt == ATTEMPTS_PER_SOURCE) break
                    try {
                        Thread.sleep(RETRY_DELAY_MS)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("角色数据更新已取消", interrupted)
                    }
                }
            }
        }

        val timedOut = failures.any { it is SocketTimeoutException }
        val last = failures.lastOrNull()
        val message = if (timedOut) {
            "角色数据下载超时，已重试并尝试 ${sources.distinct().size} 个下载源"
        } else {
            "角色数据下载失败，已尝试 ${sources.distinct().size} 个下载源：" +
                (last?.message ?: last?.javaClass?.simpleName ?: "未知错误")
        }
        throw IOException(message, last)
    }

    private fun downloadOnce(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json,text/plain;q=0.9,*/*;q=0.1")
            connection.setRequestProperty("User-Agent", "KokkoroClanBattleAssistant/3")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw HttpStatusException(responseCode)
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
