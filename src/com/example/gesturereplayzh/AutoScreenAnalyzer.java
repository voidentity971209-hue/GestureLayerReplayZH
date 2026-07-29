package com.example.gesturereplayzh;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class AutoScreenAnalyzer {
    private static final float PLAYER_CENTER_X_RATIO = 0.50f;
    private static final float PLAYER_CENTER_Y_RATIO = 0.63f;
    private static final float POKEMON_RADIUS_BY_WIDTH = 0.38f;
    private static final float STOP_RADIUS_BY_WIDTH = 0.40f;

    enum ScreenState {
        ENCOUNTER,
        HAS_CLOSE_BUTTON,
        MAP_RETURNED,
        ROCKET_DIALOG
    }

    enum TargetType {
        POKEMON,
        BLUE_STOP
    }

    static final class TargetCandidate {
        final TargetType type;
        final PointF point;
        final float confidence;

        TargetCandidate(TargetType type, PointF point, float confidence) {
            this.type = type;
            this.point = point;
            this.confidence = confidence;
        }
    }

    static final class FrameSignature {
        private static final int COLUMNS = 18;
        private static final int ROWS = 30;
        final int[] colors = new int[COLUMNS * ROWS];

        FrameSignature(Bitmap bitmap) {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int index = 0;
            for (int row = 0; row < ROWS; row++) {
                int y = Math.min(height - 1, (row * height + height / 2) / ROWS);
                for (int column = 0; column < COLUMNS; column++) {
                    int x = Math.min(
                            width - 1,
                            (column * width + width / 2) / COLUMNS
                    );
                    colors[index++] = bitmap.getPixel(x, y);
                }
            }
        }

        float distance(FrameSignature other) {
            long difference = 0L;
            for (int i = 0; i < colors.length; i++) {
                difference += colorDifference(colors[i], other.colors[i]);
            }
            return difference / (colors.length * 765f);
        }
    }

    private AutoScreenAnalyzer() {}

    static FrameSignature signature(Bitmap bitmap) {
        return new FrameSignature(bitmap);
    }

    static PointF findDrivingConfirmationButton(Bitmap bitmap) {
        RegionStats modal = stats(bitmap, 0.04f, 0.27f, 0.96f, 0.73f);
        RegionStats button = stats(bitmap, 0.14f, 0.63f, 0.86f, 0.69f);
        if (modal.whiteRatio > 0.55f &&
                button.greenRatio > 0.55f &&
                button.redRatio < 0.08f) {
            return new PointF(
                    bitmap.getWidth() * 0.50f,
                    bitmap.getHeight() * 0.66f
            );
        }
        return null;
    }

    static ScreenState classifyAfterTap(
            Bitmap bitmap,
            FrameSignature mapBeforeTap
    ) {
        if (looksLikeEncounter(bitmap)) {
            return ScreenState.ENCOUNTER;
        }
        if (looksLikeMapMenu(bitmap)) {
            return ScreenState.MAP_RETURNED;
        }
        if (looksLikeCloseButton(bitmap)) {
            return ScreenState.HAS_CLOSE_BUTTON;
        }
        if (mapBeforeTap != null &&
                mapBeforeTap.distance(new FrameSignature(bitmap)) < 0.105f) {
            return ScreenState.MAP_RETURNED;
        }
        return ScreenState.ROCKET_DIALOG;
    }

    static TargetCandidate findMapTarget(
            List<Bitmap> frames,
            List<PointF> blockedPoints
    ) {
        if (frames.size() < 3) {
            return null;
        }
        Bitmap first = frames.get(0);
        Bitmap middle = frames.get(frames.size() / 2);
        Bitmap last = frames.get(frames.size() - 1);
        int width = Math.min(
                first.getWidth(),
                Math.min(middle.getWidth(), last.getWidth())
        );
        int height = Math.min(
                first.getHeight(),
                Math.min(middle.getHeight(), last.getHeight())
        );

        Bounds bounds = Bounds.forMap(width, height);
        Shift firstShift = estimateShift(middle, first, bounds);
        Shift lastShift = estimateShift(middle, last, bounds);
        if (firstShift.error > 0.185f || lastShift.error > 0.185f) {
            return null;
        }

        int gridStep = Math.max(3, width / 170);
        int columns = Math.max(1, (bounds.right - bounds.left) / gridStep);
        int rows = Math.max(1, (bounds.bottom - bounds.top) / gridStep);
        int[] differences = new int[columns * rows];
        int differenceCount = 0;
        for (int row = 0; row < rows; row++) {
            int y = bounds.top + row * gridStep;
            for (int column = 0; column < columns; column++) {
                int x = bounds.left + column * gridStep;
                differences[differenceCount++] = alignedDifference(
                        middle,
                        first,
                        last,
                        x,
                        y,
                        firstShift,
                        lastShift
                );
            }
        }
        int[] sortedDifferences = Arrays.copyOf(differences, differenceCount);
        Arrays.sort(sortedDifferences);
        int percentile70 = sortedDifferences[
                Math.min(
                        sortedDifferences.length - 1,
                        Math.round(sortedDifferences.length * 0.70f)
                )
        ];
        int threshold = Math.max(42, Math.min(120, percentile70 + 24));

        boolean[] rawMotion = new boolean[columns * rows];
        for (int row = 0; row < rows; row++) {
            int y = bounds.top + row * gridStep;
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                int x = bounds.left + column * gridStep;
                int edge = edgeStrength(middle, x, y, gridStep);
                int difference = differences[index];
                rawMotion[index] =
                        difference > threshold &&
                                (edge > 18 || difference > threshold * 1.55f);
            }
        }

        boolean[] joinedMotion = dilate(rawMotion, columns, rows);
        List<Component> components = components(
                joinedMotion,
                rawMotion,
                differences,
                columns,
                rows
        );
        TargetCandidate bestPokemon = null;
        TargetCandidate bestBlueStop = null;
        for (Component component : components) {
            TargetCandidate candidate = classifyComponent(
                    middle,
                    component,
                    bounds,
                    gridStep,
                    width,
                    height,
                    blockedPoints,
                    threshold
            );
            if (candidate == null) {
                continue;
            }
            if (candidate.type == TargetType.POKEMON) {
                if (bestPokemon == null ||
                        candidate.confidence > bestPokemon.confidence) {
                    bestPokemon = candidate;
                }
            } else if (bestBlueStop == null ||
                    candidate.confidence > bestBlueStop.confidence) {
                bestBlueStop = candidate;
            }
        }
        return bestPokemon != null ? bestPokemon : bestBlueStop;
    }

    private static TargetCandidate classifyComponent(
            Bitmap bitmap,
            Component component,
            Bounds bounds,
            int gridStep,
            int width,
            int height,
            List<PointF> blockedPoints,
            int threshold
    ) {
        if (component.activeCells < 2) {
            return null;
        }
        int left = bounds.left + component.minColumn * gridStep;
        int right = Math.min(
                bounds.right,
                bounds.left + (component.maxColumn + 1) * gridStep
        );
        int top = bounds.top + component.minRow * gridStep;
        int bottom = Math.min(
                bounds.bottom,
                bounds.top + (component.maxRow + 1) * gridStep
        );
        int objectWidth = right - left;
        int objectHeight = bottom - top;
        int area = objectWidth * objectHeight;
        if (objectWidth < 8 || objectHeight < 8 ||
                objectWidth > width * 0.23f ||
                objectHeight > height * 0.15f ||
                area > width * height * 0.022f) {
            return null;
        }

        float weightedX = bounds.left +
                component.weightedColumn / Math.max(1f, component.weight) *
                        gridStep;
        float weightedY = bounds.top +
                component.weightedRow / Math.max(1f, component.weight) *
                        gridStep;
        weightedY = Math.min(bottom, weightedY + objectHeight * 0.12f);
        if (isBlocked(weightedX, weightedY, width, blockedPoints)) {
            return null;
        }

        VisualStats local = visualStats(
                bitmap,
                Math.max(bounds.left, left - objectWidth / 4),
                Math.max(bounds.top, top - objectHeight / 4),
                Math.min(bounds.right, right + objectWidth / 4),
                Math.min(bounds.bottom, bottom + objectHeight / 4)
        );
        VisualStats surrounding = visualStats(
                bitmap,
                Math.max(bounds.left, left - objectWidth),
                Math.max(bounds.top, top - objectHeight),
                Math.min(bounds.right, right + objectWidth),
                Math.min(bounds.bottom, bottom + objectHeight)
        );

        float boxCells =
                (component.maxColumn - component.minColumn + 1f) *
                        (component.maxRow - component.minRow + 1f);
        float compactness = component.activeCells / Math.max(1f, boxCells);
        boolean flatTerrain =
                local.edgeRatio < 0.075f &&
                        compactness < 0.28f &&
                        area > width * height * 0.0025f;
        boolean largeStructure =
                surrounding.redWhiteRatio > 0.24f &&
                        objectHeight > height * 0.035f;
        boolean purpleStop =
                local.neonMagentaRatio > 0.14f &&
                        objectHeight > height * 0.020f;
        if (flatTerrain || largeStructure || purpleStop) {
            return null;
        }

        float normalizedMotion =
                Math.min(1f, component.averageDifference() / (threshold * 2f));
        float centerDistance = distanceFromCenter(
                weightedX,
                weightedY,
                width,
                height
        );
        float confidence =
                normalizedMotion * 0.35f +
                        local.edgeRatio * 0.25f +
                        Math.min(1f, compactness) * 0.20f +
                        Math.min(1f, local.shadowRatio * 3f) * 0.10f +
                        Math.min(1f, component.activeCells / 18f) * 0.10f -
                        centerDistance * 0.10f;

        boolean blueStop =
                local.cyanRatio > 0.22f &&
                        local.cyanRatio > local.warmRatio * 1.65f &&
                        objectHeight > height * 0.025f;
        float aspectRatio = objectHeight / (float) Math.max(1, objectWidth);
        boolean personLike =
                objectHeight > height * 0.050f &&
                        aspectRatio > 2.15f;
        boolean pokemonLike =
                confidence >= 0.30f &&
                        local.edgeRatio >= 0.10f &&
                        compactness >= 0.18f &&
                        component.activeCells >= 3 &&
                        objectWidth >= width * 0.020f &&
                        objectWidth <= width * 0.15f &&
                        objectHeight >= height * 0.012f &&
                        objectHeight <= height * 0.085f &&
                        !personLike;
        if (!blueStop && !pokemonLike) {
            return null;
        }
        if (blueStop && confidence < 0.25f) {
            return null;
        }
        TargetType type = blueStop ? TargetType.BLUE_STOP : TargetType.POKEMON;
        if (!isWithinPlayerRadius(type, weightedX, weightedY, width, height)) {
            return null;
        }
        return new TargetCandidate(
                type,
                new PointF(weightedX, weightedY),
                confidence
        );
    }

    private static Shift estimateShift(
            Bitmap reference,
            Bitmap other,
            Bounds bounds
    ) {
        Shift best = new Shift(0, 0, Float.MAX_VALUE);
        for (int dy = -12; dy <= 12; dy += 2) {
            for (int dx = -12; dx <= 12; dx += 2) {
                float error = shiftError(reference, other, bounds, dx, dy);
                if (error < best.error) {
                    best = new Shift(dx, dy, error);
                }
            }
        }
        Shift refined = best;
        for (int dy = best.dy - 2; dy <= best.dy + 2; dy++) {
            for (int dx = best.dx - 2; dx <= best.dx + 2; dx++) {
                float error = shiftError(reference, other, bounds, dx, dy);
                if (error < refined.error) {
                    refined = new Shift(dx, dy, error);
                }
            }
        }
        return refined;
    }

    private static float shiftError(
            Bitmap reference,
            Bitmap other,
            Bounds bounds,
            int dx,
            int dy
    ) {
        long difference = 0L;
        int count = 0;
        int step = Math.max(12, reference.getWidth() / 38);
        for (int y = bounds.top + 16; y < bounds.bottom - 16; y += step) {
            int otherY = y + dy;
            if (otherY < 0 || otherY >= other.getHeight()) {
                continue;
            }
            for (int x = bounds.left + 16; x < bounds.right - 16; x += step) {
                int otherX = x + dx;
                if (otherX < 0 || otherX >= other.getWidth()) {
                    continue;
                }
                difference += colorDifference(
                        reference.getPixel(x, y),
                        other.getPixel(otherX, otherY)
                );
                count++;
            }
        }
        return difference / (Math.max(1, count) * 765f);
    }

    private static int alignedDifference(
            Bitmap middle,
            Bitmap first,
            Bitmap last,
            int x,
            int y,
            Shift firstShift,
            Shift lastShift
    ) {
        int reference = middle.getPixel(x, y);
        int firstColor = safePixel(
                first,
                x + firstShift.dx,
                y + firstShift.dy,
                reference
        );
        int lastColor = safePixel(
                last,
                x + lastShift.dx,
                y + lastShift.dy,
                reference
        );
        return Math.max(
                colorDifference(reference, firstColor),
                colorDifference(reference, lastColor)
        );
    }

    private static int safePixel(
            Bitmap bitmap,
            int x,
            int y,
            int fallback
    ) {
        if (x < 0 || y < 0 ||
                x >= bitmap.getWidth() || y >= bitmap.getHeight()) {
            return fallback;
        }
        return bitmap.getPixel(x, y);
    }

    private static int edgeStrength(Bitmap bitmap, int x, int y, int step) {
        int center = bitmap.getPixel(x, y);
        int right = safePixel(bitmap, x + step, y, center);
        int below = safePixel(bitmap, x, y + step, center);
        return (colorDifference(center, right) +
                colorDifference(center, below)) / 2;
    }

    private static boolean[] dilate(
            boolean[] source,
            int columns,
            int rows
    ) {
        boolean[] result = new boolean[source.length];
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                boolean value = false;
                for (int dy = -1; dy <= 1 && !value; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int x = column + dx;
                        int y = row + dy;
                        if (x >= 0 && y >= 0 && x < columns && y < rows &&
                                source[y * columns + x]) {
                            value = true;
                            break;
                        }
                    }
                }
                result[row * columns + column] = value;
            }
        }
        return result;
    }

    private static List<Component> components(
            boolean[] joined,
            boolean[] raw,
            int[] differences,
            int columns,
            int rows
    ) {
        List<Component> result = new ArrayList<>();
        boolean[] visited = new boolean[joined.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int index = 0; index < joined.length; index++) {
            if (!joined[index] || visited[index]) {
                continue;
            }
            Component component = new Component();
            visited[index] = true;
            queue.add(index);
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                int row = current / columns;
                int column = current % columns;
                component.include(
                        column,
                        row,
                        raw[current],
                        differences[current]
                );
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int x = column + dx;
                        int y = row + dy;
                        if (x < 0 || y < 0 || x >= columns || y >= rows) {
                            continue;
                        }
                        int next = y * columns + x;
                        if (joined[next] && !visited[next]) {
                            visited[next] = true;
                            queue.add(next);
                        }
                    }
                }
            }
            result.add(component);
        }
        return result;
    }

    private static VisualStats visualStats(
            Bitmap bitmap,
            int left,
            int top,
            int right,
            int bottom
    ) {
        int step = Math.max(2, bitmap.getWidth() / 240);
        int total = 0;
        int cyan = 0;
        int neonMagenta = 0;
        int warm = 0;
        int redWhite = 0;
        int edge = 0;
        int shadow = 0;
        for (int y = top; y < bottom; y += step) {
            for (int x = left; x < right; x += step) {
                int color = bitmap.getPixel(x, y);
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                total++;
                if (b > 145 && g > 135 && b > r * 1.16f && g > r * 1.10f) {
                    cyan++;
                }
                if (r > 160 && b > 145 && g < 155 &&
                        r > g * 1.10f && b > g * 1.04f) {
                    neonMagenta++;
                }
                if (r > 135 && r > b * 1.16f && g > b * 0.80f) {
                    warm++;
                }
                boolean strongRed =
                        r > 175 && r > g * 1.42f && r > b * 1.32f;
                boolean nearWhite =
                        r > 205 && g > 205 && b > 205 && max - min < 30;
                if (strongRed || nearWhite) {
                    redWhite++;
                }
                if (edgeStrength(bitmap, x, y, step) > 42) {
                    edge++;
                }
                if (max < 92 && max - min < 38) {
                    shadow++;
                }
            }
        }
        return new VisualStats(
                cyan / (float) Math.max(1, total),
                neonMagenta / (float) Math.max(1, total),
                warm / (float) Math.max(1, total),
                redWhite / (float) Math.max(1, total),
                edge / (float) Math.max(1, total),
                shadow / (float) Math.max(1, total)
        );
    }

    private static boolean looksLikeEncounter(Bitmap bitmap) {
        RegionStats ball = stats(bitmap, 0.27f, 0.79f, 0.73f, 0.995f);
        RegionStats sideButtons = stats(bitmap, 0.03f, 0.82f, 0.97f, 0.97f);
        return ball.redRatio > 0.105f &&
                ball.whiteRatio > 0.10f &&
                sideButtons.whiteRatio > 0.075f;
    }

    private static boolean looksLikeCloseButton(Bitmap bitmap) {
        RegionStats centerBottom =
                stats(bitmap, 0.43f, 0.885f, 0.57f, 0.995f);
        return centerBottom.redRatio < 0.10f &&
                (
                        centerBottom.whiteRatio > 0.025f &&
                                centerBottom.blueRatio > 0.022f ||
                                centerBottom.blueRatio > 0.16f
                );
    }

    private static boolean looksLikeMapMenu(Bitmap bitmap) {
        RegionStats centerBottom =
                stats(bitmap, 0.40f, 0.88f, 0.60f, 0.99f);
        return centerBottom.redRatio > 0.080f &&
                centerBottom.whiteRatio > 0.050f;
    }

    private static RegionStats stats(
            Bitmap bitmap,
            float left,
            float top,
            float right,
            float bottom
    ) {
        int startX = Math.max(0, Math.round(bitmap.getWidth() * left));
        int endX = Math.min(bitmap.getWidth(), Math.round(bitmap.getWidth() * right));
        int startY = Math.max(0, Math.round(bitmap.getHeight() * top));
        int endY = Math.min(bitmap.getHeight(), Math.round(bitmap.getHeight() * bottom));
        int step = Math.max(2, Math.min(bitmap.getWidth(), bitmap.getHeight()) / 240);
        int total = 0;
        int red = 0;
        int white = 0;
        int blue = 0;
        int green = 0;
        for (int y = startY; y < endY; y += step) {
            for (int x = startX; x < endX; x += step) {
                int color = bitmap.getPixel(x, y);
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                total++;
                if (r > 145 && r > g * 1.35f && r > b * 1.25f) {
                    red++;
                }
                if (r > 205 && g > 205 && b > 205 && max - min < 28) {
                    white++;
                }
                if (b > 120 && b > r * 1.08f && b >= g * 0.82f) {
                    blue++;
                }
                if (g > 145 && g > r * 1.10f && g > b * 1.05f) {
                    green++;
                }
            }
        }
        return new RegionStats(total, red, white, blue, green);
    }

    private static boolean isBlocked(
            float x,
            float y,
            int width,
            List<PointF> blockedPoints
    ) {
        float radius = width * 0.10f;
        float radiusSquared = radius * radius;
        for (PointF point : blockedPoints) {
            float dx = x - point.x;
            float dy = y - point.y;
            if (dx * dx + dy * dy < radiusSquared) {
                return true;
            }
        }
        return false;
    }

    private static float distanceFromCenter(
            float x,
            float y,
            int width,
            int height
    ) {
        float dx = x - width * PLAYER_CENTER_X_RATIO;
        float dy = y - height * PLAYER_CENTER_Y_RATIO;
        return (float) Math.sqrt(dx * dx + dy * dy) /
                Math.max(1f, width * STOP_RADIUS_BY_WIDTH);
    }

    private static boolean isWithinPlayerRadius(
            TargetType type,
            float x,
            float y,
            int width,
            int height
    ) {
        float radius = width * (
                type == TargetType.POKEMON
                        ? POKEMON_RADIUS_BY_WIDTH
                        : STOP_RADIUS_BY_WIDTH
        );
        float dx = x - width * PLAYER_CENTER_X_RATIO;
        float dy = y - height * PLAYER_CENTER_Y_RATIO;
        return dx * dx + dy * dy <= radius * radius;
    }

    private static int colorDifference(int first, int second) {
        return Math.abs(Color.red(first) - Color.red(second)) +
                Math.abs(Color.green(first) - Color.green(second)) +
                Math.abs(Color.blue(first) - Color.blue(second));
    }

    private static final class Bounds {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        static Bounds forMap(int width, int height) {
            int radius = Math.round(width * STOP_RADIUS_BY_WIDTH);
            int centerX = Math.round(width * PLAYER_CENTER_X_RATIO);
            int centerY = Math.round(height * PLAYER_CENTER_Y_RATIO);
            return new Bounds(
                    Math.max(Math.round(width * 0.05f), centerX - radius),
                    Math.max(Math.round(height * 0.13f), centerY - radius),
                    Math.min(Math.round(width * 0.84f), centerX + radius),
                    Math.min(Math.round(height * 0.86f), centerY + radius)
            );
        }
    }

    private static final class Shift {
        final int dx;
        final int dy;
        final float error;

        Shift(int dx, int dy, float error) {
            this.dx = dx;
            this.dy = dy;
            this.error = error;
        }
    }

    private static final class Component {
        int minColumn = Integer.MAX_VALUE;
        int maxColumn = Integer.MIN_VALUE;
        int minRow = Integer.MAX_VALUE;
        int maxRow = Integer.MIN_VALUE;
        int activeCells;
        long differenceTotal;
        float weightedColumn;
        float weightedRow;
        float weight;

        void include(int column, int row, boolean active, int difference) {
            minColumn = Math.min(minColumn, column);
            maxColumn = Math.max(maxColumn, column);
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
            if (active) {
                activeCells++;
                float cellWeight = Math.max(1f, difference);
                weightedColumn += column * cellWeight;
                weightedRow += row * cellWeight;
                weight += cellWeight;
                differenceTotal += difference;
            }
        }

        float averageDifference() {
            return differenceTotal / (float) Math.max(1, activeCells);
        }
    }

    private static final class VisualStats {
        final float cyanRatio;
        final float neonMagentaRatio;
        final float warmRatio;
        final float redWhiteRatio;
        final float edgeRatio;
        final float shadowRatio;

        VisualStats(
                float cyanRatio,
                float neonMagentaRatio,
                float warmRatio,
                float redWhiteRatio,
                float edgeRatio,
                float shadowRatio
        ) {
            this.cyanRatio = cyanRatio;
            this.neonMagentaRatio = neonMagentaRatio;
            this.warmRatio = warmRatio;
            this.redWhiteRatio = redWhiteRatio;
            this.edgeRatio = edgeRatio;
            this.shadowRatio = shadowRatio;
        }
    }

    private static final class RegionStats {
        final float redRatio;
        final float whiteRatio;
        final float blueRatio;
        final float greenRatio;

        RegionStats(int total, int red, int white, int blue, int green) {
            int safeTotal = Math.max(1, total);
            redRatio = red / (float) safeTotal;
            whiteRatio = white / (float) safeTotal;
            blueRatio = blue / (float) safeTotal;
            greenRatio = green / (float) safeTotal;
        }
    }
}
