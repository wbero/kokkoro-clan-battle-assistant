package com.kokkoro.clanbattle.capture

import android.graphics.Rect
import android.media.Image
import com.kokkoro.clanbattle.automation.GameCoordinateMapper
import com.kokkoro.clanbattle.automation.HorizontalAnchor
import com.kokkoro.clanbattle.recognition.PixelImage

object ImageRoiExtractor {
    fun extract(image: Image, region: Rect): PixelImage {
        require(region.left >= 0 && region.top >= 0)
        require(region.right <= image.width && region.bottom <= image.height)
        val plane = image.planes.first()
        // 独立游标的只读视图；按行批量读取代替逐像素 buffer.get()，输出逐位相同。
        val buffer = plane.buffer.duplicate()
        val pixelStride = plane.pixelStride
        val width = region.width()
        val height = region.height()
        val pixels = IntArray(width * height)
        val rowBytes = ByteArray(width * pixelStride)

        repeat(height) { row ->
            buffer.position((region.top + row) * plane.rowStride + region.left * pixelStride)
            buffer.get(rowBytes, 0, rowBytes.size)
            var source = 0
            val destinationBase = row * width
            repeat(width) { column ->
                val red = rowBytes[source].toInt() and 0xff
                val green = rowBytes[source + 1].toInt() and 0xff
                val blue = rowBytes[source + 2].toInt() and 0xff
                val alpha = if (pixelStride >= 4) rowBytes[source + 3].toInt() and 0xff else 255
                pixels[destinationBase + column] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                source += pixelStride
            }
        }
        return PixelImage(width, height, pixels)
    }

    fun scaleReferenceRegion(width: Int, height: Int): Rect {
        return scaleRegion(width, height, 1619, 38, 64, 27, HorizontalAnchor.RIGHT)
    }

    fun scaleRegion(
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        regionWidth: Int,
        regionHeight: Int,
        anchor: HorizontalAnchor = HorizontalAnchor.CENTER,
        includeCalibration: Boolean = true
    ): Rect {
        val viewport = GameCoordinateMapper.viewport(width, height)
        val left = GameCoordinateMapper.mapX(x, width, height, anchor, includeCalibration).toInt().coerceIn(0, width - 1)
        val top = (viewport.offsetY + y * viewport.scale).toInt().coerceIn(0, height - 1)
        val right = GameCoordinateMapper.mapX(x + regionWidth, width, height, anchor, includeCalibration)
            .toInt().coerceIn(left + 1, width)
        val bottom = (viewport.offsetY + (y + regionHeight) * viewport.scale).toInt().coerceIn(top + 1, height)
        return Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }
}
