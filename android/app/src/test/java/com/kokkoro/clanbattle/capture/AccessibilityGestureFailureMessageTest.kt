package com.kokkoro.clanbattle.capture

import com.kokkoro.clanbattle.automation.GestureDispatchEvent
import com.kokkoro.clanbattle.automation.GestureDispatchStatus
import com.kokkoro.clanbattle.automation.HorizontalAnchor
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityGestureFailureMessageTest {
    @Test
    fun `rejected accessibility gesture reports permission failure instead of recognition failure`() {
        val event = GestureDispatchEvent(
            status = GestureDispatchStatus.REJECTED,
            referenceX = 1,
            referenceY = 2,
            mappedX = 3f,
            mappedY = 4f,
            frameWidth = 1920,
            frameHeight = 1080,
            anchor = HorizontalAnchor.CENTER
        )

        assertEquals(
            "无障碍服务拒绝点击，请关闭后重新开启无障碍服务",
            accessibilityGestureFailureMessage(event)
        )
    }
}
