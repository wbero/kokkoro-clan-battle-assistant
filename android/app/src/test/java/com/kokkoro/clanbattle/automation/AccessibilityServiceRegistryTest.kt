package com.kokkoro.clanbattle.automation

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AccessibilityServiceRegistryTest {
    @Test
    fun `disconnecting stale service never clears replacement connection`() {
        val registry = AccessibilityServiceRegistry<Any>()
        val oldService = Any()
        val newService = Any()

        registry.connect(oldService)
        registry.connect(newService)
        registry.disconnect(oldService)

        assertSame(newService, registry.current())
        registry.disconnect(newService)
        assertNull(registry.current())
    }
}
