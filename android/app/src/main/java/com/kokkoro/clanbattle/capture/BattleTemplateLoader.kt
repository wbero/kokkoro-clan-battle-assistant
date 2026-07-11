package com.kokkoro.clanbattle.capture

import android.content.Context
import android.graphics.BitmapFactory
import com.kokkoro.clanbattle.recognition.PixelImage

data class BattleTemplates(
    val startBattle: PixelImage,
    val loading: PixelImage
)

object BattleTemplateLoader {
    fun load(context: Context): BattleTemplates = BattleTemplates(
        startBattle = loadImage(context, "battle/start_battle.bmp"),
        loading = loadImage(context, "battle/loading.bmp")
    )

    private fun loadImage(context: Context, path: String): PixelImage =
        context.assets.open(path).use { stream ->
            val bitmap = requireNotNull(BitmapFactory.decodeStream(stream))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            PixelImage(bitmap.width, bitmap.height, pixels).also { bitmap.recycle() }
        }
}
