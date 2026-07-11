package com.kokkoro.clanbattle.recognition

object StructuralMatcher {
    fun iou(first: BinaryImage, second: BinaryImage): Double {
        require(first.width == second.width && first.height == second.height)
        var intersection = 0
        var union = 0
        first.pixels.indices.forEach { index ->
            val firstForeground = first.pixels[index]
            val secondForeground = second.pixels[index]
            if (firstForeground && secondForeground) intersection += 1
            if (firstForeground || secondForeground) union += 1
        }
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }
}
