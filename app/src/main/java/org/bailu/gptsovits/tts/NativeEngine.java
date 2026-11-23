package org.bailu.gptsovits.tts;

import android.content.Context;
import java.io.File;

final class NativeEngine {
    static final int SAMPLE_RATE = 32000;

    static {
        System.loadLibrary("gpt_sovits_tts");
    }

    interface PcmSink {
        boolean isCancelled();
        boolean write(byte[] pcm);
    }

    static void prepare(Context context, File bundle) {
        File library = new File(context.getApplicationInfo().nativeLibraryDir, "libonnxruntime.so");
        prepare(bundle.getAbsolutePath(), library.getAbsolutePath());
    }

    private static native void prepare(String bundle, String onnxRuntime);
    static native void stream(String text, PcmSink sink);

    private NativeEngine() {}
}
