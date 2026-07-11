package com.kokkoro.clanbattle.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class StructuralBenchmarkTest {
    private data class Sample(val file: String, val truth: Int, val pixelHash: String)

    private val templates = (0..9).associateWith { digit ->
        DigitNormalizer.normalize(loadPngResource("templates/$digit.png"))
    }

    @Test
    fun `normalized structural matching classifies all 96 reviewed frame rows`() {
        val samples = loadManifest()
        val errors = mutableListOf<String>()

        samples.forEach { sample ->
            val normalized = DigitNormalizer.normalize(loadPngResource("clock_benchmark/${sample.file}"))
            val scores = templates.mapValues { (_, template) -> StructuralMatcher.iou(normalized, template) }
            val predicted = scores.maxByOrNull { it.value }!!.key
            if (predicted != sample.truth) {
                errors += "${sample.file}: truth=${sample.truth}, predicted=$predicted, scores=$scores"
            }
        }

        assertEquals(errors.joinToString(separator = "\n"), 96, samples.size)
        assertTrue(errors.joinToString(separator = "\n"), errors.isEmpty())
    }

    @Test
    fun `normalized structural matching classifies all 34 pixel-distinct reviewed crops`() {
        val samples = pixelDistinctSamples()
        val errors = mutableListOf<String>()

        samples.forEach { sample ->
            val normalized = DigitNormalizer.normalize(loadPngResource("clock_benchmark/${sample.file}"))
            val scores = templates.mapValues { (_, template) -> StructuralMatcher.iou(normalized, template) }
            val predicted = scores.maxByOrNull { it.value }!!.key
            if (predicted != sample.truth) {
                errors += "${sample.file}: truth=${sample.truth}, predicted=$predicted, scores=$scores"
            }
        }

        assertEquals(errors.joinToString(separator = "\n"), 34, samples.size)
        assertTrue(errors.joinToString(separator = "\n"), errors.isEmpty())
    }

    @Test
    fun `pixel-distinct confusion samples have a conservative mean structural margin`() {
        val marginsForSix = mutableListOf<Double>()
        val marginsForThree = mutableListOf<Double>()

        pixelDistinctSamples().forEach { sample ->
            val normalized = DigitNormalizer.normalize(loadPngResource("clock_benchmark/${sample.file}"))
            when (sample.truth) {
                6 -> marginsForSix += StructuralMatcher.iou(normalized, templates.getValue(6)) -
                    maxOf(
                        StructuralMatcher.iou(normalized, templates.getValue(0)),
                        StructuralMatcher.iou(normalized, templates.getValue(5))
                    )
                3 -> marginsForThree += StructuralMatcher.iou(normalized, templates.getValue(3)) -
                    StructuralMatcher.iou(normalized, templates.getValue(8))
            }
        }

        assertEquals(21, marginsForSix.size)
        assertEquals(13, marginsForThree.size)
        assertTrue("6 vs max(0,5) mean margin=${marginsForSix.average()}", marginsForSix.average() > 0.15)
        assertTrue("3 vs 8 mean margin=${marginsForThree.average()}", marginsForThree.average() > 0.15)
    }

    private fun pixelDistinctSamples(): List<Sample> =
        loadManifest().distinctBy { sample -> sample.truth to sample.pixelHash }

    private fun loadManifest(): List<Sample> {
        val lines = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("clock_benchmark/manifest.csv")
        ).bufferedReader().use { it.readLines() }
        require(lines.first() == "file,session,frameId,slot,truth")
        return lines.drop(1).filter(String::isNotBlank).map { line ->
            val columns = line.split(',')
            require(columns.size == 5) { "Malformed benchmark row: $line" }
            val file = columns[0]
            val image = loadPngResource("clock_benchmark/$file")
            Sample(file = file, truth = columns[4].toInt(), pixelHash = pixelHash(image))
        }
    }

    private fun pixelHash(image: PixelImage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun update(value: Int) {
            digest.update((value ushr 24).toByte())
            digest.update((value ushr 16).toByte())
            digest.update((value ushr 8).toByte())
            digest.update(value.toByte())
        }
        update(image.width)
        update(image.height)
        image.pixels.forEach(::update)
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
