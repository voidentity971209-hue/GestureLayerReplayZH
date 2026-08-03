package com.example.gesturereplayzh;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Finds a PGSharp-style vertical nearby list anywhere on screen. */
final class PokemonListDetector {
    static final class Result {
        final RectF panel;
        final List<PointF> candidates;
        final float confidence;

        Result(RectF panel, List<PointF> candidates, float confidence) {
            this.panel = panel;
            this.candidates = candidates;
            this.confidence = confidence;
        }
    }

    private PokemonListDetector() {}

    static Result find(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        PanelCandidate best = null;
        for (float widthRatio = .12f; widthRatio <= .30f; widthRatio += .03f) {
            float panelWidth = w * widthRatio;
            for (float left = 0; left + panelWidth <= w; left += w * .035f) {
                for (float top = h * .10f; top <= h * .58f; top += h * .06f) {
                    for (float bottom = h * .72f; bottom <= h * .96f; bottom += h * .06f) {
                        RectF panel = new RectF(left, top, left + panelWidth, bottom);
                        float score = panelScore(bitmap, panel);
                        if (best == null || score > best.score) {
                            best = new PanelCandidate(panel, score);
                        }
                    }
                }
            }
        }
        if (best == null || best.score < .31f) {
            return new Result(new RectF(), Collections.emptyList(), 0f);
        }
        RectF panel = best.panel;
        List<PointF> points = findRows(bitmap, panel);
        float confidence = Math.min(1f, best.score * .72f +
                Math.min(.28f, points.size() * .055f));
        return new Result(panel, points, confidence);
    }

    private static float panelScore(Bitmap b, RectF panel) {
        float panelLuma = meanLuma(b, panel.left, panel.top, panel.right, panel.bottom);
        float margin = panel.width() * .55f;
        float leftLuma = meanLuma(b, Math.max(0, panel.left - margin), panel.top,
                panel.left, panel.bottom);
        float rightLuma = meanLuma(b, panel.right, panel.top,
                Math.min(b.getWidth(), panel.right + margin), panel.bottom);
        float surround = Math.max(leftLuma, rightLuma);
        float contrast = clamp((surround - panelLuma) / 85f, 0f, 1f);
        float darkness = darkRatio(b, panel.left, panel.top, panel.right, panel.bottom);
        float menu = menuBarsScore(b, panel);
        float radar = circleScore(b, panel.centerX(),
                panel.bottom - b.getHeight() * .045f, b.getWidth() * .055f);
        return contrast * .30f + darkness * .22f + menu * .28f + radar * .20f;
    }

    private static float menuBarsScore(Bitmap b, RectF panel) {
        int h = b.getHeight();
        int start = Math.round(panel.top + h * .01f);
        int end = Math.round(Math.min(panel.bottom, panel.top + h * .14f));
        int half = Math.max(8, Math.round(panel.width() * .32f));
        int gap = Math.max(4, Math.round(h * .009f));
        float best = 0f;
        for (int y = start; y <= end; y += Math.max(3, h / 260)) {
            float first = brightLineRatio(b, panel.centerX(), y, half);
            float second = brightLineRatio(b, panel.centerX(), y + gap, half);
            float third = brightLineRatio(b, panel.centerX(), y + gap * 2, half);
            best = Math.max(best, (first + second + Math.max(second, third)) / 3f);
        }
        return best;
    }

    private static float brightLineRatio(Bitmap b, float centerX, int y, int half) {
        if (y < 0 || y >= b.getHeight()) return 0f;
        int bright = 0;
        int total = 0;
        for (int x = Math.max(0, Math.round(centerX) - half);
             x <= Math.min(b.getWidth() - 1, Math.round(centerX) + half); x += 2) {
            int c = b.getPixel(x, y);
            int max = Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c)));
            int min = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
            if (max > 145 && max - min < 90) bright++;
            total++;
        }
        return bright / (float) Math.max(1, total);
    }

    private static float hamburgerScore(Bitmap b, int cx, int cy) {
        int w = b.getWidth();
        int h = b.getHeight();
        int half = Math.max(10, Math.round(w * .040f));
        int gap = Math.max(4, Math.round(h * .010f));
        float lines = 0f;
        for (int row = -1; row <= 1; row++) {
            int y = cy + row * gap;
            int bright = 0;
            int total = 0;
            for (int x = cx - half; x <= cx + half; x += 2) {
                if (x < 1 || x >= w - 1 || y < 1 || y >= h - 1) continue;
                int c = b.getPixel(x, y);
                int max = Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c)));
                int min = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
                if (max > 155 && max - min < 80) bright++;
                total++;
            }
            lines += bright / (float) Math.max(1, total);
        }
        float dark = darkRatio(b, cx - half * 2, cy - gap * 3,
                cx + half * 2, cy + gap * 3);
        return lines / 3f * .80f + dark * .20f;
    }

    private static List<PointF> findRows(Bitmap b, RectF panel) {
        int h = b.getHeight();
        int start = Math.round(panel.top + h * .09f);
        int end = Math.round(panel.bottom - h * .10f);
        int step = Math.max(4, h / 220);
        List<RowScore> scores = new ArrayList<>();
        for (int y = start; y <= end; y += step) {
            scores.add(new RowScore(y, rowScore(b, panel, y, h * .032f)));
        }
        scores.sort((a, c) -> Float.compare(c.score, a.score));
        List<RowScore> chosen = new ArrayList<>();
        float minDistance = h * .065f;
        for (RowScore row : scores) {
            if (row.score < .105f) break;
            boolean near = false;
            for (RowScore accepted : chosen) {
                if (Math.abs(row.y - accepted.y) < minDistance) {
                    near = true;
                    break;
                }
            }
            if (!near) chosen.add(row);
            if (chosen.size() >= 8) break;
        }
        chosen.sort(Comparator.comparingInt(a -> a.y));
        List<PointF> result = new ArrayList<>();
        for (RowScore row : chosen) {
            result.add(new PointF(panel.centerX(), row.y));
        }
        return result;
    }

    private static float rowScore(Bitmap b, RectF panel, int cy, float halfHeight) {
        int left = Math.max(1, Math.round(panel.left + panel.width() * .10f));
        int right = Math.min(b.getWidth() - 2, Math.round(panel.right - panel.width() * .10f));
        int top = Math.max(1, Math.round(cy - halfHeight));
        int bottom = Math.min(b.getHeight() - 2, Math.round(cy + halfHeight));
        int colorful = 0;
        int edges = 0;
        int total = 0;
        int step = Math.max(2, b.getWidth() / 240);
        for (int y = top; y <= bottom; y += step) {
            for (int x = left; x <= right; x += step) {
                int c = b.getPixel(x, y);
                int max = Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c)));
                int min = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
                if (max - min > 50 && max > 90) colorful++;
                if (difference(c, b.getPixel(Math.min(right, x + step), y)) > 75 ||
                        difference(c, b.getPixel(x, Math.min(bottom, y + step))) > 75) edges++;
                total++;
            }
        }
        return colorful / (float) Math.max(1, total) * .55f +
                edges / (float) Math.max(1, total) * .45f;
    }

    private static float darkRatio(Bitmap b, float lf, float tf, float rf, float bf) {
        int l = Math.max(0, Math.round(lf));
        int t = Math.max(0, Math.round(tf));
        int r = Math.min(b.getWidth() - 1, Math.round(rf));
        int bottom = Math.min(b.getHeight() - 1, Math.round(bf));
        int dark = 0;
        int total = 0;
        int step = Math.max(3, b.getWidth() / 180);
        for (int y = t; y <= bottom; y += step) {
            for (int x = l; x <= r; x += step) {
                int c = b.getPixel(x, y);
                if ((Color.red(c) + Color.green(c) + Color.blue(c)) / 3 < 125) dark++;
                total++;
            }
        }
        return dark / (float) Math.max(1, total);
    }

    private static float meanLuma(Bitmap b, float lf, float tf, float rf, float bf) {
        int l = Math.max(0, Math.round(lf));
        int t = Math.max(0, Math.round(tf));
        int r = Math.min(b.getWidth() - 1, Math.round(rf));
        int bottom = Math.min(b.getHeight() - 1, Math.round(bf));
        long sum = 0;
        int total = 0;
        int step = Math.max(3, b.getWidth() / 180);
        for (int y = t; y <= bottom; y += step) {
            for (int x = l; x <= r; x += step) {
                int c = b.getPixel(x, y);
                sum += Color.red(c) * 3L + Color.green(c) * 6L + Color.blue(c);
                total += 10;
            }
        }
        return sum / (float) Math.max(1, total);
    }

    private static float circleScore(Bitmap b, float cx, float cy, float radius) {
        int bright = 0;
        int total = 32;
        for (int i = 0; i < total; i++) {
            double a = Math.PI * 2 * i / total;
            int x = Math.round(cx + (float) Math.cos(a) * radius);
            int y = Math.round(cy + (float) Math.sin(a) * radius);
            if (x < 0 || x >= b.getWidth() || y < 0 || y >= b.getHeight()) continue;
            int c = b.getPixel(x, y);
            if (Color.red(c) + Color.green(c) + Color.blue(c) > 520) bright++;
        }
        return bright / (float) total;
    }

    private static int difference(int a, int c) {
        return Math.abs(Color.red(a) - Color.red(c)) +
                Math.abs(Color.green(a) - Color.green(c)) +
                Math.abs(Color.blue(a) - Color.blue(c));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class PanelCandidate {
        final RectF panel;
        final float score;
        PanelCandidate(RectF panel, float score) {
            this.panel = new RectF(panel);
            this.score = score;
        }
    }

    private static final class RowScore {
        final int y;
        final float score;
        RowScore(int y, float score) { this.y = y; this.score = score; }
    }
}
