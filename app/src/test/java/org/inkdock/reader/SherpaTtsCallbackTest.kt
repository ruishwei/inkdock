package org.inkdock.reader

import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

class SherpaTtsCallbackTest {
    @Test fun exposesExactPublicBoxedIntegerMethodRequiredByJni() {
        val callback = SherpaTtsCallback { 1 }
        val method = callback.javaClass.getDeclaredMethod("invoke", FloatArray::class.java)
        assertTrue(Modifier.isPublic(callback.javaClass.modifiers))
        assertTrue(Modifier.isPublic(method.modifiers))
        assertFalse(Modifier.isStatic(method.modifiers))
        assertEquals(java.lang.Integer::class.java, method.returnType)
        assertFalse("The typed JNI entry must be a real method", method.isBridge)
        assertEquals(1, method.invoke(callback, floatArrayOf(0.1f)))
    }

    @Test fun typedJniEntryPreservesContinueAndCancelResults() {
        var proceed = true
        val callback = SherpaTtsCallback { if (proceed) 1 else 0 }
        val method = callback.javaClass.getDeclaredMethod("invoke", FloatArray::class.java)
        assertEquals(1, method.invoke(callback, FloatArray(2)))
        proceed = false
        assertEquals(0, method.invoke(callback, FloatArray(2)))
    }

    @Test fun delegatesTheSamePcmArrayAndKeepsKotlinFunctionBridge() {
        val samples = floatArrayOf(0.1f, -0.2f)
        var received: FloatArray? = null
        val callback = SherpaTtsCallback { received = it; 1 }
        val function: (FloatArray) -> Int = callback
        assertEquals(1, function(samples))
        assertSame("The signature bridge must not duplicate PCM", samples, received)
        val bridge = callback.javaClass.getDeclaredMethod("invoke", Any::class.java)
        assertTrue(bridge.isBridge)
        assertEquals(1, bridge.invoke(callback, samples))
    }
}
