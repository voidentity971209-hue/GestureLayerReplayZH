package com.example.gesturereplayzh;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class ModelManager {
    private static final String ACTIVE_NAME = "active.tflite";
    private static final String PREVIOUS_NAME = "previous.tflite";

    private ModelManager() {}

    static File activeFile(Context context) {
        return new File(modelDirectory(context), ACTIVE_NAME);
    }

    static File previousFile(Context context) {
        return new File(modelDirectory(context), PREVIOUS_NAME);
    }

    static boolean hasActive(Context context) {
        File file = activeFile(context);
        return file.isFile() && file.length() > 1024L;
    }

    static boolean hasPrevious(Context context) {
        File file = previousFile(context);
        return file.isFile() && file.length() > 1024L;
    }

    static String importModel(Context context, Uri source) throws Exception {
        File directory = modelDirectory(context);
        File staging = new File(directory, "staging.tflite");
        try (InputStream input =
                     context.getContentResolver().openInputStream(source);
             OutputStream output = new FileOutputStream(staging)) {
            if (input == null) {
                throw new IllegalArgumentException("無法讀取所選模型");
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
        if (staging.length() < 1024L) {
            staging.delete();
            throw new IllegalArgumentException("模型檔案太小或不完整");
        }

        String summary;
        boolean labelsVerified = hasExpectedLabelMetadata(staging);
        try (TfliteObjectDetector detector =
                     new TfliteObjectDetector(staging, 1)) {
            summary = detector.modelSummary();
        } catch (Exception error) {
            staging.delete();
            throw new IllegalArgumentException(
                    "模型格式不相容：" + error.getMessage(),
                    error
            );
        }

        File active = activeFile(context);
        File previous = previousFile(context);
        if (active.isFile()) {
            copy(active, previous);
        }
        copy(staging, active);
        staging.delete();
        context.getSharedPreferences("model_settings", Context.MODE_PRIVATE)
                .edit()
                .putString("activeSha256", sha256(active))
                .putLong("activeImportedAt", System.currentTimeMillis())
                .putBoolean("activeLabelsVerified", labelsVerified)
                .apply();
        return summary + (labelsVerified
                ? "\n標籤 metadata 已驗證：pokemon、pokestop、gym、power_spot"
                : "\n未找到標籤 metadata；推論將使用固定的四類順序");
    }

    static boolean restorePrevious(Context context) throws Exception {
        File active = activeFile(context);
        File previous = previousFile(context);
        if (!previous.isFile()) {
            return false;
        }
        File swap = new File(modelDirectory(context), "swap.tflite");
        if (active.isFile()) {
            copy(active, swap);
        }
        copy(previous, active);
        if (swap.isFile()) {
            copy(swap, previous);
            swap.delete();
        }
        try (TfliteObjectDetector ignored =
                     new TfliteObjectDetector(active, 1)) {
            // Validation is the operation.
        }
        return true;
    }

    static void removeActive(Context context) {
        activeFile(context).delete();
    }

    static String describe(Context context) {
        if (!hasActive(context)) {
            return "尚未匯入模型";
        }
        File active = activeFile(context);
        String sha = sha256(active);
        return String.format(
                Locale.TAIWAN,
                "已載入模型：%.1f MB\nSHA-256：%s%s",
                active.length() / 1024f / 1024f,
                sha.length() > 12 ? sha.substring(0, 12) : sha,
                hasPrevious(context) ? "\n可回復上一版模型" : ""
        );
    }

    static String versionToken(Context context) {
        File active = activeFile(context);
        return active.isFile()
                ? active.length() + "-" + active.lastModified()
                : "none";
    }

    private static File modelDirectory(Context context) {
        File directory = new File(context.getFilesDir(), "models");
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return directory;
    }

    private static void copy(File source, File target) throws Exception {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static String sha256(File file) {
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) {
                result.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static boolean hasExpectedLabelMetadata(File file) {
        try (InputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(
                    file.length(),
                    64L * 1024L * 1024L
            )];
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            String raw = new String(
                    data,
                    0,
                    offset,
                    StandardCharsets.ISO_8859_1
            ).toLowerCase(Locale.US);
            return raw.contains("pokemon") && raw.contains("pokestop") &&
                    raw.contains("gym") && raw.contains("power_spot");
        } catch (Exception ignored) {
            return false;
        }
    }
}
