package org.bailu.gptsovits.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

class VoiceDataActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val result = Intent()
        when (intent.action) {
            TextToSpeech.Engine.ACTION_CHECK_TTS_DATA -> {
                val ready = ModelStore.selection(this).ready
                val locales = arrayListOf("zho-CHN", "eng-USA")
                result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, if (ready) locales else arrayListOf())
                result.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, if (ready) arrayListOf() else locales)
                setResult(if (ready) TextToSpeech.Engine.CHECK_VOICE_DATA_PASS else TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL, result)
            }
            TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT -> {
                val english = SpeechService.voiceForLanguage(intent.getStringExtra("language")) == SpeechService.ENGLISH
                result.putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, getString(if (english) R.string.sample_en else R.string.sample_zh))
                setResult(TextToSpeech.LANG_AVAILABLE, result)
            }
        }
        finish()
    }
}
