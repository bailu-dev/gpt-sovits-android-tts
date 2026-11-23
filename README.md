# GPT-SoVITS Android TTS

独立 Android 试听 App 和系统文字转语音引擎。输入普通话或英文即可试听，也可在 Android 的
「文字转语音」设置中选择 **GPT-SoVITS TTS**，让使用系统 TTS 的应用调用同一个引擎。

合成在设备 CPU 上执行。Rust 使用 Adaptive 句内流式，边生成语义 token 边生成 PCM。
共享 KV 缓冲减少逐 token 拷贝，已加载的模型及参考音特征可供之后的请求复用。
系统服务接收 32 kHz 单声道 PCM16，支持停止当前合成。普通话、英文和中英混读使用从文本
识别出的发音规则，共用当前参考音。此版本使用模型的默认语速和音高。

## 构建

引擎通过 [`vendor/gpt-sovits-onnx-rs`](vendor/gpt-sovits-onnx-rs/Readme.md) Git 子模块固定到
`b0f2c5a`。`./build.sh` 初始化子模块、编译 Rust JNI 并组装调试 APK。构建需要 JDK 17 或更新版本、
Android SDK 35、NDK r29
（29.0.14206865）、CMake、原生构建工具、Git、`curl`、`unzip` 和带 Android 目标的
稳定版 Rust。目标设备需支持 `arm64-v8a`，系统版本为 Android 9（API 28）或更新版本。

```bash
rustup target add aarch64-linux-android
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_ROOT="$ANDROID_HOME/ndk/29.0.14206865"
./build.sh
```

原生脚本校验并解包全量 ONNX Runtime 1.30.0，将运行库和 JNI 库放入 `.local/jni/`。
已有对应 ORT AAR 时可用 `ORT_ARCHIVE_DIR` 指向归档目录。可用 `TTS_DEBUG_KEYSTORE` 指定
调试签名。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，内含应用、JNI 和运行库，
模型与参考音使用独立 ZIP。完成首次构建后，也可以在 Android Studio 打开此目录，选择 `app` 配置。

## 准备模型 ZIP

下载预导出的 v2Pro `quality` 流式图，或按
[引擎导出说明](vendor/gpt-sovits-onnx-rs/Readme.md#快速开始)生成 `mobile` 流式包。
模型和参考音通过 ZIP 单独导入。建议使用 3–10 秒的单声道 PCM16 WAV，并提供准确的 UTF-8 转录。

```bash
MODEL_DIR="$PWD/.local/v2pro-quality-stream"
uvx --from huggingface_hub hf download mikv39/gpt-sovits-onnx-custom \
  --revision 12cacd4 --local-dir "$MODEL_DIR" --exclude vits_non_streaming.onnx
cp /path/to/ref.wav "$MODEL_DIR/reference.wav"
cp /path/to/transcript.txt "$MODEL_DIR/reference.txt"

ZIP="$PWD/.local/v2pro-demo.zip"
(cd "$MODEL_DIR" && zip -q -r "$ZIP" . -x '.cache/*' '.gitattributes' 'README.md' 'vits_non_streaming.onnx')
```

使用新的 ZIP 文件名。压缩包根目录直接包含 `vits.onnx`、`vits_ref.onnx`、`sv.onnx`、
`ssl.onnx`、`bert.onnx`、`g2pW.onnx`、三个 `t2s_*.onnx`、`g2p_en/` 内的编码器和
解码器，以及 `reference.wav` 和 `reference.txt`。保留 ONNX 外部权重的相对路径。包内
`infer_params.json` 可设置采样参数。更换模型时，设备需容纳新包的解压数据和当前已安装的模型。

## 试听与系统 TTS

设备开启 USB 调试并完成授权后，可以用 SDK Platform-Tools 安装 APK、把模型 ZIP 放进下载目录。
以下命令沿用上面设置的 `ZIP`。

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push "$ZIP" /sdcard/Download/
```

1. 安装 APK 并打开 **GPT-SoVITS TTS**。点击「导入模型 ZIP」，选择上述压缩包。
   App 会在本地打开流式 ONNX 图并处理参考音。显示「模型已就绪」后可输入文字试听。
2. 点击「打开系统 TTS 设置」，在「首选引擎」中选择 **GPT-SoVITS TTS**。
   若设备将入口放在其他位置，可在系统设置中搜索「文字转语音」。语言选择简体中文或英语（美国）。
3. 从系统设置播放示例，或用启用了系统语音引擎的阅读应用朗读短句。试听和系统客户端都通过
   同一 `TextToSpeechService` 请求合成。

导入报缺少文件时，检查 ZIP 是否多包了一层目录。报告缺少 `stream_start_frame` 时，检查
根目录的 `vits.onnx` 是否为流式图。真机诊断可运行 `adb logcat -d -s GptSovitsTts`。

[Android TTS 服务接口](https://developer.android.com/reference/android/speech/tts/TextToSpeechService) ·
[系统 PCM 回调](https://developer.android.com/reference/android/speech/tts/SynthesisCallback) ·
[预导出模型](https://huggingface.co/mikv39/gpt-sovits-onnx-custom)
