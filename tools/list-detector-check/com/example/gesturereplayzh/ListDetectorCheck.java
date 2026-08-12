package com.example.gesturereplayzh;

import android.graphics.Bitmap;
import android.graphics.RectF;

import javax.imageio.ImageIO;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ListDetectorCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 5) {
            throw new IllegalArgumentException("image path [left top right bottom]");
        }
        Bitmap bitmap = new Bitmap(ImageIO.read(new File(args[0])));
        RectF locked = args.length == 5 ? new RectF(
                Float.parseFloat(args[1]), Float.parseFloat(args[2]),
                Float.parseFloat(args[3]), Float.parseFloat(args[4])) : null;
        PokemonListDetector.Result result = locked == null
                ? PokemonListDetector.find(bitmap)
                : PokemonListDetector.findLocked(bitmap, locked);
        System.out.println("panel=" + result.panel);
        System.out.println("confidence=" + result.confidence);
        System.out.println("equalsAnchor=" + result.equalsAnchor);
        System.out.println("candidates=" + result.candidates);
        System.out.println("topRows=" + topRows(bitmap, result.panel));
        Method score = PokemonListDetector.class.getDeclaredMethod(
                "panelScore", Bitmap.class, RectF.class);
        score.setAccessible(true);
        if (args.length == 5) {
            System.out.println("probe=" + locked + " score=" +
                    score.invoke(null, bitmap, locked));
        }
    }

    private static List<String> topRows(Bitmap bitmap, RectF panel) throws Exception {
        List<RowProbe> probes = new ArrayList<>();
        Method score = PokemonListDetector.class.getDeclaredMethod(
                "rowScore", Bitmap.class, RectF.class, int.class, float.class);
        score.setAccessible(true);
        Method color = PokemonListDetector.class.getDeclaredMethod(
                "rowColorfulRatio", Bitmap.class, RectF.class,
                int.class, float.class);
        color.setAccessible(true);
        for (int y = Math.round(panel.top); y <= Math.round(panel.bottom); y += 5) {
            float inside = (Float) score.invoke(
                    null, bitmap, panel, y, bitmap.getHeight() * .032f);
            float width = panel.width();
            RectF left = new RectF(
                    Math.max(0, panel.left - width * 1.05f), panel.top,
                    Math.max(0, panel.left - width * .05f), panel.bottom);
            RectF right = new RectF(
                    Math.min(bitmap.getWidth(), panel.right + width * .05f), panel.top,
                    Math.min(bitmap.getWidth(), panel.right + width * 1.05f), panel.bottom);
            float leftScore = left.width() < width * .35f ? 0f :
                    (Float) score.invoke(null, bitmap, left, y, bitmap.getHeight() * .032f);
            float rightScore = right.width() < width * .35f ? 0f :
                    (Float) score.invoke(null, bitmap, right, y, bitmap.getHeight() * .032f);
            float colorful = (Float) color.invoke(
                    null, bitmap, panel, y, bitmap.getHeight() * .032f);
            probes.add(new RowProbe(
                    y, inside, Math.max(leftScore, rightScore), colorful));
        }
        probes.sort(Comparator.comparingDouble((RowProbe p) -> p.score).reversed());
        List<String> result = new ArrayList<>();
        for (int i = 0; i < Math.min(12, probes.size()); i++) {
            result.add(probes.get(i).toString());
        }
        return result;
    }

    private static final class RowProbe {
        final int y;
        final float score;
        final float outside;
        final float colorful;
        RowProbe(int y, float score, float outside, float colorful) {
            this.y = y;
            this.score = score;
            this.outside = outside;
            this.colorful = colorful;
        }
        @Override public String toString() {
            return y + ":in=" + score + ",out=" + outside +
                    ",color=" + colorful;
        }
    }
}
