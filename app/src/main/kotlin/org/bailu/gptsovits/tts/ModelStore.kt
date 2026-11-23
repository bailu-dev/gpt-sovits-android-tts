package org.bailu.gptsovits.tts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.nio.file.Files

internal enum class SynthesisMode(val label: String) {
    Streaming("流式"), Complete("整段"),
}

internal data class ReferenceVoice(val audio: File, val transcript: String, val name: String)

internal data class ModelSelection(
    val bundle: File?,
    val name: String?,
    val reference: ReferenceVoice?,
    val bundledReference: ReferenceVoice?,
    val hasCustomReference: Boolean,
    val mode: SynthesisMode,
    val completeAvailable: Boolean,
) {
    val ready: Boolean get() = bundle != null && reference != null
}

internal object ModelStore {
    val engineLock = Any()
    private const val BUNDLE_KEY = "active_bundle"
    private const val PUSHED_MODEL_KEY = "active_pushed_model"
    private const val REFERENCE_KEY = "active_reference"
    private const val MODEL_NAME_KEY = "model_name"
    private const val MODE_KEY = "mode"
    private const val MAX_WAV = 16L * 1024 * 1024
    private const val MAX_TEXT = 64 * 1024
    private val required = listOf(
        "vits.onnx", "vits_ref.onnx", "ssl.onnx", "sv.onnx", "bert.onnx", "g2pW.onnx",
        "t2s_encoder.onnx", "t2s_fs_decoder.onnx", "t2s_s_decoder.onnx",
        "g2p_en/encoder_model.onnx", "g2p_en/decoder_model.onnx",
    )

    fun selection(context: Context): ModelSelection {
        val prefs = preferences(context)
        val pushed = prefs.getString(PUSHED_MODEL_KEY, null)
        val bundle = if (pushed != null) {
            pushedModel(modelDirectory(context), pushed)
        } else {
            // An existing private model remains usable until a folder is selected.
            prefs.getString(BUNDLE_KEY, null)
                ?.let { File(context.filesDir.resolve("models"), it) }?.takeIf(File::isDirectory)
        }
        val complete = bundle?.resolve("vits_non_streaming.onnx")?.let(::hasData) == true
        val bundled = bundle?.let(::bundledReference)
        val custom = customReference(context)
        return ModelSelection(
            bundle,
            if (bundle != null) pushed ?: prefs.getString(MODEL_NAME_KEY, "v2Pro 声音包") else null,
            custom ?: bundled,
            bundled,
            custom != null,
            if (complete && prefs.getString(MODE_KEY, null) == SynthesisMode.Complete.name)
                SynthesisMode.Complete else SynthesisMode.Streaming,
            complete,
        )
    }

    fun modelDirectory(context: Context): File? =
        context.getExternalFilesDir(null)?.resolve("models")

    fun pushedModels(context: Context): List<String> {
        val root = modelDirectory(context)?.apply { mkdirs() } ?: return emptyList()
        return root.list().orEmpty().filter { pushedModel(root, it) != null }.sorted()
    }

    fun loadPushedModel(context: Context, name: String) {
        synchronized(engineLock) {
            val candidate = requireNotNull(pushedModel(modelDirectory(context), name)) {
                "找不到模型文件夹 $name"
            }
            required.forEach { require(hasData(candidate.resolve(it))) { "模型文件夹缺少 $it" } }
            val packaged = bundledReference(candidate)
            require(packaged != null ||
                !candidate.resolve("reference.wav").exists() && !candidate.resolve("reference.txt").exists()) {
                "声音包的参考音需要 WAV 和准确转录"
            }

            val prefs = preferences(context)
            val previous = prefs.getString(PUSHED_MODEL_KEY, null)
            val previousMode = prefs.getString(MODE_KEY, null)
            val mode = if (previousMode == SynthesisMode.Complete.name &&
                hasData(candidate.resolve("vits_non_streaming.onnx"))) SynthesisMode.Complete
                else SynthesisMode.Streaming
            NativeEngine.prepare(context, candidate, customReference(context) ?: packaged, mode)
            if (!prefs.edit().putString(PUSHED_MODEL_KEY, name).putString(MODE_KEY, mode.name).commit()) {
                prefs.edit().putString(PUSHED_MODEL_KEY, previous).putString(MODE_KEY, previousMode).commit()
                error("无法保存已选模型")
            }
        }
    }

    private fun pushedModel(root: File?, name: String): File? {
        if (root == null || name.isBlank() || name.startsWith('.') || '/' in name || '\\' in name) return null
        return root.resolve(name).takeIf { it.isDirectory && it.canonicalFile.parentFile == root.canonicalFile }
    }

    fun installReference(context: Context, uri: Uri?, transcript: String) {
        synchronized(engineLock) {
            val current = selection(context)
            val bundle = requireNotNull(current.bundle) { "请先载入模型" }
            val text = transcript.trim()
            require(text.isNotEmpty() && text.toByteArray().size <= MAX_TEXT) { "请填写参考音的准确转录" }
            val root = references(context).apply { mkdirs() }
            val prefs = preferences(context)
            val previous = prefs.getString(REFERENCE_KEY, null)?.let { File(root, it) }
            val candidate = Files.createTempDirectory(root.toPath(), "reference-").toFile()
            var installed = false
            try {
                val name = uri?.let { displayName(context, it, "参考音.wav") } ?: current.reference?.name
                requireNotNull(name) { "请选择参考 WAV" }
                val stream = if (uri != null) context.contentResolver.openInputStream(uri)
                    else current.reference?.audio?.inputStream()
                val wav = candidate.resolve("reference.wav")
                requireNotNull(stream) { "无法读取参考 WAV" }.use { source ->
                    wav.outputStream().use { target ->
                        val buffer = ByteArray(32 * 1024)
                        var bytes = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count == -1) break
                            bytes += count
                            require(bytes <= MAX_WAV) { "请使用简短的单声道 PCM16 WAV" }
                            target.write(buffer, 0, count)
                        }
                    }
                }
                candidate.resolve("reference.txt").writeText(text)
                candidate.resolve("name.txt").writeText(name)
                NativeEngine.prepare(context, bundle, ReferenceVoice(wav, text, name), current.mode)
                if (!prefs.edit().putString(REFERENCE_KEY, candidate.name).commit()) {
                    prefs.edit().putString(REFERENCE_KEY, previous?.name).commit()
                    error("无法保存参考音")
                }
                installed = true
                previous?.let(::cleanUp)
            } finally {
                if (!installed) cleanUp(candidate)
            }
        }
    }

    fun selectMode(context: Context, mode: SynthesisMode) {
        synchronized(engineLock) {
            val current = selection(context)
            val bundle = requireNotNull(current.bundle) { "请先载入模型" }
            require(mode != SynthesisMode.Complete || current.completeAvailable) {
                "声音包需要包含 vits_non_streaming.onnx"
            }
            NativeEngine.prepare(context, bundle, current.reference, mode)
            val prefs = preferences(context)
            if (!prefs.edit().putString(MODE_KEY, mode.name).commit()) {
                prefs.edit().putString(MODE_KEY, current.mode.name).commit()
                error("无法保存合成模式")
            }
        }
    }

    fun useBundledReference(context: Context) {
        synchronized(engineLock) {
            val current = selection(context)
            val bundle = requireNotNull(current.bundle) { "请先载入模型" }
            val reference = requireNotNull(current.bundledReference) { "当前模型没有提供参考音" }
            NativeEngine.prepare(context, bundle, reference, current.mode)
            val prefs = preferences(context)
            val previous = prefs.getString(REFERENCE_KEY, null)
            if (!prefs.edit().remove(REFERENCE_KEY).commit()) {
                prefs.edit().putString(REFERENCE_KEY, previous).commit()
                error("无法应用声音包参考音")
            }
        }
    }

    fun displayName(context: Context, uri: Uri, fallback: String): String =
        query(context, uri, OpenableColumns.DISPLAY_NAME)?.take(160) ?: fallback

    private fun query(context: Context, uri: Uri, column: String): String? =
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }

    private fun customReference(context: Context): ReferenceVoice? =
        preferences(context).getString(REFERENCE_KEY, null)?.let { name ->
            val directory = references(context).resolve(name)
            val title = directory.resolve("name.txt").takeIf(::hasData)?.readText() ?: "自定义参考音"
            readReference(directory, title)
        }

    private fun bundledReference(directory: File): ReferenceVoice? {
        val label = directory.resolve("reference_name.txt")
        val title = if (label.isFile && label.length() in 1..512L) label.readText().trim().take(160) else ""
        return readReference(directory, title.ifEmpty { "声音包 · reference.wav" })
    }

    private fun readReference(directory: File, name: String): ReferenceVoice? {
        val wav = directory.resolve("reference.wav")
        val text = directory.resolve("reference.txt")
        if (!hasData(wav) || !hasData(text)) return null
        require(wav.length() <= MAX_WAV && text.length() <= MAX_TEXT) { "请使用简短的参考音和转录" }
        val transcript = text.readText().trim()
        return if (transcript.isEmpty()) null else ReferenceVoice(wav, transcript, name)
    }

    private fun hasData(file: File): Boolean = file.isFile && file.length() > 0
    private fun references(context: Context) = File(context.filesDir, "references")
    private fun preferences(context: Context) = context.getSharedPreferences("tts_models", Context.MODE_PRIVATE)

    private fun cleanUp(dir: File) {
        if (dir.exists() && !dir.deleteRecursively()) Log.w("GptSovitsTts", "Staged bundle cleanup failed")
    }
}
