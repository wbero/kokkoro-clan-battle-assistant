package com.kokkoro.clanbattle.axis

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

object AxisShareCode {
    private const val PREFIX = "KCA1"
    private const val MAX_AXIS_BYTES = 256 * 1024

    fun encode(text: String): String {
        val normalized = normalize(text)
        val input = normalized.toByteArray(Charsets.UTF_8)
        require(input.isNotEmpty()) { "轴文本为空" }
        require(input.size <= MAX_AXIS_BYTES) { "轴文本过大" }

        val crc = CRC32().apply { update(input) }.value
        val compressed = deflate(input)
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed)
        return "$PREFIX.${crc.toString(16).padStart(8, '0')}.$payload"
    }

    fun decode(code: String): String {
        val compact = code.filterNot(Char::isWhitespace)
        val parts = compact.split('.', limit = 3)
        require(parts.size == 3 && parts[0] == PREFIX) { "不是受支持的轴分享码" }
        require(parts[1].length == 8) { "分享码校验段无效" }
        val expectedCrc = parts[1].toLongOrNull(16)
            ?: throw IllegalArgumentException("分享码校验段无效")
        require(parts[2].isNotEmpty()) { "分享码内容为空" }

        val compressed = try {
            Base64.getUrlDecoder().decode(parts[2])
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("分享码 Base64 内容无效", error)
        }
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(compressed) == parts[2]) {
            "分享码 Base64 内容无效"
        }
        val bytes = inflate(compressed)
        val actualCrc = CRC32().apply { update(bytes) }.value
        require(actualCrc == expectedCrc) { "分享码校验失败，内容可能不完整" }

        val decoded = bytes.toString(Charsets.UTF_8)
        require(decoded.toByteArray(Charsets.UTF_8).contentEquals(bytes)) { "分享码不是有效 UTF-8 文本" }
        return normalize(decoded)
    }

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').trim()

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        return try {
            deflater.setInput(input)
            deflater.finish()
            val output = ByteArrayOutputStream(input.size.coerceAtMost(8192))
            val buffer = ByteArray(4096)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                if (count <= 0 && !deflater.finished()) {
                    throw IllegalStateException("轴分享码压缩失败")
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(input: ByteArray): ByteArray {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(input)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (error: DataFormatException) {
                    throw IllegalArgumentException("分享码压缩内容损坏", error)
                }
                if (count > 0) {
                    output.write(buffer, 0, count)
                    require(output.size() <= MAX_AXIS_BYTES) { "分享码解压后的轴文本过大" }
                    continue
                }
                if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw IllegalArgumentException("分享码压缩内容不完整")
                }
                throw IllegalArgumentException("分享码压缩内容无法继续解压")
            }
            output.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
