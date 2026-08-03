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
        final PointF equalsAnchor;

        Result(RectF panel, List<PointF> candidates, float confidence, PointF equalsAnchor) {
            this.panel = panel;
            this.candidates = candidates;
            this.confidence = confidence;
            this.equalsAnchor = equalsAnchor;
        }

        boolean foundPanel() {
            return panel != null && !panel.isEmpty();
        }

    }

    private PokemonListDetector() {}

    static Result find(Bitmap bitmap) {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        EqualsPair equals = findGlobalEquals(bitmap);
        if (equals == null) {
            return new Result(new RectF(), Collections.emptyList(), 0f, null);
        }
        float panelWidth = clamp(equals.averageLength * 2.45f,
                w * .11f, w * .30f);
        float left = clamp(equals.centerX - panelWidth / 2f,
                0f, w - panelWidth);
        float radarY = findRadarY(bitmap, equals.centerX, equals.bottomY);
        float top = Math.max(0f, equals.topY - h * .060f);
        float bottom = Math.min(h, radarY + h * .080f);
        RectF panel = new RectF(left, top, left + panelWidth, bottom);
        PointF equalsAnchor = new PointF(equals.centerX,
                (equals.topY + equals.bottomY) / 2f);
        List<PointF> points = findRows(bitmap, panel, equalsAnchor);
        float confidence = Math.min(1f, equals.score * .72f +
                Math.min(.28f, points.size() * .055f));
        return new Result(panel, points, confidence, equalsAnchor);
    }

    private static EqualsPair findGlobalEquals(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        int yStep = Math.max(2, h / 520);
        int minimumLength = Math.max(12, Math.round(w * .025f));
        int maximumLength = Math.max(minimumLength, Math.round(w * .20f));
        List<LineRun> runs = new ArrayList<>();
        for (int y = Math.round(h * .015f);
             y <= Math.round(h * .72f);
             y += yStep) {
            int start = -1;
            for (int x = 0; x <= w; x++) {
                boolean bright = x < w && isNeutralBright(b.getPixel(x, y));
                if (bright && start < 0) {
                    start = x;
                } else if (!bright && start >= 0) {
                    int length = x - start;
                    if (length >= minimumLength && length <= maximumLength) {
                        runs.add(new LineRun(y, start, x - 1));
                    }
                    start = -1;
                }
            }
        }
        List<EqualsPair> pairs = new ArrayList<>();
        List<EqualsPair> triples = new ArrayList<>();
        int minimumGap = Math.max(4, Math.round(h * .006f));
        int maximumGap = Math.max(minimumGap, Math.round(h * .040f));
        for (int firstIndex = 0; firstIndex < runs.size(); firstIndex++) {
            LineRun first = runs.get(firstIndex);
            for (int secondIndex = firstIndex + 1;
                 secondIndex < runs.size();
                 secondIndex++) {
                LineRun second = runs.get(secondIndex);
                int gap = second.y - first.y;
                if (gap < minimumGap) continue;
                if (gap > maximumGap) break;
                float overlap = Math.max(0,
                        Math.min(first.right, second.right) -
                                Math.max(first.left, second.left) + 1);
                float overlapRatio = overlap /
                        Math.max(1f, Math.max(first.length(), second.length()));
                if (overlapRatio < .62f) continue;
                float lengthSimilarity = Math.min(first.length(), second.length()) /
                        (float) Math.max(first.length(), second.length());
                float averageLength = (first.length() + second.length()) / 2f;
                float gapRatio = gap / Math.max(1f, averageLength);
                if (gapRatio < .10f || gapRatio > .95f) continue;
                float gapScore = clamp(1f - Math.abs(gapRatio - .40f) / .55f,
                        0f, 1f);
                float centerX = (first.centerX() + second.centerX()) / 2f;
                float localDark = darkRatio(
                        b,
                        centerX - averageLength * .85f,
                        first.y - gap * .70f,
                        centerX + averageLength * .85f,
                        second.y + gap * .70f
                );
                float lineScore = overlapRatio * .34f +
                        lengthSimilarity * .22f + gapScore * .18f +
                        localDark * .26f;
                EqualsPair pair = new EqualsPair(
                        centerX,
                        first.y,
                        second.y,
                        averageLength,
                        lineScore
                );
                pairs.add(pair);
                for (int thirdIndex = secondIndex + 1;
                     thirdIndex < runs.size();
                     thirdIndex++) {
                    LineRun third = runs.get(thirdIndex);
                    int secondGap = third.y - second.y;
                    if (secondGap < minimumGap) continue;
                    if (secondGap > maximumGap) break;
                    float gapSimilarity = Math.min(gap, secondGap) /
                            (float) Math.max(gap, secondGap);
                    if (gapSimilarity < .58f) continue;
                    float thirdOverlap = Math.max(0,
                            Math.min(first.right, third.right) -
                                    Math.max(first.left, third.left) + 1);
                    float thirdOverlapRatio = thirdOverlap /
                            Math.max(1f, Math.max(first.length(), third.length()));
                    float thirdLengthSimilarity = Math.min(first.length(), third.length()) /
                            (float) Math.max(first.length(), third.length());
                    if (thirdOverlapRatio < .58f || thirdLengthSimilarity < .55f) {
                        continue;
                    }
                    float tripleCenter = (first.centerX() + second.centerX() +
                            third.centerX()) / 3f;
                    float tripleLength = (first.length() + second.length() +
                            third.length()) / 3f;
                    triples.add(new EqualsPair(
                            tripleCenter,
                            first.y,
                            third.y,
                            tripleLength,
                            clamp(lineScore * .70f + gapSimilarity * .15f +
                                    thirdOverlapRatio * .15f, 0f, 1f)
                    ));
                }
            }
        }
        pairs.addAll(triples);
        pairs.sort((a, c) -> Float.compare(c.score, a.score));
        EqualsPair best = null;
        float bestScore = 0f;
        int candidateLimit = Math.min(128, pairs.size());
        for (int pairIndex = 0; pairIndex < candidateLimit; pairIndex++) {
            EqualsPair pair = pairs.get(pairIndex);
            float radarScore = radarScoreBelow(b, pair.centerX, pair.bottomY);
            float radarY = findRadarY(b, pair.centerX, pair.bottomY);
            float candidateWidth = clamp(pair.averageLength * 2.45f,
                    w * .11f, w * .30f);
            float candidateLeft = clamp(pair.centerX - candidateWidth / 2f,
                    0f, w - candidateWidth);
            RectF candidatePanel = new RectF(
                    candidateLeft,
                    Math.max(0f, pair.topY - h * .060f),
                    candidateLeft + candidateWidth,
                    Math.min(h, radarY + h * .080f)
            );
            float columnScore = panelScore(b, candidatePanel);
            float spanScore = clamp(
                    (radarY - pair.bottomY) / (h * .72f), 0f, 1f);
            float total = pair.score * .20f + radarScore * .10f +
                    columnScore * .60f + spanScore * .10f;
            if (total > bestScore) {
                bestScore = total;
                best = new EqualsPair(
                        pair.centerX,
                        pair.topY,
                        pair.bottomY,
                        pair.averageLength,
                        total
                );
            }
        }
        return best != null && best.score >= .43f ? best : null;
    }

    private static boolean isNeutralBright(int color) {
        int maximum = Math.max(Color.red(color),
                Math.max(Color.green(color), Color.blue(color)));
        int minimum = Math.min(Color.red(color),
                Math.min(Color.green(color), Color.blue(color)));
        return maximum >= 160 && maximum - minimum <= 82;
    }

    private static float radarScoreBelow(Bitmap b, float centerX, float equalsBottom) {
        int h = b.getHeight();
        float best = 0f;
        float startY = Math.max(equalsBottom + h * .18f, h * .70f);
        for (float y = startY; y <= h * .95f;
             y += Math.max(5f, h / 135f)) {
            for (float radius = b.getWidth() * .040f;
                 radius <= b.getWidth() * .115f;
                 radius += Math.max(4f, b.getWidth() * .012f)) {
                best = Math.max(best, circleScore(b, centerX, y, radius));
            }
        }
        return best;
    }

    private static float findRadarY(Bitmap b, float centerX, float equalsBottom) {
        int h = b.getHeight();
        float bestScore = 0f;
        float bestY = h * .90f;
        float startY = Math.max(equalsBottom + h * .18f, h * .70f);
        for (float y = startY; y <= h * .95f;
             y += Math.max(5f, h / 135f)) {
            float atY = 0f;
            for (float radius = b.getWidth() * .040f;
                 radius <= b.getWidth() * .115f;
                 radius += Math.max(4f, b.getWidth() * .012f)) {
                atY = Math.max(atY, circleScore(b, centerX, y, radius));
            }
            float score = atY + y / h * .035f;
            if (score > bestScore) {
                bestScore = score;
                bestY = y;
            }
        }
        return bestY;
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
        float menu = equalsBarsScore(b, panel);
        float radar = circleScore(b, panel.centerX(),
                panel.bottom - b.getHeight() * .045f, b.getWidth() * .055f);
        float heightCoverage = clamp(panel.height() / b.getHeight(), 0f, 1f);
        float widthCoverage = panel.width() / b.getWidth();
        float shapeScore = widthCoverage < .19f
                ? clamp(1f - Math.abs(heightCoverage - .48f) / .48f, 0f, 1f)
                : heightCoverage;
        return contrast * .24f + darkness * .18f + menu * .23f +
                radar * .15f + shapeScore * .20f;
    }

    private static float equalsBarsScore(Bitmap b, RectF panel) {
        int h = b.getHeight();
        int start = Math.round(panel.top + h * .01f);
        int end = Math.round(Math.min(panel.bottom, panel.top + h * .14f));
        int half = Math.max(8, Math.round(panel.width() * .32f));
        int gap = Math.max(4, Math.round(h * .009f));
        float best = 0f;
        for (int y = start; y <= end; y += Math.max(3, h / 260)) {
            float first = brightLineRatio(b, panel.centerX(), y, half);
            float second = brightLineRatio(b, panel.centerX(), y + gap, half);
            best = Math.max(best, Math.min(first, second));
        }
        return best;
    }

    private static PointF findEqualsAnchor(Bitmap b, RectF panel) {
        int h = b.getHeight();
        int start = Math.round(panel.top + h * .005f);
        int end = Math.round(Math.min(panel.bottom, panel.top + h * .16f));
        int half = Math.max(8, Math.round(panel.width() * .32f));
        int gap = Math.max(4, Math.round(h * .009f));
        float best = 0f;
        int bestY = start;
        for (int y = start; y <= end; y += Math.max(3, h / 260)) {
            float score = Math.min(
                    brightLineRatio(b, panel.centerX(), y, half),
                    brightLineRatio(b, panel.centerX(), y + gap, half)
            );
            if (score > best) {
                best = score;
                bestY = y + gap / 2;
            }
        }
        return best >= .22f ? new PointF(panel.centerX(), bestY) : null;
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

    private static List<PointF> findRows(
            Bitmap b,
            RectF panel,
            PointF equalsAnchor
    ) {
        int h = b.getHeight();
        // The white '=' and the radar icon are permanent controls on the same
        // strip.  They are anchors, never Pokemon and never tap targets.
        int start = Math.round(Math.max(
                panel.top + h * .055f,
                equalsAnchor.y + h * .075f
        ));
        float radarY = panel.bottom - h * .045f;
        int end = Math.round(Math.min(
                panel.bottom - h * .055f,
                radarY - h * .115f
        ));
        if (end <= start) {
            return Collections.emptyList();
        }
        int step = Math.max(4, h / 220);
        List<RowScore> scores = new ArrayList<>();
        for (int y = start; y <= end; y += step) {
            scores.add(new RowScore(
                    y,
                    rowScore(b, panel, y, h * .032f),
                    rowColorfulRatio(b, panel, y, h * .032f)
            ));
        }
        scores.sort((a, c) -> Float.compare(c.score, a.score));
        List<RowScore> chosen = new ArrayList<>();
        float minDistance = h * .065f;
        for (RowScore row : scores) {
            if (row.score < .105f) break;
            if (row.colorfulRatio < .045f) continue;
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
            result.add(new PointF(findRowCenterX(b, panel, row.y), row.y));
        }
        return result;
    }

    private static float findRowCenterX(Bitmap b, RectF panel, int cy) {
        int halfHeight = Math.max(8, Math.round(b.getHeight() * .030f));
        int left = Math.max(1, Math.round(panel.left + panel.width() * .07f));
        int right = Math.min(b.getWidth() - 2,
                Math.round(panel.right - panel.width() * .07f));
        int top = Math.max(1, cy - halfHeight);
        int bottom = Math.min(b.getHeight() - 2, cy + halfHeight);
        int step = Math.max(2, b.getWidth() / 240);
        float weightedX = 0f;
        float weight = 0f;
        for (int y = top; y <= bottom; y += step) {
            for (int x = left; x <= right; x += step) {
                int c = b.getPixel(x, y);
                int max = Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c)));
                int min = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
                int edge = Math.max(
                        difference(c, b.getPixel(Math.min(right, x + step), y)),
                        difference(c, b.getPixel(x, Math.min(bottom, y + step)))
                );
                float pixelWeight = Math.max(0, max - min - 35) +
                        Math.max(0, edge - 45) * .55f;
                weightedX += x * pixelWeight;
                weight += pixelWeight;
            }
        }
        return weight <= 0f ? panel.centerX() : weightedX / weight;
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

    private static float rowColorfulRatio(
            Bitmap b,
            RectF panel,
            int cy,
            float halfHeight
    ) {
        int left = Math.max(1, Math.round(panel.left + panel.width() * .10f));
        int right = Math.min(b.getWidth() - 2,
                Math.round(panel.right - panel.width() * .10f));
        int top = Math.max(1, Math.round(cy - halfHeight));
        int bottom = Math.min(b.getHeight() - 2, Math.round(cy + halfHeight));
        int colorful = 0;
        int total = 0;
        int step = Math.max(2, b.getWidth() / 240);
        for (int y = top; y <= bottom; y += step) {
            for (int x = left; x <= right; x += step) {
                int c = b.getPixel(x, y);
                int max = Math.max(Color.red(c), Math.max(Color.green(c), Color.blue(c)));
                int min = Math.min(Color.red(c), Math.min(Color.green(c), Color.blue(c)));
                if (max - min > 50 && max > 90) colorful++;
                total++;
            }
        }
        return colorful / (float) Math.max(1, total);
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

    private static final class LineRun {
        final int y;
        final int left;
        final int right;
        LineRun(int y, int left, int right) {
            this.y = y;
            this.left = left;
            this.right = right;
        }
        int length() { return right - left + 1; }
        float centerX() { return (left + right) / 2f; }
    }

    private static final class EqualsPair {
        final float centerX;
        final float topY;
        final float bottomY;
        final float averageLength;
        final float score;
        EqualsPair(
                float centerX,
                float topY,
                float bottomY,
                float averageLength,
                float score
        ) {
            this.centerX = centerX;
            this.topY = topY;
            this.bottomY = bottomY;
            this.averageLength = averageLength;
            this.score = score;
        }
    }

    private static final class RowScore {
        final int y;
        final float score;
        final float colorfulRatio;
        RowScore(int y, float score, float colorfulRatio) {
            this.y = y;
            this.score = score;
            this.colorfulRatio = colorfulRatio;
        }
    }
}
