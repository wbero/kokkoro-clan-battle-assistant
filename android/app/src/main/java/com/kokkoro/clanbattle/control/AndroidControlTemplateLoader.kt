package com.kokkoro.clanbattle.control

import android.content.Context
import android.graphics.BitmapFactory
import com.kokkoro.clanbattle.recognition.PixelImage

data class LoadedControlTemplates(
    val controls: BattleControlTemplates,
    val menu: PixelImage
)

object AndroidControlTemplateLoader {
    fun load(context: Context): LoadedControlTemplates = LoadedControlTemplates(
        controls = BattleControlTemplates(
            autoOn = loadImage(context, "templates/auto_on.bmp"),
            autoOff = loadImage(context, "templates/auto_off.bmp"),
            globalSetOn = loadImage(context, "templates/set_on.bmp"),
            globalSetOff = loadImage(context, "templates/set_off.bmp"),
            roleSetOn = loadImage(context, "templates/set.bmp")
        ),
        menu = loadImage(context, "templates/menu.bmp")
    )

    private fun loadImage(context: Context, path: String): PixelImage =
        context.assets.open(path).use { stream ->
            val bitmap = requireNotNull(BitmapFactory.decodeStream(stream)) { "无法加载控制模板：$path" }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            PixelImage(bitmap.width, bitmap.height, pixels).also { bitmap.recycle() }
        }
}
