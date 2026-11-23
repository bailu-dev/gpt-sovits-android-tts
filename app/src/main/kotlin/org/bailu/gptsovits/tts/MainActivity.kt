package org.bailu.gptsovits.tts

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null
    private var referencePlayer: MediaPlayer? = null
    private var connected by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var request by mutableStateOf<String?>(null)
    private var sequence = 0L
    private var selection by mutableStateOf<ModelSelection?>(null)
    private var referenceDraft by mutableStateOf<ReferenceDraft?>(null)
    private val work = MutableStateFlow<LoadProgress?>(null)

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runWork("准备导入模型") { context ->
            ModelStore.installModel(context, uri) { work.value = it }
        }
    }
    private val pickReference = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val draft = referenceDraft
        if (uri != null && draft != null) {
            val name = ModelStore.displayName(this, uri, "参考音.wav")
            referenceDraft = draft.copy(source = uri, name = name)
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        selection = ModelStore.selection(this)
        tts = TextToSpeech(this, { code -> window.decorView.post { onConnected(code) } }, packageName)
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
                dark -> darkColorScheme(primary = Color(0xFF9FD2C1))
                else -> lightColorScheme(primary = Color(0xFF276757))
            }
            MaterialExpressiveTheme(colorScheme = colors) {
                val progress by work.collectAsState()
                val metrics by SpeechService.metrics.collectAsState()
                val model = selection ?: return@MaterialExpressiveTheme
                WorkspaceScreen(
                    model = model,
                    progress = progress,
                    canSpeak = connected && model.ready,
                    previewing = request != null,
                    message = message,
                    metrics = metrics,
                    onPickModel = { pickModel.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onSpeak = ::speak,
                    onStop = ::stopPlayback,
                    onSystemSettings = ::openTtsSettings,
                    onMode = { mode ->
                        if (mode != model.mode) runWork("载入${mode.label}模型") {
                            ModelStore.selectMode(it, mode)
                        }
                    },
                    onEditReference = {
                        referenceDraft = ReferenceDraft(
                            name = model.reference?.name.orEmpty(),
                            transcript = model.reference?.transcript.orEmpty(),
                        )
                    },
                    onUseBundledReference = {
                        runWork("应用声音包参考音") { ModelStore.useBundledReference(it) }
                    },
                    onPlayReference = { model.reference?.audio?.let(::playReference) },
                )
                val draft = referenceDraft
                if (draft != null) {
                    ReferenceDialog(
                        draft = draft,
                        onText = { referenceDraft = draft.copy(transcript = it) },
                        onPick = { pickReference.launch(arrayOf("audio/*", "application/octet-stream")) },
                        onDismiss = { referenceDraft = null },
                        onSave = {
                            referenceDraft = null
                            runWork("处理参考音") {
                                ModelStore.installReference(it, draft.source, draft.transcript)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (work.value == null) selection = ModelStore.selection(this)
    }

    private fun onConnected(code: Int) {
        if (isDestroyed) return
        val engine = tts ?: return
        connected = code == TextToSpeech.SUCCESS && engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(id: String) = updatePlayback(id, "正在播放", false)
                override fun onDone(id: String) = updatePlayback(id, "播放结束", true)
                @Deprecated("Android's legacy TTS callback")
                override fun onError(id: String) = onError(id, TextToSpeech.ERROR)
                override fun onError(id: String, code: Int) = updatePlayback(id, "合成失败（$code）", true)
                override fun onStop(id: String, interrupted: Boolean) = updatePlayback(id, "已停止", true)
            }
        ) == TextToSpeech.SUCCESS
        if (!connected) message = "语音引擎连接失败，请重新打开应用。"
    }

    private fun updatePlayback(id: String, status: String, done: Boolean) {
        runOnUiThread {
            if (id == request && !isDestroyed) {
                message = status
                if (done) request = null
            }
        }
    }

    private fun speak(input: String, english: Boolean) {
        val engine = tts ?: return
        val text = input.trim()
        if (text.isEmpty()) return
        stopReference()
        val name = if (english) SpeechService.ENGLISH else SpeechService.MANDARIN
        val voice = engine.voices?.firstOrNull { it.name == name }
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
            message = "无法连接已选择的 GPT-SoVITS 音色。"
            return
        }
        val id = "preview-${++sequence}"
        request = id
        message = "正在合成"
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            request = null
            message = "合成请求提交失败。"
        }
    }

    private fun playReference(file: File) {
        stopPlayback()
        val player = MediaPlayer()
        referencePlayer = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            file.inputStream().use { player.setDataSource(it.fd) }
            player.setOnPreparedListener {
                if (referencePlayer === it) {
                    message = "正在播放参考音"
                    it.start()
                }
            }
            player.setOnCompletionListener {
                if (referencePlayer === it) {
                    stopReference()
                    message = "参考音播放结束"
                }
            }
            player.setOnErrorListener { failed, _, _ ->
                if (referencePlayer === failed) {
                    stopReference()
                    message = "参考音播放失败"
                }
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            stopReference()
            message = "无法读取参考音。"
        }
    }

    private fun stopReference() {
        referencePlayer?.release()
        referencePlayer = null
    }

    private fun stopPlayback() {
        if (request != null || referencePlayer != null) message = "已停止"
        request = null
        tts?.stop()
        stopReference()
    }

    private fun runWork(label: String, action: (android.content.Context) -> Unit) {
        if (work.value != null) return
        stopPlayback()
        work.value = LoadProgress(label)
        message = ""
        val context = applicationContext
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { action(context) }
                selection = ModelStore.selection(context)
                message = "已应用"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = "加载失败。 ${error.message.orEmpty()}"
            } catch (error: LinkageError) {
                message = "原生运行库加载失败。 ${error.message.orEmpty()}"
            } finally {
                work.value = null
            }
        }
    }

    private fun openTtsSettings() {
        try {
            startActivity(Intent("com.android.settings.TTS_SETTINGS"))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
            message = "在系统设置中搜索「文字转语音」。"
        }
    }

    override fun onDestroy() {
        stopPlayback()
        tts?.shutdown()
        super.onDestroy()
    }
}

internal data class ReferenceDraft(
    val source: Uri? = null,
    val name: String,
    val transcript: String,
)
