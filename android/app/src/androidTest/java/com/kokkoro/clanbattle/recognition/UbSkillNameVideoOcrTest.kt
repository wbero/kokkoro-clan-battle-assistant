package com.kokkoro.clanbattle.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UbSkillNameVideoOcrTest {
    @Test
    fun replayExtractedVideoBannerFrames() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = context.assets
        val files = assets.list(ASSET_ROOT).orEmpty()
            .filter { it.endsWith(".png", ignoreCase = true) }
            .sorted()

        check(files.isNotEmpty()) { "No generated UB replay frames under $ASSET_ROOT" }

        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val rows = mutableListOf("file,text,lines")
            files.forEach { filename ->
                val bitmap = assets.open("$ASSET_ROOT/$filename").use(BitmapFactory::decodeStream)
                    ?: error("Cannot decode $filename")
                val cropped = cropLikeProduction(bitmap)
                val scaled = Bitmap.createScaledBitmap(
                    cropped,
                    cropped.width * OCR_SCALE,
                    cropped.height * OCR_SCALE,
                    false
                )
                if (cropped !== bitmap) cropped.recycle()
                bitmap.recycle()
                val result = Tasks.await(
                    recognizer.process(InputImage.fromBitmap(scaled, 0)),
                    OCR_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
                scaled.recycle()

                val lines = result.textBlocks
                    .flatMap { block -> block.lines }
                    .map { it.text.trim() }
                    .filter { it.isNotEmpty() }
                val compactText = result.text
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                    .trim()
                rows += listOf(
                    filename,
                    compactText,
                    lines.joinToString("／")
                ).joinToString(",") { csv(it) }
            }
            val outputDir = targetContext.filesDir
            val output = File(outputDir, REPORT_NAME)
            output.writeText(
                rows.joinToString(separator = "\n", postfix = "\n"),
                Charsets.UTF_8
            )
            println(
                "UB_OCR_REPORT path=${output.absolutePath} count=${files.size}"
                )
        } finally {
            recognizer.close()
        }
    }

    private fun cropLikeProduction(bitmap: Bitmap): Bitmap {
        val left = TEXT_LEFT * bitmap.width / REFERENCE_WIDTH
        val top = TEXT_TOP * bitmap.height / REFERENCE_HEIGHT
        val right = TEXT_RIGHT * bitmap.width / REFERENCE_WIDTH
        val bottom = TEXT_BOTTOM * bitmap.height / REFERENCE_HEIGHT
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    companion object {
        private const val ASSET_ROOT = "ub_replay"
        private const val REFERENCE_WIDTH = 800
        private const val REFERENCE_HEIGHT = 110
        private const val TEXT_LEFT = 90
        private const val TEXT_TOP = 0
        private const val TEXT_RIGHT = 710
        private const val TEXT_BOTTOM = 110
        private const val OCR_SCALE = 2
        private const val OCR_TIMEOUT_SECONDS = 10L
        private const val REPORT_NAME = "ub_ocr_report.csv"

        private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
    }
}
