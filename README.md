# GPT-SoVITS ONNX RS Android Demo

**简体中文** · [English](README.en.md)

这是 [GPT-SoVITS ONNX RS](https://github.com/bailu-obj/gpt-sovits-onnx-rs) 的 Android 示例，
包含语音试听和系统 TTS 两个入口。可以在 App 里输入文字、
更换参考音，并比较流式和整段合成的延迟。将它设为系统语音引擎后，阅读器等使用 Android TTS
的应用也会使用同一份模型、参考音和合成模式。

## demo视频

https://github.com/user-attachments/assets/e0f6653e-b9c9-4c94-8fee-7a1f60802c83


## 在手机上使用

手机需要 Android 9 或更新版本和 arm64 CPU。开发机需要 `adb` 和 Hugging Face CLI（`hf`）。
先[构建 APK](#构建-apk)，再从
[Hugging Face 模型仓库](https://huggingface.co/mikv39/gpt-sovits-onnx-custom)下载 `mobile/`，
将下载后的整个目录推送到 App 的外部文件目录。

```bash
hf download mikv39/gpt-sovits-onnx-custom \
  --include 'mobile/**' --local-dir models/gpt-sovits

adb install -r app/build/outputs/apk/debug/app-debug.apk
TARGET=/sdcard/Android/data/org.bailu.gptsovits.tts/files/models
adb shell mkdir -p "$TARGET"
adb push -Z models/gpt-sovits/mobile "$TARGET/"
adb shell chmod o+rx "$TARGET"
adb shell chmod -R o+rX "$TARGET/mobile"
```

1. 打开 **GPT-SoVITS TTS**，点「选择模型文件夹」，选择刚推送的 `mobile`，等待模型载入。
2. 在页面底部的「参考音」选择一段 WAV，并填写这段录音的准确转录。这里也可以查看和播放当前参考音。
3. 在「试听与性能」输入文字、选择流式或整段，然后点击「播放」。引擎首块延迟和合成耗时会出现在
   播放按钮下方。
4. 要验证系统调用，点「系统 TTS」，选 **GPT-SoVITS TTS** 为首选引擎，然后在系统设置中播放示例。

## 构建 APK

需要稳定版 Rust、JDK 17 或更新版本、Android SDK Platform 37.0、Build Tools 37.0.0、
NDK r29（29.0.14206865）、CMake、原生构建工具、Git、`curl` 和 `unzip`。
将 `ANDROID_HOME` 指向已安装的 SDK，然后在项目根目录运行以下命令。

```bash
rustup target add aarch64-linux-android
./build.sh
```

脚本初始化 Git 子模块、构建 JNI 与 Kotlin 应用，并检查 APK 内容。产物位于
`app/build/outputs/apk/debug/app-debug.apk`。`ORT_ARCHIVE_DIR` 可复用 ORT 1.30.0 AAR；
`TTS_DEBUG_KEYSTORE` 可沿用已有的调试签名。

## 模型目录

公开的 `mobile/` 已包含流式和整段两种图，App 直接读取推送后的目录。自定义模型可用
[导出工具](https://github.com/bailu-obj/gpt-sovits-onnx-rs/blob/main/scripts/README.md#从权重导出)准备，
以 `vits.onnx` 命名流式图，
以 `vits_non_streaming.onnx` 命名同权重导出的整段图。`g2p_en/`、`vits_ref.onnx`
和模型引用的外部权重按原目录结构放置。

模型目录还支持这几个可选文件。

- `reference.wav` 与 `reference.txt` 为配对的参考音和 UTF-8 转录。建议使用 3–10 秒的
  单声道 PCM16 WAV。也可在载入模型后，从 App 内选择参考音。
- `reference_name.txt` 为参考音的显示名称。
- `infer_params.json` 为采样参数。复测时可在其中固定随机种子。

真机验证使用 Bailu Android 部署的 v2Pro `mobile` 模型，以及筛选后的 `bailu-09.wav` 和配套转录。

## 实现与计时

界面使用 Kotlin 和 Compose Material 3。应用内试听和其他 Android App 都通过系统
`TextToSpeech` 访问同一个服务。服务经 JNI 调用固定到 `b0f2c5a` 的 Rust 引擎，返回
32 kHz 单声道 PCM16。流式模式从 16 个语义 token 起步，整段模式在完成合成后交付 PCM。

性能卡片在原生引擎完成合成时更新。「引擎首块 PCM」从推理开始计到模型生成首块 PCM，
「引擎合成耗时」计到推理返回。推理 RTF 是引擎合成耗时除以生成的 PCM 时长。
模型和参考音准备在计时开始前完成。PCM 转换与 Android 播放在接收线程上进行，
原生引擎将单次请求生成的音频送入独立队列，可在播放期间继续推理。
