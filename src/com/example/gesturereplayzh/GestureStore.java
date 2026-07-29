package com.example.gesturereplayzh;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

final class GestureStore {
    private static final String PREFS = "gesture_layers";
    private static final String KEY_LAYERS = "layers";
    private static final String KEY_LOOP_INTERVAL = "loop_interval";

    private GestureStore() {}

    static List<GestureLayer> load(Context context) {
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
        } catch (JSONException ignored) {
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
        } catch (JSONException ignored) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAYERS, array.toString())
                .apply();
        if (context.getPackageName().endsWith(".autoexperimental")) {
            AutoSettings settings = AutoSettings.load(context);
            settings.catchGestureSourceId =
                    AutoSettings.CURRENT_GESTURE_SOURCE;
            settings.save(context);
        }
    }

    static void append(Context context, GestureLayer layer) {
        List<GestureLayer> layers = load(context);
        layer.name = "軌跡 " + (layers.size() + 1);
        layers.add(layer);
        save(context, layers);
    }

    static long getLoopInterval(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LOOP_INTERVAL, 1000L);
    }

    static void setLoopInterval(Context context, long value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LOOP_INTERVAL, Math.max(200L, value))
                .apply();
    }
}
