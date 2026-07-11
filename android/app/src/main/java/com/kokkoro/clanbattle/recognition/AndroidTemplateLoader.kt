package com.kokkoro.clanbattle.recognition

import android.content.Context
import android.graphics.BitmapFactory

object AndroidTemplateLoader {
    fun load(context: Context): DigitTemplates {
        val digits = (0..9).associateWith { digit ->
            context.assets.open("templates/$digit.png").use { stream ->
                val bitmap = requireNotNull(BitmapFactory.decodeStream(stream))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                PixelImage(bitmap.width, bitmap.height, pixels).also { bitmap.recycle() }
            }
        }
        return DigitTemplates(digits)
    }
}
