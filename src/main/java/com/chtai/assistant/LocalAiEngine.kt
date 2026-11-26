package com.chtai.assistant

/** On-device GGUF runtime. The native llama.cpp backend is built into the APK. */
class LocalAiEngine {
    companion object {
        @Volatile private var nativeAvailable = false
        init {
            try {
                System.loadLibrary("jarvis_llama")
                nativeAvailable = true
            } catch (_: UnsatisfiedLinkError) {
                nativeAvailable = false
            }
        }
        fun isNativeAvailable(): Boolean = nativeAvailable
    }

    @Volatile private var handle: Long = 0L

    @Synchronized fun load(modelPath: String, threads: Int = 4, context: Int = 4096) {
        check(nativeAvailable) { "موتور بومی llama.cpp در این نسخه ساخته نشده است؛ پروژه را با Android NDK بساز." }
        check(java.io.File(modelPath).isFile) { "فایل مدل پیدا نشد." }
        unload()
        val loaded = nativeLoad(modelPath, threads.coerceIn(2, 8), context.coerceIn(2048, 8192))
        check(loaded != 0L) { "مدل GGUF بارگذاری نشد؛ فایل یا حافظه دستگاه را بررسی کن." }
        handle = loaded
    }

    @Synchronized fun isLoaded(): Boolean = handle != 0L

    @Synchronized fun generate(prompt: String, maxTokens: Int = 384): String {
        val current = handle
        check(current != 0L) { "مدل محلی هنوز بارگذاری نشده است." }
        return nativeGenerate(current, prompt, maxTokens.coerceIn(64, 768))
    }

    @Synchronized fun unload() {
        val current = handle
        if (current != 0L) {
            nativeFree(current)
            handle = 0L
        }
    }

    private external fun nativeLoad(path: String, threads: Int, context: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String
    private external fun nativeFree(handle: Long)
}
