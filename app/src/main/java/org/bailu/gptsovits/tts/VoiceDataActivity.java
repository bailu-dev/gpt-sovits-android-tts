package org.bailu.gptsovits.tts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.Arrays;

public final class VoiceDataActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent result = new Intent();
        String action = getIntent().getAction();
        if (TextToSpeech.Engine.ACTION_CHECK_TTS_DATA.equals(action)) {
            boolean ready = ModelStore.active(this) != null;
            ArrayList<String> locales = new ArrayList<>(Arrays.asList("zho-CHN", "eng-USA"));
            result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                ready ? locales : new ArrayList<>());
            result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                ready ? new ArrayList<>() : locales);
            setResult(ready ? TextToSpeech.Engine.CHECK_VOICE_DATA_PASS
                : TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL, result);
        } else if (TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT.equals(action)) {
            String voice = SpeechService.voiceForLanguage(getIntent().getStringExtra("language"));
            result.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
                getString(SpeechService.ENGLISH.equals(voice) ? R.string.sample_en : R.string.sample_zh));
            setResult(TextToSpeech.LANG_AVAILABLE, result);
        }
        finish();
    }
}
