package com.kokkoro.clanbattle.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class OpeningWaitStatusTextTest {
    @Test fun `labels raw clock reading and keeps energy text`() {
        assertEquals(
            "等待有效开场 1:30（原始 1:28）  46ms  TP 1:10 2:20 3:30 4:40 5:50",
            openingWaitStatusText("1:28", 46, "TP 1:10 2:20 3:30 4:40 5:50")
        )
    }

    @Test fun `shows placeholder when no raw clock was read`() {
        assertEquals(
            "等待有效开场 1:30（原始 --:--）  12ms  TP --",
            openingWaitStatusText(null, 12, "TP --")
        )
    }

    @Test fun `keeps missing energy marker visible while waiting`() {
        val text = openingWaitStatusText("0:59", 8, EnergyStatusFormatter.format(null))
        assertEquals("等待有效开场 1:30（原始 0:59）  8ms  TP --", text)
    }
}
