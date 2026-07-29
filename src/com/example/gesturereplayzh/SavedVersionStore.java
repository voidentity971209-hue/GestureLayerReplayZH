package com.example.gesturereplayzh;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class SavedVersionStore {
    private static final String PREFS = "gesture_layers";
    private static final String KEY_VERSIONS = "saved_versions";

    private SavedVersionStore() {}

    static final class SavedVersion {
        String id;
        String name;
        long savedAt;
        List<GestureLayer> layers = new ArrayList<>();
    }

    static List<SavedVersion> load(Context context) {
        List<SavedVersion> result = new ArrayList<>();
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_VERSIONS, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                SavedVersion version = new SavedVersion();
                version.id = object.optString("id", String.valueOf(i));
                version.name = object.optString("name", "保存版本 " + (i + 1));
                version.savedAt = object.optLong("savedAt", 0L);
                JSONArray layerArray = object.optJSONArray("layers");
                if (layerArray != null) {
                    for (int j = 0; j < layerArray.length(); j++) {
                        GestureLayer layer =
                                GestureLayer.fromJson(layerArray.getJSONObject(j));
                        if (layer.points.size() >= 2) {
                            version.layers.add(layer);
                        }
                    }
                }
                result.add(version);
            }
        } catch (JSONException ignored) {
            result.clear();
        }
        return result;
    }

    static void save(Context context, List<SavedVersion> versions) {
        JSONArray array = new JSONArray();
        try {
            for (SavedVersion version : versions) {
                JSONObject object = new JSONObject();
                object.put("id", version.id);
                object.put("name", version.name);
                object.put("savedAt", version.savedAt);
                JSONArray layerArray = new JSONArray();
                for (GestureLayer layer : version.layers) {
                    layerArray.put(layer.toJson());
                }
                object.put("layers", layerArray);
                array.put(object);
            }
        } catch (JSONException ignored) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_VERSIONS, array.toString())
                .apply();
    }

    static SavedVersion create(
            String name,
            List<GestureLayer> sourceLayers
    ) {
        SavedVersion version = new SavedVersion();
        version.id = String.valueOf(System.currentTimeMillis());
        version.name = name;
        version.savedAt = System.currentTimeMillis();
        version.layers = copyLayers(sourceLayers);
        return version;
    }

    static List<GestureLayer> copyLayers(List<GestureLayer> sourceLayers) {
        List<GestureLayer> result = new ArrayList<>();
        try {
            for (GestureLayer layer : sourceLayers) {
                result.add(GestureLayer.fromJson(layer.toJson()));
            }
        } catch (JSONException ignored) {
            result.clear();
        }
        return result;
    }
}
