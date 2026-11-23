package org.bailu.gptsovits.tts;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Log;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SpeechService extends TextToSpeechService {
    static final String MANDARIN = "bailu-gptsovits-zh-cn";
    static final String ENGLISH = "bailu-gptsovits-en-us";
    private final AtomicReference<AtomicBoolean> activeRequest = new AtomicReference<>();
    private volatile String language = MANDARIN;

    @Override protected String[] onGetLanguage() {
        return ENGLISH.equals(language) ? new String[]{"eng", "USA", ""} : new String[]{"zho", "CHN", ""};
    }

    @Override protected int onIsLanguageAvailable(String lang, String country, String variant) {
        String voice = voiceForLanguage(lang);
        if (voice == null) return TextToSpeech.LANG_NOT_SUPPORTED;
        if (ModelStore.active(this) == null) return TextToSpeech.LANG_MISSING_DATA;
        String supportedCountry = ENGLISH.equals(voice) ? "USA" : "CHN";
        return supportedCountry.equalsIgnoreCase(country)
            ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_AVAILABLE;
    }

    @Override protected int onLoadLanguage(String lang, String country, String variant) {
        int availability = onIsLanguageAvailable(lang, country, variant);
        if (availability >= 0) language = voiceForLanguage(lang);
        return availability;
    }

    @Override public List<Voice> onGetVoices() {
        Set<String> features = ModelStore.active(this) == null
            ? Collections.singleton(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            : Collections.emptySet();
        return Arrays.asList(
            new Voice(MANDARIN, Locale.SIMPLIFIED_CHINESE, Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL, false, features),
            new Voice(ENGLISH, Locale.US, Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL, false, features)
        );
    }

    @Override public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        return voiceForLanguage(lang);
    }

    @Override public int onIsValidVoiceName(String voice) {
        return MANDARIN.equals(voice) || ENGLISH.equals(voice) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    @Override public int onLoadVoice(String voice) {
        if (onIsValidVoiceName(voice) != TextToSpeech.SUCCESS || ModelStore.active(this) == null) {
            return TextToSpeech.ERROR;
        }
        language = voice;
        return TextToSpeech.SUCCESS;
    }

    @Override protected void onStop() {
        AtomicBoolean request = activeRequest.get();
        if (request != null) request.set(true);
    }

    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        AtomicBoolean cancelled = new AtomicBoolean();
        activeRequest.set(cancelled);
        try {
            CharSequence input = request.getCharSequenceText();
            if (input == null || input.toString().trim().isEmpty()
                || input.length() > TextToSpeech.getMaxSpeechInputLength()) {
                callback.error(TextToSpeech.ERROR_INVALID_REQUEST);
                return;
            }
            synchronized (ModelStore.ENGINE_LOCK) {
                File bundle = ModelStore.active(this);
                if (cancelled.get()) return;
                if (bundle == null) {
                    callback.error(TextToSpeech.ERROR_NOT_INSTALLED_YET);
                    return;
                }
                int start = callback.start(NativeEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1);
                if (start != TextToSpeech.SUCCESS) return;
                NativeEngine.prepare(this, bundle);
                if (cancelled.get()) return;
                PcmDelivery delivery = new PcmDelivery(cancelled, callback);
                NativeEngine.stream(input.toString().trim(), delivery);
                if (!cancelled.get() && !delivery.sentAudio) {
                    callback.error(TextToSpeech.ERROR_SYNTHESIS);
                }
            }
        } catch (RuntimeException | LinkageError error) {
            Log.e("GptSovitsTts", "Local synthesis failed", error);
            if (!cancelled.get()) callback.error(TextToSpeech.ERROR_SYNTHESIS);
        } finally {
            activeRequest.compareAndSet(cancelled, null);
            callback.done();
        }
    }

    static String voiceForLanguage(String lang) {
        if ("eng".equalsIgnoreCase(lang) || "en".equalsIgnoreCase(lang)) return ENGLISH;
        if ("zho".equalsIgnoreCase(lang) || "chi".equalsIgnoreCase(lang)
            || "cmn".equalsIgnoreCase(lang) || "zh".equalsIgnoreCase(lang)) return MANDARIN;
        return null;
    }

    private static final class PcmDelivery implements NativeEngine.PcmSink {
        private final AtomicBoolean cancelled;
        private final SynthesisCallback callback;
        private final int limit;
        private boolean sentAudio;

        PcmDelivery(AtomicBoolean cancelled, SynthesisCallback callback) {
            this.cancelled = cancelled;
            this.callback = callback;
            limit = callback.getMaxBufferSize() & ~1;
            if (limit == 0) throw new IllegalStateException("TTS PCM buffer is too small");
        }

        @Override public boolean isCancelled() {
            return cancelled.get();
        }

        @Override public boolean write(byte[] pcm) {
            for (int offset = 0; offset < pcm.length; offset += limit) {
                if (cancelled.get()) return false;
                int count = Math.min(limit, pcm.length - offset);
                int result = callback.audioAvailable(pcm, offset, count);
                if (result == TextToSpeech.STOPPED) {
                    cancelled.set(true);
                    return false;
                }
                if (result != TextToSpeech.SUCCESS) {
                    callback.error(TextToSpeech.ERROR_OUTPUT);
                    sentAudio = true; // The callback already has an output error for this request.
                    return false;
                }
                sentAudio = true;
            }
            return true;
        }
    }
}
