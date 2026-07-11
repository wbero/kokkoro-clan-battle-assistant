package com.kokkoro.clanbattle.recognition

data class BinaryBounds(
    val left: Int,
    val top: Int,
    val rightExclusive: Int,
    val bottomExclusive: Int
) {
    init {
        require(left >= 0 && top >= 0)
        require(rightExclusive > left && bottomExclusive > top)
    }

    val width: Int get() = rightExclusive - left
    val height: Int get() = bottomExclusive - top
}

data class BinaryImage(
    val width: Int,
    val height: Int,
    val pixels: BooleanArray
) {
    init {
        require(width > 0 && height > 0)
        require(pixels.size == width * height)
    }

    operator fun get(x: Int, y: Int): Boolean = pixels[y * width + x]

    val foregroundCount: Int get() = pixels.count { it }

    fun foregroundBounds(): BinaryBounds? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        repeat(height) { y ->
            repeat(width) inner@{ x ->
                if (!this[x, y]) return@inner
                if (x < left) left = x
                if (y < top) top = y
                if (x > right) right = x
                if (y > bottom) bottom = y
            }
        }
        if (right < 0) return null
        return BinaryBounds(left, top, right + 1, bottom + 1)
    }
}
