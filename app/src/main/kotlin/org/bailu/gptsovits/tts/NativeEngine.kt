package org.bailu.gptsovits.tts

import android.content.Context
import java.io.File

internal object NativeEngine {
    const val SAMPLE_RATE = 32_000

    init {
        System.loadLibrary("gpt_sovits_tts")
    }

    interface PcmSink {
        fun isCancelled(): Boolean
        fun write(pcm: ByteArray): Boolean
    }

    fun prepare(context: Context, bundle: File, voice: ReferenceVoice?, mode: SynthesisMode) {
        val library = File(context.applicationInfo.nativeLibraryDir, "libonnxruntime.so")
        prepareModel(
            bundle.absolutePath, library.absolutePath, voice?.audio?.absolutePath.orEmpty(),
            voice?.transcript.orEmpty(), mode == SynthesisMode.Streaming,
        )
    }

    @JvmStatic private external fun prepareModel(
        bundle: String, onnxRuntime: String, wav: String, transcript: String, streaming: Boolean,
    )

    @JvmStatic external fun stream(text: String, sink: PcmSink)
}
