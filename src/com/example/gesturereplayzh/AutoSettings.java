package com.example.gesturereplayzh;

import android.content.Context;
import android.content.SharedPreferences;

final class AutoSettings {
    private static final String PREFS = "auto_operation_settings";
    private static final String V7_TIMING_MIGRATED = "v7_timing_migrated";
    private static final String V10_PHONE_PROFILE_MIGRATED =
            "v10_phone_profile_migrated";
    static final String CURRENT_GESTURE_SOURCE = "current";
    static final int DETECTOR_LEGACY = 0;
    static final int DETECTOR_MODEL_PREVIEW = 1;
    static final int DETECTOR_MODEL_VERIFY = 2;

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
    boolean pokemonOnlyMode;
    int detectorMode;
    float modelConfidenceThreshold;
    int modelMaxResults;
    int modelThreads;
    boolean collectModelEvents;
    int dataLimitMb;
    int positiveSamplePercent;

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
        settings.pokemonOnlyMode = values.getBoolean(
                "pokemonOnlyMode",
                settings.pokemonOnlyMode
        );
        settings.detectorMode = Math.max(
                DETECTOR_LEGACY,
                Math.min(
                        DETECTOR_MODEL_VERIFY,
                        values.getInt("detectorMode", settings.detectorMode)
                )
        );
        settings.modelConfidenceThreshold = Math.max(
                0.05f,
                Math.min(
                        0.95f,
                        values.getFloat(
                                "modelConfidenceThreshold",
                                settings.modelConfidenceThreshold
                        )
                )
        );
        settings.modelMaxResults = Math.max(
                1,
                Math.min(
                        20,
                        values.getInt(
                                "modelMaxResults",
                                settings.modelMaxResults
                        )
                )
        );
        settings.modelThreads = Math.max(
                1,
                Math.min(
                        8,
                        values.getInt("modelThreads", settings.modelThreads)
                )
        );
        settings.collectModelEvents = values.getBoolean(
                "collectModelEvents",
                settings.collectModelEvents
        );
        settings.dataLimitMb = Math.max(
                50,
                Math.min(
                        5000,
                        values.getInt("dataLimitMb", settings.dataLimitMb)
                )
        );
        settings.positiveSamplePercent = Math.max(
                0,
                Math.min(
                        100,
                        values.getInt(
                                "positiveSamplePercent",
                                settings.positiveSamplePercent
                        )
                )
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
        if (!values.getBoolean(V10_PHONE_PROFILE_MIGRATED, false)) {
            if (settings.afterCatchMs == 6500L) {
                settings.afterCatchMs = 1200L;
            }
            values.edit()
                    .putLong("afterCatchMs", settings.afterCatchMs)
                    .putBoolean(V10_PHONE_PROFILE_MIGRATED, true)
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
                .putBoolean("pokemonOnlyMode", pokemonOnlyMode)
                .putInt("detectorMode", detectorMode)
                .putFloat(
                        "modelConfidenceThreshold",
                        modelConfidenceThreshold
                )
                .putInt("modelMaxResults", modelMaxResults)
                .putInt("modelThreads", modelThreads)
                .putBoolean("collectModelEvents", collectModelEvents)
                .putInt("dataLimitMb", dataLimitMb)
                .putInt("positiveSamplePercent", positiveSamplePercent)
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
        settings.scanDescription = "三幀快速掃描：優先點擊小型獨立寶可夢候選";
        settings.encounterDescription = "確認捕捉畫面後播放目前套用的捕捉手勢";
        settings.rocketDescription = "火箭隊對話點擊兩次，每次相隔 0.6 秒，再退出";
        settings.exitDescription = "非捕捉畫面等待後按下方 X 返回地圖";
        settings.scanIntervalMs = 200L;
        settings.rocketTapIntervalMs = 600L;
        settings.beforeCatchMs = 700L;
        settings.afterCatchMs = 1200L;
        settings.afterExitMs = 1000L;
        settings.rocketTapCount = 2;
        settings.rocketTapXRatio = 0.50f;
        settings.rocketTapYRatio = 0.69f;
        settings.exitXRatio = 0.50f;
        settings.exitYRatio = 0.938f;
        settings.catchGestureSourceId = CURRENT_GESTURE_SOURCE;
        settings.autoEnabled = false;
        settings.pokemonOnlyMode = true;
        settings.detectorMode = DETECTOR_LEGACY;
        settings.modelConfidenceThreshold = 0.45f;
        settings.modelMaxResults = 8;
        settings.modelThreads = 4;
        settings.collectModelEvents = true;
        settings.dataLimitMb = 500;
        settings.positiveSamplePercent = 20;
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
