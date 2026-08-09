package com.example.gesturereplayzh;

import android.graphics.Bitmap;

import javax.imageio.ImageIO;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ScreenStateCheck {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("image path [...]");
        }
        for (String path : args) {
            Bitmap bitmap = new Bitmap(ImageIO.read(new File(path)));
            System.out.println(path + "=" +
                    AutoScreenAnalyzer.classifyAfterTap(
                            bitmap, null, new AutoSettings()) +
                    " context=" + invokeBoolean("looksLikeEncounterContext", bitmap) +
                    " docks=" + invokeBoolean("looksLikeEncounterSideDocks", bitmap) +
                    " map=" + invokeBoolean("looksLikeMapScreen", bitmap) +
                    " mapRelaxed=" + invokeBoolean("looksLikeMapScreenRelaxed", bitmap) +
                    " bigBall=" + invokeFloat("findEncounterBallCircleScore", bitmap) +
                    " bigBallBest=" + bestBall(bitmap) +
                    " smallBall=" + invokeFloat("findMapMenuBallCircleScore", bitmap) +
                    " camera=" + stats(bitmap, .38f, .035f, .62f, .15f) +
                    " cp=" + stats(bitmap, .16f, .24f, .84f, .44f));
        }
    }

    private static boolean invokeBoolean(String name, Bitmap bitmap) throws Exception {
        Method method = AutoScreenAnalyzer.class.getDeclaredMethod(name, Bitmap.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, bitmap);
    }

    private static float invokeFloat(String name, Bitmap bitmap) throws Exception {
        Method method = AutoScreenAnalyzer.class.getDeclaredMethod(name, Bitmap.class);
        method.setAccessible(true);
        return (Float) method.invoke(null, bitmap);
    }

    private static String stats(
            Bitmap bitmap, float left, float top, float right, float bottom
    ) throws Exception {
        Method method = AutoScreenAnalyzer.class.getDeclaredMethod(
                "stats", Bitmap.class, float.class, float.class,
                float.class, float.class);
        method.setAccessible(true);
        Object value = method.invoke(null, bitmap, left, top, right, bottom);
        return "{w=" + field(value, "whiteRatio") +
                ",e=" + field(value, "edgeRatio") +
                ",d=" + field(value, "darkRatio") + "}";
    }

    private static float field(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getFloat(value);
    }

    private static String bestBall(Bitmap bitmap) throws Exception {
        Method method = AutoScreenAnalyzer.class.getDeclaredMethod(
                "circleBoundaryScore", Bitmap.class, float.class,
                float.class, float.class);
        method.setAccessible(true);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float best = 0f;
        int bestY = 0;
        int bestRadius = 0;
        for (int y = Math.round(height * .82f); y <= Math.round(height * .975f);
             y += Math.max(5, height / 120)) {
            for (int radius = Math.max(12, Math.round(width * .105f));
                 radius <= Math.max(12, Math.round(width * .235f));
                 radius += Math.max(4, width / 100)) {
                float score = (Float) method.invoke(
                        null, bitmap, width * .50f, (float) y, (float) radius);
                if (score > best) {
                    best = score;
                    bestY = y;
                    bestRadius = radius;
                }
            }
        }
        return "{s=" + best + ",y=" + bestY / (float) height +
                ",r=" + bestRadius / (float) width + "}";
    }
}
