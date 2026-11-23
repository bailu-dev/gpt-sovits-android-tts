# GPT-SoVITS Android TTS

这个项目把 [GPT-SoVITS ONNX RS](https://github.com/bailu-obj/gpt-sovits-onnx-rs) 接入 Android。
它既是一个语音试听和性能测试工具，也是一个可供阅读器等 App 调用的 Android 文字转语音引擎。
用途是在手机上验证模型、参考音和合成模式的组合，确认能否满足交互朗读的延迟要求。

## 功能

| 功能 | 用途 |
| --- | --- |
| 文本试听与性能 | 输入文本，切换流式或整段，查看首块 PCM、合成与交付耗时、音频时长和实时率 |
| 参考音管理 | 选择 WAV、编辑对应的转录、查看和播放当前参考音，或切回模型包提供的音色 |
| 系统 TTS | 安装后可选为 Android 首选语音引擎，使用当前的模型、音色和合成模式 |
| 模型导入 | 单独导入模型 ZIP，查看解压和载入状态，在通过校验后启用模型 |

## 技术结构

界面用 Kotlin 和 Jetpack Compose Material 3 实现。应用内试听通过 Android
`TextToSpeech` 访问本应用的 `TextToSpeechService`，因此与系统客户端共用同一条音频路径。

```text
应用内试听 / Android TTS 客户端
    → TextToSpeechService
    → JNI → GPT-SoVITS ONNX RS → ONNX Runtime CPU
    ← 32 kHz 单声道 PCM16
```

Rust 引擎加载文本前端、T2S 和 VITS。参考音和转录用于准备语义提示与音色条件，
T2S 根据请求文本产生语义 token，VITS 将 token 转成波形。流式模式在 T2S 继续解码时按块交付
PCM，整段模式先合成整份波形，再把 PCM 送给 Android 播放或写入文件。

## 模型与音色

Android 部署使用 **v2Pro `mobile`** 配置。Bailu Android 使用量化文本前端和 T2S，以及
Adaptive 流式 VITS。Demo 的流式首块与 Bailu 一样从 16 个语义 token 起步。引擎子模块固定在
`b0f2c5a`，部署模型和参考音以 ZIP 独立分发。

按[引擎导出指南](vendor/gpt-sovits-onnx-rs/scripts/README.md)生成 v2Pro `mobile` 模型和流式包。
将同一套权重导出的普通 VITS 图以 `vits_non_streaming.onnx` 放进流式目录即可启用整段模式。
拆分参考图的两个 VITS 使用相同的 `vits_ref.onnx`。ZIP 根目录布局如下。

```text
vits.onnx
vits_ref.onnx
vits_non_streaming.onnx     可选，用于整段合成
bert.onnx、g2pW.onnx、ssl.onnx、sv.onnx、t2s_*.onnx
g2p_en/                    英文 G2P 模型
infer_params.json          可选，采样参数
reference.wav              可选，模型包提供的参考音
reference.txt              参考音转录，与 reference.wav 成对提供
reference_name.txt         可选，参考音的显示名称
```

ONNX 引用的外部权重文件保留原相对路径。参考音建议使用 3–10 秒的单声道 PCM16 WAV。
项目当前在 S25 Ultra 上的验证使用 Bailu Android 部署模型，以及 Bailu 试听选中的
`bailu-09.wav` 和配套转录。

## 构建与运行

构建需要 JDK 17 或更新版本、Android SDK Platform 37.0、Build Tools 37.0.0、NDK r29
（29.0.14206865）、CMake、原生构建工具、Git、`curl`、`unzip` 和稳定版 Rust。
将 `ANDROID_HOME` 指向 SDK，在项目根目录运行：

```bash
rustup target add aarch64-linux-android
./build.sh
```

脚本初始化 Git 子模块、编译 JNI 和 Kotlin 应用，并检查 APK 的文件清单。产物为
`app/build/outputs/apk/debug/app-debug.apk`。可用 `ORT_ARCHIVE_DIR` 复用 ORT 1.30.0
AAR，或用 `TTS_DEBUG_KEYSTORE` 指定调试签名。

设备需支持 `arm64-v8a` 和 Android 9 或更新版本。先安装 APK，再把声音包传到手机。

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push /path/to/bailu-android-mobile.zip /sdcard/Download/
```

打开 **GPT-SoVITS TTS**，导入 ZIP。包内有参考音时即可开始试听；也可在页面底部的「参考音」
提供自己的 WAV 和转录。要测试系统调用，点「系统 TTS」，将 **GPT-SoVITS TTS** 设为首选引擎，
再用系统的「播放」播放示例。

## 性能口径

「首块 PCM」从服务收到合成请求计到首块交付。「合成与交付」计到最后一块 PCM 交付完成，
包含当次模型准备和 Android 回调耗时。RTF 等于这一耗时除以音频时长。比较两种模式时，
请使用同一参考音、同一输入文本和固定的采样种子。

[Android TTS 服务接口](https://developer.android.com/reference/android/speech/tts/TextToSpeechService) ·
[引擎与模型导出](https://github.com/bailu-obj/gpt-sovits-onnx-rs)
