package com.chtai.assistant

class WhisperEngine {
    companion object {
        @Volatile private var available = false
        init {
            try { System.loadLibrary("jarvis_whisper"); available = true }
            catch (_: UnsatisfiedLinkError) { available = false }
        }
        fun isAvailable(): Boolean = available
    }
    @Volatile private var handle: Long = 0L
    @Synchronized fun load(path: String) {
        check(available) { "موتور Whisper در این APK ساخته نشده است." }
        unload()
        val loaded = nativeInit(path)
        check(loaded != 0L) { "مدل Whisper بارگذاری نشد؛ فایل مدل را بررسی کن." }
        handle = loaded
    }
    @Synchronized fun isLoaded() = handle != 0L
    @Synchronized fun transcribe(samples: FloatArray): String {
        check(handle != 0L) { "مدل تشخیص گفتار هنوز بارگذاری نشده است." }
        // Use the available CPU cores without oversubscribing a mobile device.
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        return nativeTranscribe(handle, samples, threads).trim()
    }
    @Synchronized fun unload() {
        val h = handle
        if (h != 0L) { nativeFree(h); handle = 0L }
    }
    private external fun nativeInit(path: String): Long
    private external fun nativeTranscribe(handle: Long, samples: FloatArray, threads: Int): String
    private external fun nativeFree(handle: Long)
}
