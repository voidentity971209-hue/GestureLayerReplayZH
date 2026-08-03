package com.example.gesturereplayzh;

import android.content.Context;
import android.content.SharedPreferences;

final class AutoSettings {
    private static final String PREFS = "auto_operation_settings";
    long scanIntervalMs;
    int recognitionFrameCount;
    int listTapCount;
    long listTapIntervalMs;
    long postTapClassifyDelayMs;
    long rocketTapIntervalMs;
    long beforeCatchMs;
    long afterCatchMs;
    long afterExitMs;
    long groundMoveWaitMs;
    int groundMoveRetries;
    int rocketTapCount;
    float rocketTapXRatio;
    float rocketTapYRatio;
    boolean autoEnabled;
    long unknownTimeoutMs;
    long blockedDurationMs;
    float blockedRadiusRatio;
    int blockedMaxCount;

    static AutoSettings load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit()
                .remove("scanDescription")
                .remove("encounterDescription")
                .remove("rocketDescription")
                .remove("exitDescription")
                .remove("detectorMode")
                .remove("modelConfidenceThreshold")
                .remove("modelMaxResults")
                .remove("modelThreads")
                .remove("collectModelEvents")
                .remove("dataLimitMb")
                .remove("positiveSamplePercent")
                .remove("legacySensitivity")
                .remove("scanRadiusRatio")
                .remove("closeTimeoutMs")
                .remove("allowFallbackExit")
                .remove("exitXRatio")
                .remove("exitYRatio")
                .remove("catchGestureSourceId")
                .apply();
        AutoSettings s = defaults();
        s.scanIntervalMs = readTime(p, "scanIntervalMs", s.scanIntervalMs);
        s.recognitionFrameCount = clamp(p.getInt("recognitionFrameCount", s.recognitionFrameCount), 1, 5);
        s.listTapCount = clamp(p.getInt("listTapCount", s.listTapCount), 1, 4);
        s.listTapIntervalMs = readTime(p, "listTapIntervalMs", s.listTapIntervalMs);
        s.postTapClassifyDelayMs = readTime(p, "postTapClassifyDelayMs", s.postTapClassifyDelayMs);
        s.rocketTapIntervalMs = readTime(p, "rocketTapIntervalMs", s.rocketTapIntervalMs);
        s.beforeCatchMs = readTime(p, "beforeCatchMs", s.beforeCatchMs);
        s.afterCatchMs = readTime(p, "afterCatchMs", s.afterCatchMs);
        s.afterExitMs = readTime(p, "afterExitMs", s.afterExitMs);
        s.groundMoveWaitMs = readTime(p, "groundMoveWaitMs", s.groundMoveWaitMs);
        s.groundMoveRetries = clamp(p.getInt("groundMoveRetries", s.groundMoveRetries), 1, 8);
        s.rocketTapCount = clamp(p.getInt("rocketTapCount", s.rocketTapCount), 1, 5);
        s.rocketTapXRatio = readRatio(p, "rocketTapXRatio", s.rocketTapXRatio);
        s.rocketTapYRatio = readRatio(p, "rocketTapYRatio", s.rocketTapYRatio);
        s.autoEnabled = p.getBoolean("autoEnabled", false);
        s.unknownTimeoutMs = readTime(p, "unknownTimeoutMs", s.unknownTimeoutMs);
        s.blockedDurationMs = readTime(p, "blockedDurationMs", s.blockedDurationMs);
        s.blockedRadiusRatio = clamp(p.getFloat("blockedRadiusRatio", s.blockedRadiusRatio), .01f, .20f);
        s.blockedMaxCount = clamp(p.getInt("blockedMaxCount", s.blockedMaxCount), 1, 200);
        return s;
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("scanIntervalMs", normalizeTime(scanIntervalMs))
                .putInt("recognitionFrameCount", clamp(recognitionFrameCount, 1, 5))
                .putInt("listTapCount", clamp(listTapCount, 1, 4))
                .putLong("listTapIntervalMs", normalizeTime(listTapIntervalMs))
                .putLong("postTapClassifyDelayMs", normalizeTime(postTapClassifyDelayMs))
                .putLong("rocketTapIntervalMs", normalizeTime(rocketTapIntervalMs))
                .putLong("beforeCatchMs", normalizeTime(beforeCatchMs))
                .putLong("afterCatchMs", normalizeTime(afterCatchMs))
                .putLong("afterExitMs", normalizeTime(afterExitMs))
                .putLong("groundMoveWaitMs", normalizeTime(groundMoveWaitMs))
                .putInt("groundMoveRetries", clamp(groundMoveRetries, 1, 8))
                .putInt("rocketTapCount", clamp(rocketTapCount, 1, 5))
                .putFloat("rocketTapXRatio", normalizeRatio(rocketTapXRatio))
                .putFloat("rocketTapYRatio", normalizeRatio(rocketTapYRatio))
                .putBoolean("autoEnabled", autoEnabled)
                .putLong("unknownTimeoutMs", normalizeTime(unknownTimeoutMs))
                .putLong("blockedDurationMs", normalizeTime(blockedDurationMs))
                .putFloat("blockedRadiusRatio", clamp(blockedRadiusRatio, .01f, .20f))
                .putInt("blockedMaxCount", clamp(blockedMaxCount, 1, 200))
                .apply();
    }

    static void reset(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static AutoSettings defaults() {
        AutoSettings s = new AutoSettings();
        s.scanIntervalMs = 300L;
        s.recognitionFrameCount = 1;
        s.listTapCount = 2;
        s.listTapIntervalMs = 300L;
        s.postTapClassifyDelayMs = 700L;
        s.rocketTapIntervalMs = 600L;
        s.beforeCatchMs = 700L;
        s.afterCatchMs = 2000L;
        s.afterExitMs = 1000L;
        s.groundMoveWaitMs = 1200L;
        s.groundMoveRetries = 4;
        s.rocketTapCount = 2;
        s.rocketTapXRatio = .50f;
        s.rocketTapYRatio = .69f;
        s.autoEnabled = false;
        s.unknownTimeoutMs = 15000L;
        s.blockedDurationMs = 60000L;
        s.blockedRadiusRatio = .07f;
        s.blockedMaxCount = 40;
        return s;
    }

    private static long readTime(SharedPreferences p, String key, long fallback) {
        return normalizeTime(p.getLong(key, fallback));
    }

    private static long normalizeTime(long value) {
        return Math.max(100L, Math.min(60000L, Math.round(value / 100f) * 100L));
    }

    private static float readRatio(SharedPreferences p, String key, float fallback) {
        return normalizeRatio(p.getFloat(key, fallback));
    }

    private static float normalizeRatio(float value) {
        return clamp(value, .02f, .98f);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
