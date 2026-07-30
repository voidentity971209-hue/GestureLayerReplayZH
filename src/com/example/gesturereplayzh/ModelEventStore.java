package com.example.gesturereplayzh;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ModelEventStore {
    static final String REVIEW_PENDING = "pending";
    private static final Random RANDOM = new Random();

    static final class Event {
        final File directory;
        final JSONObject json;

        Event(File directory, JSONObject json) {
            this.directory = directory;
            this.json = json;
        }

        String id() {
            return json.optString("id", directory.getName());
        }

        File beforeImage() {
            return new File(directory, "before.jpg");
        }

        RectF box() {
            return new RectF(
                    (float) json.optDouble("left", 0.4),
                    (float) json.optDouble("top", 0.4),
                    (float) json.optDouble("right", 0.6),
                    (float) json.optDouble("bottom", 0.6)
            );
        }
    }

    private ModelEventStore() {}

    static String begin(
            Context context,
            Bitmap screen,
            TfliteObjectDetector.Detection detection,
            AutoSettings settings
    ) {
        try {
            if (directorySize(root(context)) >=
                    settings.dataLimitMb * 1024L * 1024L) {
                return null;
            }
            Bitmap roi = TfliteObjectDetector.cropTrainingRoi(screen);
            long hash = averageHash(roi);
            if (isRecentDuplicate(context, hash)) {
                roi.recycle();
                return null;
            }
            String id = String.format(
                    Locale.US,
                    "%d-%04d",
                    System.currentTimeMillis(),
                    RANDOM.nextInt(10000)
            );
            File directory = new File(root(context), id);
            if (!directory.mkdirs()) {
                roi.recycle();
                return null;
            }
            saveJpeg(roi, new File(directory, "before.jpg"), 88);
            int imageWidth = roi.getWidth();
            int imageHeight = roi.getHeight();
            roi.recycle();

            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("createdAt", System.currentTimeMillis());
            json.put("modelVersion", ModelManager.versionToken(context));
            json.put("predictedClass", detection.label);
            json.put("score", detection.score);
            json.put("left", detection.roiBox.left);
            json.put("top", detection.roiBox.top);
            json.put("right", detection.roiBox.right);
            json.put("bottom", detection.roiBox.bottom);
            json.put("imageWidth", imageWidth);
            json.put("imageHeight", imageHeight);
            json.put("imageHash", Long.toUnsignedString(hash));
            json.put("automaticOutcome", "waiting");
            json.put("review", REVIEW_PENDING);
            writeJson(directory, json);
            return id;
        } catch (Exception ignored) {
            return null;
        }
    }

    static void complete(
            Context context,
            String eventId,
            Bitmap afterScreen,
            String outcome,
            AutoSettings settings
    ) {
        if (eventId == null) {
            return;
        }
        File directory = new File(root(context), eventId);
        try {
            JSONObject json = readJson(directory);
            if (json == null) {
                return;
            }
            if ("encounter".equals(outcome) &&
                    RANDOM.nextInt(100) >= settings.positiveSamplePercent) {
                deleteTree(directory);
                return;
            }
            Bitmap after = Bitmap.createScaledBitmap(
                    afterScreen,
                    Math.max(1, afterScreen.getWidth() / 2),
                    Math.max(1, afterScreen.getHeight() / 2),
                    true
            );
            saveJpeg(after, new File(directory, "after.jpg"), 70);
            after.recycle();
            json.put("automaticOutcome", outcome);
            json.put("completedAt", System.currentTimeMillis());
            writeJson(directory, json);
        } catch (Exception ignored) {
            // A failed training sample must never interrupt automatic control.
        }
    }

    static List<Event> loadEvents(Context context, boolean onlyPending) {
        List<Event> result = new ArrayList<>();
        File[] directories = root(context).listFiles(File::isDirectory);
        if (directories == null) {
            return result;
        }
        Arrays.sort(
                directories,
                Comparator.comparingLong(File::lastModified).reversed()
        );
        for (File directory : directories) {
            JSONObject json = readJson(directory);
            if (json == null ||
                    "waiting".equals(json.optString("automaticOutcome"))) {
                continue;
            }
            if (onlyPending &&
                    !REVIEW_PENDING.equals(json.optString("review"))) {
                continue;
            }
            result.add(new Event(directory, json));
        }
        return result;
    }

    static void saveReview(
            Event event,
            String label,
            RectF box
    ) throws Exception {
        event.json.put("review", label);
        event.json.put("left", clamp(box.left));
        event.json.put("top", clamp(box.top));
        event.json.put("right", clamp(box.right));
        event.json.put("bottom", clamp(box.bottom));
        event.json.put("reviewedAt", System.currentTimeMillis());
        writeJson(event.directory, event.json);
    }

    static void deleteEvent(Event event) {
        deleteTree(event.directory);
    }

    static void discardIncomplete(Context context, String eventId) {
        if (eventId != null) {
            deleteTree(new File(root(context), eventId));
        }
    }

    static int pendingCount(Context context) {
        return loadEvents(context, true).size();
    }

    static long storageBytes(Context context) {
        return directorySize(root(context));
    }

    static void exportReviewed(Context context, OutputStream destination)
            throws Exception {
        List<Event> all = loadEvents(context, false);
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            StringBuilder csv = new StringBuilder(
                    "filename,width,height,class,xmin,ymin,xmax,ymax," +
                            "automatic_outcome,score,model_version\n"
            );
            StringBuilder jsonLines = new StringBuilder();
            for (Event event : all) {
                String review = event.json.optString(
                        "review",
                        REVIEW_PENDING
                );
                if (REVIEW_PENDING.equals(review) ||
                        "unknown".equals(review) ||
                        "box_wrong".equals(review)) {
                    continue;
                }
                File image = event.beforeImage();
                if (!image.isFile()) {
                    continue;
                }
                String filename = event.id() + ".jpg";
                addFile(zip, image, "images/" + filename);
                int width = event.json.optInt("imageWidth", 1);
                int height = event.json.optInt("imageHeight", 1);
                RectF box = event.box();
                String className =
                        "background".equals(review) ? "" : review;
                csv.append(filename).append(',')
                        .append(width).append(',')
                        .append(height).append(',')
                        .append(className).append(',');
                if (className.isEmpty()) {
                    csv.append(",,,,");
                } else {
                    csv.append(Math.round(box.left * width)).append(',')
                            .append(Math.round(box.top * height)).append(',')
                            .append(Math.round(box.right * width)).append(',')
                            .append(Math.round(box.bottom * height)).append(',');
                }
                csv.append(event.json.optString("automaticOutcome")).append(',')
                        .append(event.json.optDouble("score")).append(',')
                        .append(event.json.optString("modelVersion"))
                        .append('\n');
                jsonLines.append(event.json.toString()).append('\n');
            }
            addText(zip, "annotations.csv", csv.toString());
            addText(zip, "events.jsonl", jsonLines.toString());
            addText(
                    zip,
                    "labels.txt",
                    "pokemon\npokestop\ngym\npower_spot\n"
            );
            addText(
                    zip,
                    "README.txt",
                    "圖片均為 App 實際推論使用的固定 ROI。\n" +
                            "class 空白代表沒有任何可標註目標的負樣本。\n" +
                            "unknown 與 box_wrong 不會匯出為訓練標註。\n"
            );
        }
    }

    private static File root(Context context) {
        File directory = new File(context.getFilesDir(), "model-events");
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return directory;
    }

    private static boolean isRecentDuplicate(Context context, long hash) {
        List<Event> events = loadEvents(context, false);
        int checked = 0;
        for (Event event : events) {
            if (checked++ >= 60) {
                break;
            }
            try {
                long other = Long.parseUnsignedLong(
                        event.json.optString("imageHash", "0")
                );
                if (Long.bitCount(hash ^ other) <= 3) {
                    return true;
                }
            } catch (Exception ignored) {
                // Keep comparing other events.
            }
        }
        return false;
    }

    private static long averageHash(Bitmap bitmap) {
        Bitmap sample = Bitmap.createScaledBitmap(bitmap, 8, 8, true);
        int[] values = new int[64];
        long total = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int color = sample.getPixel(x, y);
                int gray = (
                        ((color >> 16) & 0xff) * 30 +
                                ((color >> 8) & 0xff) * 59 +
                                (color & 0xff) * 11
                ) / 100;
                values[y * 8 + x] = gray;
                total += gray;
            }
        }
        sample.recycle();
        int average = (int) (total / 64L);
        long result = 0L;
        for (int i = 0; i < 64; i++) {
            if (values[i] >= average) {
                result |= 1L << i;
            }
        }
        return result;
    }

    private static void saveJpeg(Bitmap bitmap, File file, int quality)
            throws Exception {
        try (OutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                throw new IllegalStateException("JPEG 壓縮失敗");
            }
        }
    }

    private static JSONObject readJson(File directory) {
        File file = new File(directory, "event.json");
        if (!file.isFile()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            return new JSONObject(content.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeJson(File directory, JSONObject json)
            throws Exception {
        try (FileWriter writer =
                     new FileWriter(new File(directory, "event.json"))) {
            writer.write(json.toString(2));
        }
    }

    private static void addFile(
            ZipOutputStream zip,
            File file,
            String path
    ) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                zip.write(buffer, 0, read);
            }
        }
        zip.closeEntry();
    }

    private static void addText(
            ZipOutputStream zip,
            String path,
            String value
    ) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static long directorySize(File file) {
        if (file == null || !file.exists()) {
            return 0L;
        }
        if (file.isFile()) {
            return file.length();
        }
        long total = 0L;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                total += directorySize(child);
            }
        }
        return total;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
        }
        file.delete();
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
