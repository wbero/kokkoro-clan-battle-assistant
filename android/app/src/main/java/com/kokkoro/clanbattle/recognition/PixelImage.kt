package com.kokkoro.clanbattle.recognition

data class PixelImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray
) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    fun crop(x: Int, y: Int, cropWidth: Int, cropHeight: Int): PixelImage {
        require(x >= 0 && y >= 0 && cropWidth > 0 && cropHeight > 0)
        require(x + cropWidth <= width && y + cropHeight <= height)
        val result = IntArray(cropWidth * cropHeight)
        repeat(cropHeight) { row ->
            pixels.copyInto(
                destination = result,
                destinationOffset = row * cropWidth,
                startIndex = (y + row) * width + x,
                endIndex = (y + row) * width + x + cropWidth
            )
        }
        return PixelImage(cropWidth, cropHeight, result)
    }
}

data class DigitTemplates(val digits: Map<Int, PixelImage>) {
    init {
        require((0..9).all(digits::containsKey))
    }
}
