package com.example.gesturereplayzh;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class CatchGestureStore {
    private static final String PREFS = "auto_catch_gesture";
    private static final String KEY_LAYERS = "layers";
    private static final String KEY_SCHEMA = "schema";
    private static final int CURRENT_SCHEMA = 2;
    private static final int FIRST_CATCH_LAYER_INDEX = 4;

    private CatchGestureStore() {}

    static void ensureInstalled(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (preferences.contains(KEY_LAYERS) &&
                preferences.getInt(KEY_SCHEMA, 1) >= CURRENT_SCHEMA) {
            return;
        }
        List<GestureLayer> layers = loadBuiltInRemainingLayers(context);
        save(context, layers);
    }

    static List<GestureLayer> load(Context context) {
        ensureInstalled(context);
        List<GestureLayer> result = new ArrayList<>();
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAYERS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                GestureLayer layer = GestureLayer.fromJson(array.getJSONObject(i));
                if (layer.points.size() >= 2) {
                    result.add(layer);
                }
            }
        } catch (Exception ignored) {
            result.clear();
        }
        return result;
    }

    static void save(Context context, List<GestureLayer> layers) {
        JSONArray array = new JSONArray();
        try {
            for (GestureLayer layer : layers) {
                array.put(layer.toJson());
            }
        } catch (Exception ignored) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAYERS, array.toString())
                .putInt(KEY_SCHEMA, CURRENT_SCHEMA)
                .apply();
    }

    static void restoreBuiltIn(Context context) {
        save(context, loadBuiltInRemainingLayers(context));
    }

    private static List<GestureLayer> loadBuiltInRemainingLayers(Context context) {
        List<GestureLayer> result = new ArrayList<>();
        try (
                InputStream input = context.getResources().openRawResource(
                        R.raw.builtin_stationary_speed
                );
                ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            String raw = new String(output.toByteArray(), StandardCharsets.UTF_8);
            if (!raw.isEmpty() && raw.charAt(0) == '\uFEFF') {
                raw = raw.substring(1);
            }
            JSONArray layers = new JSONObject(raw).getJSONArray("layers");
            for (int i = FIRST_CATCH_LAYER_INDEX; i < layers.length(); i++) {
                GestureLayer layer = GestureLayer.fromJson(layers.getJSONObject(i));
                if (layer.points.size() >= 2) {
                    result.add(layer);
                }
            }
            normalizeStartTimes(result);
        } catch (Exception ignored) {
            result.clear();
        }
        return result;
    }

    private static void normalizeStartTimes(List<GestureLayer> layers) {
        if (layers.isEmpty()) {
            return;
        }
        long earliest = Long.MAX_VALUE;
        for (GestureLayer layer : layers) {
            earliest = Math.min(earliest, layer.startDelayMs);
        }
        if (earliest <= 0L || earliest == Long.MAX_VALUE) {
            return;
        }
        for (GestureLayer layer : layers) {
            layer.startDelayMs = Math.max(0L, layer.startDelayMs - earliest);
        }
    }
}
