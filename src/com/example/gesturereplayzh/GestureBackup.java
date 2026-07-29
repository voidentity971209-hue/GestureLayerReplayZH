package com.example.gesturereplayzh;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

final class GestureBackup {
    private GestureBackup() {}

    static JSONObject create(Context context) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", "GestureLayerReplayZH");
        root.put("schemaVersion", 1);
        root.put("exportedAt", System.currentTimeMillis());

        JSONArray activeLayers = new JSONArray();
        for (GestureLayer layer : GestureStore.load(context)) {
            activeLayers.put(layer.toJson());
        }
        root.put("activeLayers", activeLayers);

        JSONArray savedVersions = new JSONArray();
        List<SavedVersionStore.SavedVersion> versions =
                SavedVersionStore.load(context);
        for (SavedVersionStore.SavedVersion version : versions) {
            JSONObject object = new JSONObject();
            object.put("id", version.id);
            object.put("name", version.name);
            object.put("savedAt", version.savedAt);
            JSONArray layers = new JSONArray();
            for (GestureLayer layer : version.layers) {
                layers.put(layer.toJson());
            }
            object.put("layers", layers);
            savedVersions.put(object);
        }
        root.put("savedVersions", savedVersions);
        return root;
    }
}
