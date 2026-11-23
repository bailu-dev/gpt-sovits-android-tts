package org.bailu.gptsovits.tts

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceScreen(
    model: ModelSelection,
    progress: LoadProgress?,
    canSpeak: Boolean,
    previewing: Boolean,
    message: String,
    metrics: SynthesisMetrics?,
    onPickModel: () -> Unit,
    onSpeak: (String, Boolean) -> Unit,
    onStop: () -> Unit,
    onSystemSettings: () -> Unit,
    onMode: (SynthesisMode) -> Unit,
    onEditReference: () -> Unit,
    onUseBundledReference: () -> Unit,
    onPlayReference: () -> Unit,
) {
    val sample = stringResource(R.string.sample_zh)
    var text by rememberSaveable { mutableStateOf(sample) }
    var english by rememberSaveable { mutableStateOf(false) }
    val working = progress != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GPT-SoVITS") },
                actions = { TextButton(onClick = onSystemSettings) { Text("系统 TTS") } },
            )
        },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 660.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Panel("模型") {
                    Text(
                        model.name ?: "导入 v2Pro 流式声音包以开始",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (progress != null) {
                        val percent = progress.fraction?.let { " · ${(it * 100).toInt()}%" }.orEmpty()
                        Text(progress.label + percent, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        if (progress.fraction != null) {
                            LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    FilledTonalButton(onClick = onPickModel, enabled = !working) {
                        Text(if (model.bundle != null) "更换模型 ZIP" else "导入模型 ZIP")
                    }
                }
                Panel("试听与性能") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SynthesisMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = model.mode == mode,
                                onClick = { onMode(mode) },
                                enabled = !working && model.bundle != null &&
                                    (mode == SynthesisMode.Streaming || model.completeAvailable),
                                shape = SegmentedButtonDefaults.itemShape(index, SynthesisMode.entries.size),
                            ) { Text(mode.label) }
                        }
                    }
                    if (model.bundle != null && !model.completeAvailable) {
                        Text("包含普通 VITS 图的声音包可启用整段合成。", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(TextToSpeech.getMaxSpeechInputLength()) },
                        label = { Text("朗读文本") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("普通话 / 混读", "English").forEachIndexed { index, label ->
                            SegmentedButton(
                                selected = english == (index == 1),
                                onClick = { english = index == 1 },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                            ) { Text(label) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSpeak(text, english) },
                            enabled = !working && canSpeak && text.isNotBlank(),
                        ) { Text("播放") }
                        FilledTonalButton(onClick = onStop, enabled = previewing || message == "正在播放参考音") {
                            Text("停止")
                        }
                    }
                    if (message.isNotEmpty()) {
                        Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    LatestSynthesis(metrics)
                }
                Panel("参考音") {
                    Text(model.reference?.name ?: "选择参考 WAV 并填写转录", style = MaterialTheme.typography.titleSmall)
                    model.reference?.let { voice ->
                        Text(voice.transcript, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onEditReference, enabled = !working && model.bundle != null) {
                            Text("编辑参考音")
                        }
                        if (model.reference != null) {
                            TextButton(onClick = onPlayReference, enabled = !working) { Text("播放参考音") }
                        }
                    }
                    if (model.hasCustomReference && model.bundledReference != null) {
                        TextButton(onClick = onUseBundledReference, enabled = !working) {
                            Text("使用声音包参考音")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LatestSynthesis(metrics: SynthesisMetrics?) {
    if (metrics == null) {
        Text("播放后显示首块 PCM、耗时、音频时长和实时率。", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text("${metrics.origin} · ${metrics.mode.label}", style = MaterialTheme.typography.labelMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Metric("首块 PCM", "${metrics.firstPcmMs} ms", Modifier.weight(1f))
        Metric("合成与交付", "${metrics.deliveryMs} ms", Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Metric("音频时长", String.format(Locale.US, "%.2f s", metrics.audioSeconds), Modifier.weight(1f))
        Metric("实时率 RTF", String.format(Locale.US, "%.2f", metrics.realTimeFactor), Modifier.weight(1f))
    }
}

@Composable
private fun Panel(title: String, body: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            body()
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun ReferenceDialog(
    draft: ReferenceDraft,
    onText: (String) -> Unit,
    onPick: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("参考音") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(draft.name.ifEmpty { "请选择单声道 PCM16 WAV" })
                FilledTonalButton(onClick = onPick) { Text("选择 WAV") }
                OutlinedTextField(
                    value = draft.transcript,
                    onValueChange = onText,
                    label = { Text("参考音转录") },
                    minLines = 3,
                    maxLines = 6,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = draft.name.isNotEmpty() && draft.transcript.isNotBlank()) {
                Text("应用")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
