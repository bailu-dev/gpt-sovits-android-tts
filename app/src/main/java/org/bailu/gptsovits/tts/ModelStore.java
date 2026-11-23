package org.bailu.gptsovits.tts;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Iterator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ModelStore {
    static final Object ENGINE_LOCK = new Object();
    private static final Object IMPORT_LOCK = new Object();
    private static final long MAX_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 2048;
    private static final String ACTIVE_KEY = "active_bundle";
    private static final String[] REQUIRED = {
        "vits.onnx", "vits_ref.onnx", "ssl.onnx", "sv.onnx", "bert.onnx", "g2pW.onnx",
        "t2s_encoder.onnx", "t2s_fs_decoder.onnx", "t2s_s_decoder.onnx",
        "g2p_en/encoder_model.onnx", "g2p_en/decoder_model.onnx",
        "reference.wav", "reference.txt"
    };

    static File active(Context context) {
        String name = preferences(context).getString(ACTIVE_KEY, null);
        File result = name == null ? null : new File(directory(context), name);
        return result != null && result.isDirectory() ? result : null;
    }

    static void install(Context context, Uri uri) throws IOException {
        synchronized (IMPORT_LOCK) {
            File root = directory(context);
            Files.createDirectories(root.toPath());
            File previous = active(context);
            // Undo staging left behind if the process was killed during an earlier import.
            File[] staged = root.listFiles((dir, name) -> name.startsWith("bundle-"));
            if (staged != null) {
                for (File file : staged) {
                    if (!file.equals(previous)) cleanUp(file);
                }
            }
            File candidate = Files.createTempDirectory(root.toPath(), "bundle-").toFile();
            boolean installed = false;
            try {
                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IOException("无法打开模型 ZIP");
                    unzip(input, candidate.toPath());
                }
                for (String name : REQUIRED) {
                    Path file = candidate.toPath().resolve(name);
                    if (!Files.isRegularFile(file) || Files.size(file) == 0) {
                        throw new IOException("模型包根目录缺少 " + name);
                    }
                }
                if (new File(candidate, "reference.txt").length() > 64 * 1024
                    || new File(candidate, "reference.wav").length() > 16 * 1024 * 1024) {
                    throw new IOException("请使用简短的参考音和转录");
                }
                synchronized (ENGINE_LOCK) {
                    NativeEngine.prepare(context, candidate);
                    if (!preferences(context).edit().putString(ACTIVE_KEY, candidate.getName()).commit()) {
                        preferences(context).edit()
                            .putString(ACTIVE_KEY, previous == null ? null : previous.getName()).commit();
                        throw new IOException("无法保存已选模型");
                    }
                    installed = true;
                    if (previous != null) cleanUp(previous);
                }
            } finally {
                if (!installed) cleanUp(candidate);
            }
        }
    }

    private static void unzip(InputStream input, Path root) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                Path file = root.resolve(name).normalize();
                if (++entries > MAX_ENTRIES || !file.startsWith(root) || file.equals(root)
                    || name.indexOf('\\') >= 0 || name.startsWith("/")) {
                    throw new IOException("模型 ZIP 包含无效路径或过多文件");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(file);
                    if (zip.read() != -1) throw new IOException("ZIP 目录项包含数据");
                } else {
                    Files.createDirectories(file.getParent());
                    try (OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            total += count;
                            if (total > MAX_BYTES) throw new IOException("解压后的模型包超过 8 GiB");
                            output.write(buffer, 0, count);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void cleanUp(File dir) {
        try (Stream<Path> tree = Files.walk(dir.toPath())) {
            Iterator<Path> paths = tree.sorted(Comparator.reverseOrder()).iterator();
            while (paths.hasNext()) Files.deleteIfExists(paths.next());
        } catch (IOException error) {
            Log.w("GptSovitsTts", "Couldn't clean up the staged bundle", error);
        }
    }

    private static File directory(Context context) {
        return new File(context.getFilesDir(), "models");
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("tts_models", Context.MODE_PRIVATE);
    }

    private ModelStore() {}
}
