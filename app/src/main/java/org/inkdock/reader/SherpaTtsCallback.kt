package org.inkdock.reader

/**
 * Stable JNI bridge for sherpa-onnx's lookup of invoke([F)Ljava/lang/Integer;.
 * Kotlin's invokedynamic lambdas may expose only invoke(Object): Object. Pass this
 * named implementation to JNI; its delegate is invoked through Kotlin's interface.
 * If shrinking is enabled later, retain this class and its public typed invoke.
 */
internal class SherpaTtsCallback(private val callback: (FloatArray) -> Int) : (FloatArray) -> Int {
    override fun invoke(samples: FloatArray): Int = callback(samples)
}
