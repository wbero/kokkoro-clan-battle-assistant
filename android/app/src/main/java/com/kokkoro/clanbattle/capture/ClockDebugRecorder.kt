package com.kokkoro.clanbattle.capture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.kokkoro.clanbattle.recognition.FilterResult
import com.kokkoro.clanbattle.recognition.EnergyDetectionResult
import com.kokkoro.clanbattle.recognition.PixelImage
import com.kokkoro.clanbattle.recognition.RecognitionResult
import com.kokkoro.clanbattle.control.BattleControlObservation
import com.kokkoro.clanbattle.control.ControlCrops
import com.kokkoro.clanbattle.control.ControlStep
import com.kokkoro.clanbattle.control.VisualToggleState
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class ClockDebugRecorder(private val context: Context) : AutoCloseable {
    private val dropped = AtomicLong()
    private val lifecycleLock = Any()
    private var closed = false
    private val sessions = ClockDebugSessionState(::createSession)
    private val queue = ClockDebugAsyncQueue(64) { error -> Log.e(TAG, "Recorder task failed", error) }

    fun startSession() = synchronized(lifecycleLock) {
        if (!closed) queue.control { sessions.start() } else false
    }

    fun record(frameId: Long, wallMs: Long, elapsedMs: Long, gate: String, recognition: RecognitionResult, filter: FilterResult?, energy: EnergyDetectionResult? = null) {
        val trace = recognition.debugTrace
        val accepted = synchronized(lifecycleLock) { !closed && queue.submit {
            val current = sessions.current() ?: return@submit
            current.frames.write(listOf(frameId, wallMs, gate, recognition.rawText, recognition.ok,
                recognition.confidence, recognition.reason, filter?.accepted, filter?.timeSeconds,
                filter?.reason, filter?.source, dropped.get()))
            energy?.let { current.energy.write(ClockDebugCsv.energyValues(frameId, wallMs, it)) }
            val saveFrame = trace?.digits?.let { digits ->
                current.sampler.shouldSaveFrame(
                    elapsedMs,
                    recognition.ok,
                    digits.any { it.rawMargin < 0.10 || it.chosen != it.rawTop1 }
                )
            } ?: false
            val cropNames = current.sampler.cropFileNames(
                frameId,
                trace?.digits?.map { it.slot.name }.orEmpty(),
                saveFrame
            )
            trace?.digits?.forEachIndexed { index, digit ->
                val cropName = if (saveFrame) {
                    cropNames[index].also { savePng(digit.crop, File(current.dir, it)) }
                } else ""
                current.digits.write(ClockDebugCsv.digitValues(frameId, wallMs, digit, cropName))
            }
        } }
        if (!accepted) dropped.incrementAndGet()
    }

    fun recordControls(
        frameId: Long,
        wallMs: Long,
        observation: BattleControlObservation?,
        step: ControlStep,
        menuScore: Double,
        crops: ControlCrops?
    ) {
        val accepted = synchronized(lifecycleLock) { !closed && queue.submit {
            val current = sessions.current() ?: return@submit
            val unknown = observation == null || !observation.consistent ||
                observation.auto.state == VisualToggleState.UNKNOWN ||
                observation.globalSet.state == VisualToggleState.UNKNOWN ||
                observation.roles.values.any { it.state == VisualToggleState.UNKNOWN }
            val saveCrops = crops != null && current.controlSampler.shouldSave(
                wallMs, unknown, step.action, step.safety
            )
            val prefix = if (saveCrops) "control-$frameId" else ""
            if (saveCrops) saveControlCrops(requireNotNull(crops), current.dir, prefix)
            current.controls.write(
                ClockDebugCsv.controlValues(frameId, wallMs, observation, step, menuScore, prefix)
            )
        } }
        if (!accepted) dropped.incrementAndGet()
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            queue.control { sessions.close() }
            queue.close()
        }
    }

    private fun createSession(): Session {
        val root = File(context.getExternalFilesDir(null), "clock-debug")
        val dir = File(root, "session-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}")
        dir.mkdirs()
        return Session(dir)
    }

    private fun savePng(image: PixelImage, file: File) {
        val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private fun saveControlCrops(crops: ControlCrops, dir: File, prefix: String) {
        savePng(crops.auto, File(dir, "$prefix-auto.png"))
        savePng(crops.globalSet, File(dir, "$prefix-global.png"))
        crops.roles.entries.sortedBy { it.key.ordinal }.forEach { (role, crop) ->
            savePng(crop, File(dir, "$prefix-${role.name.lowercase(Locale.US)}.png"))
        }
    }

    private class Session(val dir: File) : AutoCloseable {
        val frames = ClockDebugCsv(BufferedWriter(FileWriter(File(dir, "frames.csv"))), ClockDebugCsv.FRAME_HEADER)
        val digits = ClockDebugCsv(BufferedWriter(FileWriter(File(dir, "digits.csv"))), ClockDebugCsv.DIGIT_HEADER)
        val energy = ClockDebugCsv(BufferedWriter(FileWriter(File(dir, "energy.csv"))), ClockDebugCsv.ENERGY_HEADER)
        val controls = ClockDebugCsv(BufferedWriter(FileWriter(File(dir, "controls.csv"))), ClockDebugCsv.CONTROL_HEADER)
        val sampler = ClockDebugFrameSampler()
        val controlSampler = ControlCropSampler()

        override fun close() {
            runCatching { frames.close() }.onFailure { Log.e(TAG, "Failed closing frames.csv", it) }
            runCatching { digits.close() }.onFailure { Log.e(TAG, "Failed closing digits.csv", it) }
            runCatching { energy.close() }.onFailure { Log.e(TAG, "Failed closing energy.csv", it) }
            runCatching { controls.close() }.onFailure { Log.e(TAG, "Failed closing controls.csv", it) }
        }
    }

    private companion object { const val TAG = "ClockDebugRecorder" }
}
