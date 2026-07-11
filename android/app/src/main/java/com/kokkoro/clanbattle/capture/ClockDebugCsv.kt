package com.kokkoro.clanbattle.capture

import java.io.Closeable
import java.io.Writer

class ClockDebugCsv(private val writer: Writer, header: String) : Closeable {
    var isClosed = false
        private set

    init { writer.append(header).append('\n') }

    @Synchronized fun write(values: List<Any?>): Boolean {
        if (isClosed) return false
        writer.append(values.joinToString(",") { escape(it?.toString().orEmpty()) }).append('\n')
        return true
    }

    @Synchronized override fun close() {
        if (isClosed) return
        isClosed = true
        writer.flush()
        writer.close()
    }

    companion object {
        const val FRAME_HEADER = "frameId,wallMs,gate,recognitionRaw,recognitionOk,recognitionConfidence,recognitionReason,filterAccepted,filterTime,filterReason,filterSource,dropped"
        const val DIGIT_HEADER = "frameId,wallMs,slot,rawTop1,rawTop2,rawMargin,chosen,chosenScore,decisionMargin,decisionRule,s0,s1,s2,s3,s4,s5,s6,s7,s8,s9,cropFile"

        private fun escape(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
                "\"${value.replace("\"", "\"\"")}\"" else value
    }
}
