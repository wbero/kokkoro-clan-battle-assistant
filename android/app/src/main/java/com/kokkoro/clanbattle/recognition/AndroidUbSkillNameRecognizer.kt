package com.kokkoro.clanbattle.recognition

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class UbSkillNameDetection(
    val role: CharacterRole?,
    val bestRole: CharacterRole,
    val recognizedText: String,
    val expectedName: String,
    val score: Double,
    val margin: Double,
    val captureTimestampNanos: Long,
    val gameClockSeconds: Int?
)

/**
 * Asynchronous on-device OCR for the changing centre text of the existing UB
 * banner ROI. Only one request is in flight; early partial banner frames are
 * retried until the current banner cycle yields a unique match.
 */
class AndroidUbSkillNameRecognizer(
    private val matcher: UbSkillNameMatcher = UbSkillNameMatcher()
) : AutoCloseable {
    private val textRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )
    private val callbackExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "kokkoro-ub-skill-ocr").apply { isDaemon = true }
    }
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private var expectedNames: Map<CharacterRole, String> = emptyMap()
    private var cycleId = 0L
    private var cycleOpen = false
    private var cycleConfirmed = false
    private var inFlight = false
    private var completed: Pair<Long, UbSkillNameDetection>? = null

    init {
        warmUp()
    }

    val configured: Boolean get() = synchronized(lock) { expectedNames.isNotEmpty() }

    fun configure(names: Map<CharacterRole, String>) = synchronized(lock) {
        val cleaned = names.mapValues { (_, value) -> value.trim() }.filterValues(String::isNotEmpty)
        expectedNames = cleaned.takeIf {
            it.size == CharacterRole.entries.size && it.values.toSet().size == CharacterRole.entries.size
        }.orEmpty()
        resetCycleLocked()
    }

    fun reset() = synchronized(lock) { resetCycleLocked() }

    fun update(
        bannerImage: PixelImage?,
        bannerRawPresent: Boolean,
        bannerActive: Boolean,
        captureTimestampNanos: Long,
        gameClockSeconds: Int?
    ): UbSkillNameDetection? {
        if (closed.get()) return null
        val present = bannerRawPresent || bannerActive
        synchronized(lock) {
            if (expectedNames.isEmpty()) return null
            if (present && !cycleOpen) {
                cycleId++
                cycleOpen = true
                cycleConfirmed = false
                completed = null
            } else if (!present && cycleOpen) {
                // Keep the cycle id after the banner disappears so an OCR request
                // already in flight may still return its timestamped result. A
                // later banner increments the id and invalidates stale callbacks.
                cycleOpen = false
            }

            completed?.takeIf { (id, _) -> id == cycleId && !cycleConfirmed }?.let { (_, result) ->
                completed = null
                if (result.role != null) cycleConfirmed = true
                return result
            }

            if (!present || cycleConfirmed || inFlight || bannerImage == null) return null

            val requestCycle = cycleId
            val expectedSnapshot = expectedNames.toMap()
            val cropped = cropSkillName(bannerImage)
            val bitmap = cropped.toBitmap(scale = OCR_SCALE)
            val input = InputImage.fromBitmap(bitmap, 0)
            inFlight = true
            textRecognizer.process(input)
                .addOnSuccessListener(callbackExecutor) { result ->
                    val texts = buildList {
                        result.text.takeIf(String::isNotBlank)?.let(::add)
                        result.textBlocks.forEach { block ->
                            block.lines.forEach { line ->
                                line.text.takeIf(String::isNotBlank)?.let(::add)
                            }
                        }
                    }
                    val match = matcher.match(texts, expectedSnapshot)
                    synchronized(lock) resultLock@{
                        inFlight = false
                        if (closed.get() || requestCycle != cycleId || cycleConfirmed) return@resultLock
                        match ?: return@resultLock
                        completed = requestCycle to UbSkillNameDetection(
                            role = match.role,
                            bestRole = match.bestRole,
                            recognizedText = match.recognizedText,
                            expectedName = match.expectedName,
                            score = match.score,
                            margin = match.margin,
                            captureTimestampNanos = captureTimestampNanos,
                            gameClockSeconds = gameClockSeconds
                        )
                    }
                }
                .addOnFailureListener(callbackExecutor) {
                    synchronized(lock) {
                        // A newer banner cannot start another OCR while this
                        // request owns inFlight, so releasing it is always safe
                        // even when the failed request belongs to an older cycle.
                        inFlight = false
                    }
                }
                .addOnCompleteListener(callbackExecutor) { bitmap.recycle() }
            return null
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            expectedNames = emptyMap()
            completed = null
            cycleOpen = false
        }
        textRecognizer.close()
        callbackExecutor.shutdown()
    }

    private fun resetCycleLocked() {
        cycleId++
        cycleOpen = false
        cycleConfirmed = false
        completed = null
        // An in-flight callback carries the old cycle id and will be discarded.
    }

    private fun warmUp() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val input = InputImage.fromBitmap(bitmap, 0)
        synchronized(lock) { inFlight = true }
        textRecognizer.process(input)
            .addOnCompleteListener(callbackExecutor) {
                synchronized(lock) { inFlight = false }
                bitmap.recycle()
            }
    }

    companion object {
        private const val REFERENCE_WIDTH = 800
        private const val REFERENCE_HEIGHT = 110
        // Keep the full height and most of the centre width. The existing
        // UbBannerDetector already proves that this 800x110 crop is a skill
        // banner; OCR only needs to avoid the fixed decorative caps at both
        // ends. A generous crop is safer across short/long skill names than a
        // narrow hand-tuned text box.
        private const val TEXT_LEFT = 90
        private const val TEXT_TOP = 0
        private const val TEXT_RIGHT = 710
        private const val TEXT_BOTTOM = 110
        private const val OCR_SCALE = 2

        internal fun cropSkillName(image: PixelImage): PixelImage {
            val left = (TEXT_LEFT * image.width / REFERENCE_WIDTH).coerceIn(0, image.width - 1)
            val top = (TEXT_TOP * image.height / REFERENCE_HEIGHT).coerceIn(0, image.height - 1)
            val right = (TEXT_RIGHT * image.width / REFERENCE_WIDTH).coerceIn(left + 1, image.width)
            val bottom = (TEXT_BOTTOM * image.height / REFERENCE_HEIGHT).coerceIn(top + 1, image.height)
            return image.crop(left, top, right - left, bottom - top)
        }

        private fun PixelImage.toBitmap(scale: Int): Bitmap {
            val base = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            if (scale <= 1) return base
            val scaled = Bitmap.createScaledBitmap(base, width * scale, height * scale, false)
            if (scaled !== base) base.recycle()
            return scaled
        }
    }
}
