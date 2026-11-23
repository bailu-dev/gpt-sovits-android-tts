package org.bailu.gptsovits.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow

internal data class SynthesisMetrics(
    val mode: SynthesisMode,
    val origin: String,
    val firstPcmNanos: Long,
    val synthesisNanos: Long,
    val frames: Long,
) {
    val audioSeconds: Double get() = frames.toDouble() / NativeEngine.SAMPLE_RATE
    val firstPcmMs: Long get() = (firstPcmNanos + 500_000) / 1_000_000
    val synthesisMs: Long get() = (synthesisNanos + 500_000) / 1_000_000
    val realTimeFactor: Double get() = synthesisNanos / (1_000_000_000.0 * audioSeconds)
}

class SpeechService : TextToSpeechService() {
    private val active = AtomicReference<AtomicBoolean>()
    @Volatile private var language = MANDARIN

    override fun onGetLanguage(): Array<String> =
        if (language == ENGLISH) arrayOf("eng", "USA", "") else arrayOf("zho", "CHN", "")

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        val voice = voiceForLanguage(lang) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        if (!ModelStore.selection(this).ready) return TextToSpeech.LANG_MISSING_DATA
        val supportedCountry = if (voice == ENGLISH) "USA" else "CHN"
        return if (supportedCountry.equals(country, ignoreCase = true))
            TextToSpeech.LANG_COUNTRY_AVAILABLE else TextToSpeech.LANG_AVAILABLE
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val availability = onIsLanguageAvailable(lang, country, variant)
        if (availability >= 0) language = voiceForLanguage(lang) ?: MANDARIN
        return availability
    }

    override fun onGetVoices(): List<Voice> {
        val features = if (ModelStore.selection(this).ready) emptySet()
            else setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        return listOf(
            Voice(MANDARIN, Locale.SIMPLIFIED_CHINESE, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, features),
            Voice(ENGLISH, Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, features),
        )
    }

    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? =
        voiceForLanguage(lang)

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceName == MANDARIN || voiceName == ENGLISH) TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onLoadVoice(voiceName: String?): Int {
        if (voiceName == null || onIsValidVoiceName(voiceName) != TextToSpeech.SUCCESS ||
            !ModelStore.selection(this).ready) {
            return TextToSpeech.ERROR
        }
        language = voiceName
        return TextToSpeech.SUCCESS
    }

    override fun onStop() {
        active.get()?.set(true)
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val cancelled = AtomicBoolean()
        active.set(cancelled)
        try {
            val text = request.charSequenceText?.toString()?.trim().orEmpty()
            if (text.isEmpty() || text.length > TextToSpeech.getMaxSpeechInputLength()) {
                callback.error(TextToSpeech.ERROR_INVALID_REQUEST)
                return
            }
            synchronized(ModelStore.engineLock) {
                val model = ModelStore.selection(this)
                if (cancelled.get()) return
                if (model.bundle == null || model.reference == null) {
                    callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET)
                    return
                }
                metrics.value = null
                if (callback.start(NativeEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) !=
                    TextToSpeech.SUCCESS) return
                NativeEngine.prepare(this, model.bundle, model.reference, model.mode)
                if (cancelled.get()) return
                val origin = if (request.callerUid == applicationInfo.uid) "应用试听" else "系统 TTS"
                val sink = PcmDelivery(cancelled, callback) { firstPcm, synthesis, frames ->
                    metrics.value = SynthesisMetrics(model.mode, origin, firstPcm, synthesis, frames)
                }
                NativeEngine.stream(text, sink)
                if (!cancelled.get() && !sink.failed && !sink.wroteAudio) {
                    callback.error(TextToSpeech.ERROR_SYNTHESIS)
                }
            }
        } catch (error: RuntimeException) {
            reportFailure(cancelled, callback, error)
        } catch (error: LinkageError) {
            reportFailure(cancelled, callback, error)
        } finally {
            active.compareAndSet(cancelled, null)
            callback.done()
        }
    }

    private fun reportFailure(cancelled: AtomicBoolean, callback: SynthesisCallback, error: Throwable) {
        Log.e("GptSovitsTts", "Local synthesis failed", error)
        if (!cancelled.get()) callback.error(TextToSpeech.ERROR_SYNTHESIS)
    }

    private class PcmDelivery(
        private val cancelled: AtomicBoolean,
        private val callback: SynthesisCallback,
        private val publishMetrics: (Long, Long, Long) -> Unit,
    ) : NativeEngine.PcmSink {
        private val limit = (callback.maxBufferSize and -2).also {
            check(it > 0) { "TTS PCM buffer is too small" }
        }
        var wroteAudio = false
            private set
        var failed = false
            private set

        override fun isCancelled(): Boolean = cancelled.get()

        override fun onMeasured(firstPcmNanos: Long, synthesisNanos: Long, frames: Long) {
            if (!cancelled.get()) publishMetrics(firstPcmNanos, synthesisNanos, frames)
        }

        override fun write(pcm: ByteArray): Boolean {
            var offset = 0
            while (offset < pcm.size) {
                if (cancelled.get()) return false
                val count = minOf(limit, pcm.size - offset)
                when (callback.audioAvailable(pcm, offset, count)) {
                    TextToSpeech.STOPPED -> {
                        cancelled.set(true)
                        return false
                    }
                    TextToSpeech.SUCCESS -> wroteAudio = true
                    else -> {
                        failed = true
                        callback.error(TextToSpeech.ERROR_OUTPUT)
                        return false
                    }
                }
                offset += count
            }
            return true
        }
    }

    companion object {
        internal const val MANDARIN = "bailu-gptsovits-zh-cn"
        internal const val ENGLISH = "bailu-gptsovits-en-us"
        internal val metrics = MutableStateFlow<SynthesisMetrics?>(null)

        internal fun voiceForLanguage(lang: String?): String? = when (lang?.lowercase(Locale.ROOT)) {
            "eng", "en" -> ENGLISH
            "zho", "chi", "cmn", "zh" -> MANDARIN
            else -> null
        }
    }
}
