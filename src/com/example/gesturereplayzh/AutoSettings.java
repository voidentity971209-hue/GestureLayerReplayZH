package com.example.gesturereplayzh;

import android.content.Context;
import android.content.SharedPreferences;

final class AutoSettings {
    private static final String PREFS = "auto_operation_settings";
    private static final String V7_TIMING_MIGRATED = "v7_timing_migrated";
    static final String CURRENT_GESTURE_SOURCE = "current";

    String scanDescription;
    String encounterDescription;
    String rocketDescription;
    String exitDescription;
    long scanIntervalMs;
    long rocketTapIntervalMs;
    long beforeCatchMs;
    long afterCatchMs;
    long afterExitMs;
    int rocketTapCount;
    float rocketTapXRatio;
    float rocketTapYRatio;
    float exitXRatio;
    float exitYRatio;
    String catchGestureSourceId;
    boolean autoEnabled;

    static AutoSettings load(Context context) {
        SharedPreferences values =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AutoSettings settings = defaults();
        settings.scanDescription = values.getString(
                "scanDescription",
                settings.scanDescription
        );
        settings.encounterDescription = values.getString(
                "encounterDescription",
                settings.encounterDescription
        );
        settings.rocketDescription = values.getString(
                "rocketDescription",
                settings.rocketDescription
        );
        settings.exitDescription = values.getString(
                "exitDescription",
                settings.exitDescription
        );
        settings.scanIntervalMs = readTime(
                values,
                "scanIntervalMs",
                settings.scanIntervalMs
        );
        settings.rocketTapIntervalMs = readTime(
                values,
                "rocketTapIntervalMs",
                settings.rocketTapIntervalMs
        );
        settings.beforeCatchMs = readTime(
                values,
                "beforeCatchMs",
                settings.beforeCatchMs
        );
        settings.afterCatchMs = readTime(
                values,
                "afterCatchMs",
                settings.afterCatchMs
        );
        settings.afterExitMs = readTime(
                values,
                "afterExitMs",
                settings.afterExitMs
        );
        settings.rocketTapCount = Math.max(
                1,
                Math.min(5, values.getInt("rocketTapCount", settings.rocketTapCount))
        );
        settings.rocketTapXRatio = readRatio(
                values,
                "rocketTapXRatio",
                settings.rocketTapXRatio
        );
        settings.rocketTapYRatio = readRatio(
                values,
                "rocketTapYRatio",
                settings.rocketTapYRatio
        );
        settings.exitXRatio = readRatio(
                values,
                "exitXRatio",
                settings.exitXRatio
        );
        settings.exitYRatio = readRatio(
                values,
                "exitYRatio",
                settings.exitYRatio
        );
        settings.catchGestureSourceId = values.getString(
                "catchGestureSourceId",
                settings.catchGestureSourceId
        );
        settings.autoEnabled = values.getBoolean(
                "autoEnabled",
                settings.autoEnabled
        );
        if (!values.getBoolean(V7_TIMING_MIGRATED, false)) {
            if (settings.scanIntervalMs == 800L) {
                settings.scanIntervalMs = 300L;
            }
            if (settings.beforeCatchMs == 300L) {
                settings.beforeCatchMs = 700L;
            }
            values.edit()
                    .putLong("scanIntervalMs", settings.scanIntervalMs)
                    .putLong("beforeCatchMs", settings.beforeCatchMs)
                    .putBoolean(V7_TIMING_MIGRATED, true)
                    .apply();
        }
        return settings;
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("scanDescription", scanDescription)
                .putString("encounterDescription", encounterDescription)
                .putString("rocketDescription", rocketDescription)
                .putString("exitDescription", exitDescription)
                .putLong("scanIntervalMs", normalizeTime(scanIntervalMs))
                .putLong("rocketTapIntervalMs", normalizeTime(rocketTapIntervalMs))
                .putLong("beforeCatchMs", normalizeTime(beforeCatchMs))
                .putLong("afterCatchMs", normalizeTime(afterCatchMs))
                .putLong("afterExitMs", normalizeTime(afterExitMs))
                .putInt("rocketTapCount", Math.max(1, Math.min(5, rocketTapCount)))
                .putFloat("rocketTapXRatio", normalizeRatio(rocketTapXRatio))
                .putFloat("rocketTapYRatio", normalizeRatio(rocketTapYRatio))
                .putFloat("exitXRatio", normalizeRatio(exitXRatio))
                .putFloat("exitYRatio", normalizeRatio(exitYRatio))
                .putString(
                        "catchGestureSourceId",
                        catchGestureSourceId == null
                                ? CURRENT_GESTURE_SOURCE
                                : catchGestureSourceId
                )
                .putBoolean("autoEnabled", autoEnabled)
                .apply();
    }

    static void reset(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    private static AutoSettings defaults() {
        AutoSettings settings = new AutoSettings();
        settings.scanDescription = "掃描中央地圖，先處理寶可夢，再處理藍色補給站";
        settings.encounterDescription = "確認捕捉畫面後播放目前套用的捕捉手勢";
        settings.rocketDescription = "火箭隊對話點擊兩次，每次相隔 0.6 秒，再退出";
        settings.exitDescription = "非捕捉畫面等待後按下方 X 返回地圖";
        settings.scanIntervalMs = 300L;
        settings.rocketTapIntervalMs = 600L;
        settings.beforeCatchMs = 700L;
        settings.afterCatchMs = 6500L;
        settings.afterExitMs = 1000L;
        settings.rocketTapCount = 2;
        settings.rocketTapXRatio = 0.50f;
        settings.rocketTapYRatio = 0.69f;
        settings.exitXRatio = 0.50f;
        settings.exitYRatio = 0.938f;
        settings.catchGestureSourceId = CURRENT_GESTURE_SOURCE;
        settings.autoEnabled = false;
        return settings;
    }

    private static long readTime(
            SharedPreferences values,
            String key,
            long fallback
    ) {
        return normalizeTime(values.getLong(key, fallback));
    }

    private static long normalizeTime(long value) {
        return Math.max(100L, Math.min(60000L, Math.round(value / 100f) * 100L));
    }

    private static float readRatio(
            SharedPreferences values,
            String key,
            float fallback
    ) {
        return normalizeRatio(values.getFloat(key, fallback));
    }

    private static float normalizeRatio(float value) {
        return Math.max(0.02f, Math.min(0.98f, value));
    }
}
