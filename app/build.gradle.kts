plugins {
    id("com.android.application") version "8.9.2"
}

android {
    namespace = "org.bailu.gptsovits.tts"
    compileSdk = 35
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "org.bailu.gptsovits.tts"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Rust opens ORT by its absolute path in the installed app's library directory.
    packaging { jniLibs { useLegacyPackaging = true } }
    sourceSets["main"].jniLibs.srcDir(rootProject.file(".local/jni"))
    providers.gradleProperty("ttsDebugKeystore").orNull?.let {
        signingConfigs.getByName("debug").storeFile = rootProject.file(it)
    }
}

tasks.named("preBuild") {
    doFirst {
        val jni = rootProject.file(".local/jni/arm64-v8a")
        listOf("libgpt_sovits_tts.so", "libonnxruntime.so", "libc++_shared.so").forEach {
            check(jni.resolve(it).isFile) { "Missing $it. Run ./build.sh first." }
        }
    }
}
