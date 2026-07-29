package com.example.gesturereplayzh;

import android.graphics.PointF;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class GestureLayer {
    private static final int LEGACY_SOURCE_WIDTH = 1080;
    private static final int LEGACY_SOURCE_HEIGHT = 2340;

    String name;
    long startDelayMs;
    long durationMs;
    int sourceWidth;
    int sourceHeight;
    final List<PointF> points = new ArrayList<>();

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("name", name);
        object.put("startDelayMs", startDelayMs);
        object.put("durationMs", durationMs);
        object.put("sourceWidth", sourceWidth);
        object.put("sourceHeight", sourceHeight);
        JSONArray pointArray = new JSONArray();
        for (PointF point : points) {
            JSONArray pair = new JSONArray();
            pair.put(point.x);
            pair.put(point.y);
            pointArray.put(pair);
        }
        object.put("points", pointArray);
        return object;
    }

    static GestureLayer fromJson(JSONObject object) throws JSONException {
        GestureLayer layer = new GestureLayer();
        layer.name = object.optString("name", "軌跡");
        layer.startDelayMs = Math.max(0L, object.optLong("startDelayMs", 0L));
        layer.durationMs = Math.max(100L, object.optLong("durationMs", 100L));
        layer.sourceWidth = Math.max(
                1,
                object.optInt("sourceWidth", LEGACY_SOURCE_WIDTH)
        );
        layer.sourceHeight = Math.max(
                1,
                object.optInt("sourceHeight", LEGACY_SOURCE_HEIGHT)
        );
        JSONArray pointArray = object.getJSONArray("points");
        for (int i = 0; i < pointArray.length(); i++) {
            JSONArray pair = pointArray.getJSONArray(i);
            layer.points.add(new PointF((float) pair.getDouble(0), (float) pair.getDouble(1)));
        }
        return layer;
    }
}
