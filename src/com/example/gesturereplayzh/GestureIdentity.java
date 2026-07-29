package com.example.gesturereplayzh;

import org.json.JSONArray;
import org.json.JSONException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

final class GestureIdentity {
    private GestureIdentity() {}

    static long totalDuration(List<GestureLayer> layers) {
        long result = 0L;
        for (GestureLayer layer : layers) {
            result = Math.max(
                    result,
                    layer.startDelayMs + layer.durationMs
            );
        }
        return result;
    }

    static String fingerprint(List<GestureLayer> layers) {
        JSONArray array = new JSONArray();
        try {
            for (GestureLayer layer : layers) {
                array.put(layer.toJson());
            }
        } catch (JSONException ignored) {
            return "無法計算";
        }
        CRC32 crc = new CRC32();
        byte[] bytes = array.toString().getBytes(StandardCharsets.UTF_8);
        crc.update(bytes, 0, bytes.length);
        return String.format(Locale.ROOT, "%08X", crc.getValue());
    }
}
