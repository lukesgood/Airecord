package com.airecord.app.stt

/** Receives whisper.cpp progress (0..100) for the current whisper_full call. */
fun interface WhisperProgressListener {
    fun onProgress(progress: Int)
}

/** Thin JNI wrapper around whisper.cpp (see src/main/cpp/whisper_jni.cpp). */
object WhisperLib {
    val loadError: Throwable? = try {
        System.loadLibrary("airecord_jni")
        null
    } catch (t: UnsatisfiedLinkError) {
        t
    }

    val isAvailable: Boolean get() = loadError == null

    external fun initContext(modelPath: String, useGpu: Boolean): Long
    external fun freeContext(ctx: Long)
    external fun abort()
    external fun resetAbort()
    external fun fullTranscribe(
        ctx: Long,
        threads: Int,
        audio: FloatArray,
        language: String,
        initialPrompt: String?,
        listener: WhisperProgressListener?,
    ): Int
    external fun segmentCount(ctx: Long): Int
    external fun segmentText(ctx: Long, index: Int): ByteArray
    /** Segment start, in units of 10 ms. */
    external fun segmentT0(ctx: Long, index: Int): Long
    /** Segment end, in units of 10 ms. */
    external fun segmentT1(ctx: Long, index: Int): Long
    external fun systemInfo(): String

    const val ABORTED = -200
}
