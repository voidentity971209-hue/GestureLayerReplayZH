package com.example.gesturereplayzh;

import android.content.Context;

import java.io.File;

final class LegacyModelDataCleanup {
    private static final String PREFS = "legacy_cleanup";
    private static final String KEY = "v17_model_data_removed";

    private LegacyModelDataCleanup() {}

    static void run(Context context) {
        if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY, false)) return;
        deleteTree(new File(context.getFilesDir(), "models"));
        deleteTree(new File(context.getFilesDir(), "model-events"));
        context.getSharedPreferences("model_settings", Context.MODE_PRIVATE)
                .edit().clear().apply();
        context.getSharedPreferences("auto_catch_gesture_snapshot", Context.MODE_PRIVATE)
                .edit().clear().apply();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, true).apply();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
