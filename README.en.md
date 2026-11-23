# GPT-SoVITS ONNX RS Android Demo

[简体中文](README.md) · **English**

This is the Android demo for
[GPT-SoVITS ONNX RS](https://github.com/bailu-obj/gpt-sovits-onnx-rs/blob/main/README.en.md). Enter
text to preview speech, change the reference voice, and compare streaming and
whole-utterance latency. You can also set it as the system TTS engine so
readers and other Android apps use the same model, reference voice, and
synthesis mode. The toolbar's **Upstream Project** (「原项目」) button opens
the inference library repository.

## Run on a phone

You need an arm64 phone running Android 9 or later, plus `adb` and the
Hugging Face CLI (`hf`) on your computer. [Build the APK](#build-the-apk),
download `mobile/` from the
[Hugging Face model repository](https://huggingface.co/mikv39/gpt-sovits-onnx-custom),
and push the downloaded directory to the app's external files directory.

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

1. Open **GPT-SoVITS TTS**. Tap **Choose model folder** (「选择模型文件夹」),
   select `mobile`, and wait for it to load.
2. In **Reference audio** (「参考音」) at the bottom of the page, choose a WAV
   and enter its exact transcript. You can also view and play the current
   reference audio here.
3. In **Preview and performance** (「试听与性能」), enter text, choose streaming
   or whole-utterance mode, and tap **Play** (「播放」). Time to first PCM and
   synthesis time appear below the button.
4. To test system TTS, tap **System TTS** (「系统 TTS」), select **GPT-SoVITS TTS**
   as the preferred engine, and play the sample in Android Settings.

## Build the APK

Install stable Rust, JDK 17 or later, Android SDK Platform 37.0,
Build Tools 37.0.0, NDK r29 (29.0.14206865), CMake, native build tools, Git,
`curl`, and `unzip`. Set `ANDROID_HOME` to your SDK path, then run these
commands from the project root.

```bash
rustup target add aarch64-linux-android
./build.sh
```

The script initializes the Git submodule, builds the JNI library and Kotlin
app, and checks the resulting `app/build/outputs/apk/debug/app-debug.apk`.
Set `ORT_ARCHIVE_DIR` to reuse the ONNX Runtime 1.30.0 AAR, or
`TTS_DEBUG_KEYSTORE` to sign with an existing debug keystore.

## Model directory

The published `mobile/` bundle contains both streaming and whole-utterance
graphs. The app reads the model files directly from the directory pushed
with `adb`. For custom models, follow the
[export guide (Chinese)](https://github.com/bailu-obj/gpt-sovits-onnx-rs/blob/main/scripts/README.md#从权重导出).
Name the streaming graph `vits.onnx` and the whole-utterance graph
`vits_non_streaming.onnx`. Both graphs should come from the same weights.
Preserve the paths of `g2p_en/`, `vits_ref.onnx`, and any external weights
referenced by the models.

Optional files in the model directory include the following.

- `reference.wav` and `reference.txt` provide a reference recording and its
  exact UTF-8 transcript. A 3–10 second mono PCM16 WAV works well to start.
  You can also select the reference audio in the app after loading the model.
- `reference_name.txt` sets the reference voice's display name.
- `infer_params.json` sets sampling parameters. Set a seed here for repeated
  measurements.

Device tests used the v2Pro `mobile` bundle deployed by Bailu for Android,
along with the selected `bailu-09.wav` reference and its transcript.

## Implementation and timing

The UI uses Kotlin and Compose Material 3. The in-app preview and other
Android apps reach the same service through Android's `TextToSpeech` API.
The service calls the Rust engine pinned to `b0f2c5a` through JNI and returns
32 kHz mono PCM16. Streaming starts at 16 semantic tokens. Whole-utterance
mode delivers PCM when the utterance is ready.

The performance card updates when native synthesis completes. **Time to first
PCM** measures from inference start to the first PCM block. **Synthesis time**
ends when inference returns. RTF divides synthesis time by the generated
audio duration. The engine prepares the model and reference audio before
timing starts. PCM conversion and Android playback run on a consumer thread,
while the native engine queues generated PCM and continues inference.
