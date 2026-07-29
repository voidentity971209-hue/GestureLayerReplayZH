package com.example.gesturereplayzh;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

final class AutoCatchGestureStore {
    private static final String PREFS = "auto_catch_gesture_snapshot";
    private static final String KEY_LAYERS = "layers";
    private static final String KEY_APPLIED_AT = "applied_at";

    private AutoCatchGestureStore() {}

    static boolean saveSnapshot(
            Context context,
            List<GestureLayer> sourceLayers
    ) {
        JSONArray array = new JSONArray();
        try {
            for (GestureLayer layer : sourceLayers) {
                array.put(layer.toJson());
            }
        } catch (JSONException ignored) {
            return false;
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAYERS, array.toString())
                .putLong(KEY_APPLIED_AT, System.currentTimeMillis())
                .commit();
    }

    static List<GestureLayer> load(Context context) {
        List<GestureLayer> result = new ArrayList<>();
        SharedPreferences values =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = values.getString(KEY_LAYERS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                GestureLayer layer =
                        GestureLayer.fromJson(array.getJSONObject(i));
                if (layer.points.size() >= 2) {
                    result.add(layer);
                }
            }
        } catch (JSONException ignored) {
            result.clear();
        }
        return result;
    }

    static long appliedAt(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_APPLIED_AT, 0L);
    }
}
