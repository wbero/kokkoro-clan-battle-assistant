package com.kokkoro.clanbattle.recognition

import ar.com.hjg.pngj.PngReaderInt
import java.io.ByteArrayInputStream

internal fun loadPngResource(name: String): PixelImage {
    val bytes = loadResourceBytes(name)
    val reader = PngReaderInt(ByteArrayInputStream(bytes))
    val pixels = IntArray(reader.imgInfo.cols * reader.imgInfo.rows)
    repeat(reader.imgInfo.rows) { y ->
        val line = reader.readRowInt()
        repeat(reader.imgInfo.cols) { x ->
            val offset = x * reader.imgInfo.channels
            val red: Int
            val green: Int
            val blue: Int
            val alpha: Int
            if (reader.imgInfo.greyscale) {
                red = line.scanline[offset]
                green = red
                blue = red
                alpha = if (reader.imgInfo.alpha) line.scanline[offset + 1] else 255
            } else {
                red = line.scanline[offset]
                green = line.scanline[offset + 1]
                blue = line.scanline[offset + 2]
                alpha = if (reader.imgInfo.alpha) line.scanline[offset + 3] else 255
            }
            pixels[y * reader.imgInfo.cols + x] =
                (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
    reader.end()
    return PixelImage(reader.imgInfo.cols, reader.imgInfo.rows, pixels)
}

internal fun loadResourceBytes(name: String): ByteArray {
    val classLoader = requireNotNull(Thread.currentThread().contextClassLoader)
    val stream = requireNotNull(classLoader.getResourceAsStream(name)) {
        "Missing test resource: $name"
    }
    return stream.use { it.readBytes() }
}
