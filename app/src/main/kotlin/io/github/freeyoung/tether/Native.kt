package io.github.freeyoung.tether

// libtether.so, from go/ (see jni_android.c). All data is UTF-8 JSON in byte
// arrays.
internal object Native {
    init {
        System.loadLibrary("tether")
    }

    // {"handle": n} or {"error": "..."}
    @JvmStatic external fun start(config: ByteArray): ByteArray

    @JvmStatic external fun stop(handle: Long)

    // Blocks until there is an event. Null once the engine is stopped.
    @JvmStatic external fun nextEvent(handle: Long): ByteArray?

    // The result as JSON, or {"error": "..."}.
    @JvmStatic external fun call(handle: Long, op: ByteArray, arg: ByteArray?): ByteArray
}
