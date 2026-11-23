package org.bailu.gptsovits.tts;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.InputFilter;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int IMPORT_ZIP = 1;
    private static final ExecutorService IMPORTS = Executors.newSingleThreadExecutor();
    private TextToSpeech tts;
    private boolean connected;
    private boolean importing;
    private String utterance;
    private long sequence;

    private EditText text;
    private Spinner languages;
    private TextView modelStatus;
    private TextView playbackStatus;
    private Button importButton;
    private Button play;
    private Button stop;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        View page = findViewById(R.id.page);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            page.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets padding = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom);
                return insets;
            });
        }
        text = findViewById(R.id.preview_text);
        text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(TextToSpeech.getMaxSpeechInputLength())});
        languages = findViewById(R.id.languages);
        ArrayAdapter<String> options = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
            new String[]{getString(R.string.mandarin), getString(R.string.english)});
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languages.setAdapter(options);
        modelStatus = findViewById(R.id.model_status);
        playbackStatus = findViewById(R.id.playback_status);
        importButton = findViewById(R.id.import_model);
        play = findViewById(R.id.play);
        stop = findViewById(R.id.stop);
        importButton.setOnClickListener(view -> {
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            startActivityForResult(picker, IMPORT_ZIP);
        });
        play.setOnClickListener(view -> speak());
        stop.setOnClickListener(view -> stopPreview());
        findViewById(R.id.system_settings).setOnClickListener(view -> openTtsSettings());
        tts = new TextToSpeech(this, status -> page.post(() -> onConnected(status)), getPackageName());
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void onConnected(int status) {
        if (isDestroyed()) return;
        connected = status == TextToSpeech.SUCCESS && tts.setOnUtteranceProgressListener(
            new UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    updatePlayback(id, getString(R.string.playing), false);
                }

                @Override public void onDone(String id) {
                    updatePlayback(id, getString(R.string.played), true);
                }

                @Override public void onError(String id) {
                    onError(id, TextToSpeech.ERROR);
                }

                @Override public void onError(String id, int code) {
                    updatePlayback(id, getString(R.string.synthesis_error, code), true);
                }

                @Override public void onStop(String id, boolean interrupted) {
                    updatePlayback(id, getString(R.string.stopped), true);
                }
            }) == TextToSpeech.SUCCESS;
        if (!connected) playbackStatus.setText(R.string.engine_error);
        refresh();
    }

    private void updatePlayback(String id, String message, boolean finished) {
        runOnUiThread(() -> {
            if (isDestroyed() || !id.equals(utterance)) return;
            if (finished) utterance = null;
            playbackStatus.setText(message);
            refresh();
        });
    }

    private void refresh() {
        boolean ready = ModelStore.active(this) != null;
        modelStatus.setText(importing ? R.string.model_importing
            : ready ? R.string.model_ready : R.string.model_missing);
        importButton.setText(ready ? R.string.replace_model : R.string.import_model);
        importButton.setEnabled(!importing);
        play.setEnabled(connected && ready && !importing);
        stop.setEnabled(utterance != null);
    }

    private void speak() {
        String content = text.getText().toString().trim();
        if (content.isEmpty()) {
            playbackStatus.setText(R.string.missing_text);
            return;
        }
        String voiceName = languages.getSelectedItemPosition() == 1
            ? SpeechService.ENGLISH : SpeechService.MANDARIN;
        Set<Voice> voices = tts.getVoices();
        Voice voice = null;
        if (voices != null) {
            for (Voice candidate : voices) {
                if (voiceName.equals(candidate.getName())) {
                    voice = candidate;
                    break;
                }
            }
        }
        if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) {
            playbackStatus.setText(R.string.engine_error);
            return;
        }
        utterance = "preview-" + (++sequence);
        playbackStatus.setText(R.string.synthesizing);
        if (tts.speak(content, TextToSpeech.QUEUE_FLUSH, null, utterance) != TextToSpeech.SUCCESS) {
            utterance = null;
            playbackStatus.setText(getString(R.string.synthesis_error, TextToSpeech.ERROR));
        }
        refresh();
    }

    private void stopPreview() {
        if (utterance != null) {
            utterance = null;
            tts.stop();
            playbackStatus.setText(R.string.stopped);
            refresh();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != IMPORT_ZIP || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        stopPreview();
        importing = true;
        playbackStatus.setText("");
        refresh();
        Context context = getApplicationContext();
        IMPORTS.execute(() -> {
            String failure = null;
            try {
                ModelStore.install(context, uri);
            } catch (Exception | LinkageError error) {
                failure = getString(R.string.model_import_failed, error.getMessage());
            }
            String message = failure;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                importing = false;
                if (message != null) playbackStatus.setText(message);
                refresh();
            });
        });
    }

    private void openTtsSettings() {
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (ActivityNotFoundException error) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
            Toast.makeText(this, R.string.system_settings_fallback, Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }
}
