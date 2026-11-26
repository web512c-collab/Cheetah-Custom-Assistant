package com.example.llm

interface LlamaTokenCallback {
    fun onToken(tokenText: String)
}

/**
 * Low-level JNI bridge to libcheetah_llama.so (llama.cpp).
 * Gracefully detects if the native library is unavailable on JVM unit-test hosts or non-arm64 ABIs.
 */
object LlamaNativeBridge {

    val isNativeAvailable: Boolean by lazy {
        try {
            System.loadLibrary("cheetah_llama")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    external fun nativeLoadModelFromPath(path: String, contextLen: Int, threads: Int): Boolean
    external fun nativeLoadModelFromFd(fd: Int, contextLen: Int, threads: Int): Boolean
    external fun nativeGetModelDescription(): String
    external fun nativeGetChatTemplate(): String
    external fun nativeCountTokens(text: String): Int
    external fun nativeClearKvCache()
    external fun nativeUnloadModel()
    external fun nativeCancelGeneration()
    external fun nativeGenerateStream(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int,
        temperature: Float,
        tokenCallback: LlamaTokenCallback?
    ): String

    external fun nativeGenerateRawStream(
        formattedPrompt: String,
        maxTokens: Int,
        temperature: Float,
        gbnfGrammar: String?,
        reuseKvCache: Boolean,
        tokenCallback: LlamaTokenCallback?
    ): String
}
