package com.example.gesturereplayzh;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class BuiltInGestureStore {
    private static final String BUILT_IN_ID = "builtin-original-v1";
    private static final String BUILT_IN_NAME = "原版";

    private BuiltInGestureStore() {}

    static void ensureInstalled(Context context) {
        List<SavedVersionStore.SavedVersion> versions =
                SavedVersionStore.load(context);
        for (SavedVersionStore.SavedVersion version : versions) {
            if (BUILT_IN_ID.equals(version.id)
                    || BUILT_IN_NAME.equals(version.name)) {
                return;
            }
        }

        try (
                InputStream input =
                        context.getResources().openRawResource(R.raw.builtin_original);
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
            JSONObject object = new JSONObject(raw);
            JSONArray layerArray = object.getJSONArray("layers");

            SavedVersionStore.SavedVersion version =
                    new SavedVersionStore.SavedVersion();
            version.id = object.optString("id", BUILT_IN_ID);
            version.name = object.optString("name", BUILT_IN_NAME);
            version.savedAt = object.optLong("savedAt", 0L);

            for (int i = 0; i < layerArray.length(); i++) {
                GestureLayer layer =
                        GestureLayer.fromJson(layerArray.getJSONObject(i));
                if (layer.points.size() >= 2) {
                    version.layers.add(layer);
                }
            }

            if (!version.layers.isEmpty()) {
                versions.add(0, version);
                SavedVersionStore.save(context, versions);
            }
        } catch (Exception ignored) {
            // A malformed built-in resource must never block the main app.
        }
    }
}
