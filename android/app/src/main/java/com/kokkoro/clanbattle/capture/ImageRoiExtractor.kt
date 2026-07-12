package com.kokkoro.clanbattle.capture

import android.graphics.Rect
import android.media.Image
import com.kokkoro.clanbattle.recognition.PixelImage

object ImageRoiExtractor {
    fun extract(image: Image, region: Rect): PixelImage {
        require(region.left >= 0 && region.top >= 0)
        require(region.right <= image.width && region.bottom <= image.height)
        val plane = image.planes.first()
        val buffer = plane.buffer
        val pixels = IntArray(region.width() * region.height())

        repeat(region.height()) { row ->
            repeat(region.width()) { column ->
                val offset = (region.top + row) * plane.rowStride + (region.left + column) * plane.pixelStride
                val red = buffer.get(offset).toInt() and 0xff
                val green = buffer.get(offset + 1).toInt() and 0xff
                val blue = buffer.get(offset + 2).toInt() and 0xff
                val alpha = if (plane.pixelStride >= 4) buffer.get(offset + 3).toInt() and 0xff else 255
                pixels[row * region.width() + column] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        return PixelImage(region.width(), region.height(), pixels)
    }

    fun scaleReferenceRegion(width: Int, height: Int): Rect {
        return scaleRegion(width, height, 1619, 38, 64, 27)
    }

    fun scaleRegion(width: Int, height: Int, x: Int, y: Int, regionWidth: Int, regionHeight: Int): Rect {
        val scaleX = width / 1920.0
        val scaleY = height / 1080.0
        val left = (x * scaleX).toInt().coerceIn(0, width - 1)
        val top = (y * scaleY).toInt().coerceIn(0, height - 1)
        val right = ((x + regionWidth) * scaleX).toInt().coerceIn(left + 1, width)
        val bottom = ((y + regionHeight) * scaleY).toInt().coerceIn(top + 1, height)
        return Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }
}
