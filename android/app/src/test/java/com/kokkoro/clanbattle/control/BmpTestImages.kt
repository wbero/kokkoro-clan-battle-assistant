package com.kokkoro.clanbattle.control

import com.kokkoro.clanbattle.recognition.PixelImage
import com.kokkoro.clanbattle.recognition.loadResourceBytes

internal fun loadBmpResource(name: String): PixelImage {
    val bytes = loadResourceBytes(name)
    require(bytes.size >= 54 && bytes[0].toInt() == 'B'.code && bytes[1].toInt() == 'M'.code)

    val pixelOffset = bytes.int32(10)
    val width = bytes.int32(18)
    val signedHeight = bytes.int32(22)
    val height = kotlin.math.abs(signedHeight)
    require(width > 0 && height > 0)
    require(bytes.int16(28) == 24) { "Only 24-bit BMP fixtures are supported" }
    require(bytes.int32(30) == 0) { "Only uncompressed BMP fixtures are supported" }

    val rowStride = ((width * 3 + 3) / 4) * 4
    val bottomUp = signedHeight > 0
    val pixels = IntArray(width * height)
    repeat(height) { y ->
        val sourceY = if (bottomUp) height - 1 - y else y
        repeat(width) { x ->
            val offset = pixelOffset + sourceY * rowStride + x * 3
            val blue = bytes[offset].toInt() and 0xff
            val green = bytes[offset + 1].toInt() and 0xff
            val red = bytes[offset + 2].toInt() and 0xff
            pixels[y * width + x] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
    return PixelImage(width, height, pixels)
}

private fun ByteArray.int16(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.int32(offset: Int): Int =
    int16(offset) or (int16(offset + 2) shl 16)
