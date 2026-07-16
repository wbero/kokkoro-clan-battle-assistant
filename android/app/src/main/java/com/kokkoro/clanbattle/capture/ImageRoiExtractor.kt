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
