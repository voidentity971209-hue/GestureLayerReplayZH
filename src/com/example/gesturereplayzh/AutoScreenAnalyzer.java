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
        ENCOUNTER_WAIT,
        HAS_CLOSE_BUTTON,
        MAP_RETURNED,
        ROCKET_DIALOG,
        UNKNOWN
    }

    enum TargetType {
        POKEMON,
        BLUE_STOP,
        GYM
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
        // A stable map has the small menu ball plus at least one map HUD
        // anchor.  Check it before encounter controls: dense maps can contain
        // white shapes in every encounter control region.
        if (looksLikeMapScreen(bitmap)) {
            return ScreenState.MAP_RETURNED;
        }
        if (looksLikeEncounterReady(bitmap)) {
            return ScreenState.ENCOUNTER;
        }
        if (looksLikeEncounterContext(bitmap)) {
            return ScreenState.ENCOUNTER_WAIT;
        }
        if (mapBeforeTap != null &&
                mapBeforeTap.distance(new FrameSignature(bitmap)) < 0.105f &&
                looksLikeMapScreenRelaxed(bitmap)) {
            return ScreenState.MAP_RETURNED;
        }
        if (looksLikeRocketDialog(bitmap)) {
            return ScreenState.ROCKET_DIALOG;
        }
        if (looksLikeCloseButton(bitmap)) {
            return ScreenState.HAS_CLOSE_BUTTON;
        }
        return ScreenState.UNKNOWN;
    }

    static PointF findCloseButton(Bitmap bitmap) {
        if (looksLikeEncounterContext(bitmap) ||
                looksLikeCaptureAnimation(bitmap) ||
                looksLikeMapScreenRelaxed(bitmap)) {
            return null;
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float bestScore = 0f;
        float bestSymbolScore = 0f;
        float bestY = 0f;
        int bestRadius = 0;
        int yStart = Math.round(height * 0.89f);
        int yEnd = Math.round(height * 0.975f);
        int yStep = Math.max(4, height / 150);
        int radiusStart = Math.max(10, Math.round(width * 0.038f));
        int radiusEnd = Math.max(radiusStart, Math.round(width * 0.082f));
        int radiusStep = Math.max(3, width / 180);
        for (int y = yStart; y <= yEnd; y += yStep) {
            for (int radius = radiusStart;
                 radius <= radiusEnd;
                 radius += radiusStep) {
                float score = circleBoundaryScore(
                        bitmap,
                        width * 0.50f,
                        y,
                        radius
                );
                float symbolScore = xInteriorScore(
                        bitmap,
                        width * 0.50f,
                        y,
                        radius
                );
                float combinedScore = score * 0.72f +
                        symbolScore * 0.28f;
                if (combinedScore > bestScore) {
                    bestScore = combinedScore;
                    bestSymbolScore = symbolScore;
                    bestY = y;
                    bestRadius = radius;
                }
            }
        }
        if (bestScore < 0.29f || bestSymbolScore < 0.12f) {
            return null;
        }
        float halfWidth = Math.max(
                width * 0.08f,
                bestRadius * 1.30f
        ) / width;
        float halfHeight = Math.max(
                height * 0.035f,
                bestRadius * 1.30f
        ) / height;
        RegionStats centerBottom = stats(
                bitmap,
                0.50f - halfWidth,
                bestY / height - halfHeight,
                0.50f + halfWidth,
                bestY / height + halfHeight
        );
        // The permanent map menu button is a red/white ball in the same
        // position.  A real close button is neutral/cyan and has a clear rim.
        if (centerBottom.redRatio > 0.075f ||
                centerBottom.edgeRatio < 0.060f ||
                (
                        centerBottom.whiteRatio < 0.018f &&
                                centerBottom.blueRatio < 0.025f
                )) {
            return null;
        }
        RegionStats runControl =
                stats(bitmap, 0.02f, 0.035f, 0.18f, 0.15f);
        RegionStats cameraControl =
                stats(bitmap, 0.38f, 0.035f, 0.62f, 0.15f);
        RegionStats rightDock =
                stats(bitmap, 0.77f, 0.82f, 0.97f, 0.98f);
        boolean whiteMenu =
                runControl.whiteRatio > 0.50f ||
                        cameraControl.whiteRatio > 0.50f;
        boolean visibleTopControls =
                runControl.edgeRatio > 0.075f ||
                        cameraControl.edgeRatio > 0.075f;
        boolean pageDock = rightDock.whiteRatio > 0.080f;
        if (!whiteMenu && !visibleTopControls && !pageDock) {
            return null;
        }
        return new PointF(width * 0.50f, bestY);
    }

    static TargetCandidate findMapTarget(
            List<Bitmap> frames,
            List<PointF> blockedPoints
    ) {
        return findMapTarget(frames, blockedPoints, false);
    }

    static TargetCandidate findMapTarget(
            List<Bitmap> frames,
            List<PointF> blockedPoints,
            boolean pokemonOnlyMode
    ) {
        if (frames.size() < 3) {
            return null;
        }
        int width = frames.get(frames.size() / 2).getWidth();
        int height = frames.get(frames.size() / 2).getHeight();
        int windowCount = frames.size() - 2;
        List<CandidateCluster> clusters = new ArrayList<>();
        for (int center = 1; center < frames.size() - 1; center++) {
            List<Bitmap> window = Arrays.asList(
                    frames.get(center - 1),
                    frames.get(center),
                    frames.get(center + 1)
            );
            List<TargetCandidate> candidates =
                    findMapCandidatesInWindow(window, blockedPoints);
            for (TargetCandidate candidate : candidates) {
                addCandidateToCluster(
                        clusters,
                        candidate,
                        width * 0.085f
                );
            }
        }

        int pokemonRequired = windowCount <= 1
                ? 1
                : Math.max(3, Math.round(windowCount * 0.55f));
        int stopRequired = windowCount <= 1
                ? 1
                : Math.max(4, Math.round(windowCount * 0.70f));
        CandidateCluster bestPokemon = null;
        CandidateCluster bestStop = null;
        for (CandidateCluster cluster : clusters) {
            if (cluster.type == TargetType.POKEMON &&
                    cluster.count >= pokemonRequired &&
                    !nearStableStructure(cluster, clusters, width)) {
                if (bestPokemon == null ||
                        cluster.preferenceScore(width, height) >
                                bestPokemon.preferenceScore(width, height)) {
                    bestPokemon = cluster;
                }
            } else if (!pokemonOnlyMode &&
                    cluster.type == TargetType.BLUE_STOP &&
                    cluster.count >= stopRequired &&
                    !nearGym(cluster, clusters, width)) {
                if (bestStop == null ||
                        cluster.preferenceScore(width, height) >
                                bestStop.preferenceScore(width, height)) {
                    bestStop = cluster;
                }
            }
        }
        CandidateCluster selected =
                bestPokemon != null ? bestPokemon : bestStop;
        return selected == null ? null : selected.asCandidate();
    }

    private static List<TargetCandidate> findMapCandidatesInWindow(
            List<Bitmap> frames,
            List<PointF> blockedPoints
    ) {
        List<TargetCandidate> result = new ArrayList<>();
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
            return result;
        }

        int gridStep = Math.max(4, width / 145);
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

        List<Component> components = components(
                rawMotion,
                rawMotion,
                differences,
                columns,
                rows
        );
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
            result.add(candidate);
        }
        return result;
    }

    private static void addCandidateToCluster(
            List<CandidateCluster> clusters,
            TargetCandidate candidate,
            float radius
    ) {
        CandidateCluster nearest = null;
        float nearestDistance = Float.MAX_VALUE;
        for (CandidateCluster cluster : clusters) {
            if (cluster.type != candidate.type) {
                continue;
            }
            float dx = cluster.centerX() - candidate.point.x;
            float dy = cluster.centerY() - candidate.point.y;
            float distance = dx * dx + dy * dy;
            if (distance <= radius * radius && distance < nearestDistance) {
                nearest = cluster;
                nearestDistance = distance;
            }
        }
        if (nearest == null) {
            nearest = new CandidateCluster(candidate.type);
            clusters.add(nearest);
        }
        nearest.add(candidate);
    }

    private static boolean nearStableStructure(
            CandidateCluster candidate,
            List<CandidateCluster> clusters,
            int width
    ) {
        for (CandidateCluster structure : clusters) {
            if (structure == candidate ||
                    structure.count < 2 ||
                    (
                            structure.type != TargetType.GYM &&
                                    structure.type != TargetType.BLUE_STOP
                    )) {
                continue;
            }
            float radius = structure.type == TargetType.GYM
                    ? width * 0.20f
                    : width * 0.13f;
            if (candidate.distanceSquared(structure) <= radius * radius) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearGym(
            CandidateCluster candidate,
            List<CandidateCluster> clusters,
            int width
    ) {
        for (CandidateCluster structure : clusters) {
            if (structure.type == TargetType.GYM &&
                    structure.count >= 2 &&
                    candidate.distanceSquared(structure) <=
                            width * width * 0.040f) {
                return true;
            }
        }
        return false;
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
        VisualStats structureContext = visualStats(
                bitmap,
                Math.max(
                        bounds.left,
                        left - Math.max(objectWidth * 3, width / 10)
                ),
                Math.max(bounds.top, top - objectHeight),
                Math.min(
                        bounds.right,
                        right + Math.max(objectWidth * 3, width / 10)
                ),
                Math.min(
                        bounds.bottom,
                        bottom + Math.max(objectHeight * 8, height / 8)
                )
        );
        int pedestalHalfWidth = Math.max(objectWidth, width / 14);
        RegionStats pedestal = stats(
                bitmap,
                Math.max(bounds.left, weightedX - pedestalHalfWidth) / width,
                Math.max(bounds.top, bottom) / (float) height,
                Math.min(bounds.right, weightedX + pedestalHalfWidth) / width,
                Math.min(bounds.bottom, bottom + height * 0.16f) / height
        );
        boolean mountedOnMapStructure =
                pedestal.edgeRatio > 0.085f &&
                        (
                                pedestal.blueRatio > 0.28f ||
                                        pedestal.whiteRatio > 0.055f
                        );

        float boxCells =
                (component.maxColumn - component.minColumn + 1f) *
                        (component.maxRow - component.minRow + 1f);
        float compactness = component.activeCells / Math.max(1f, boxCells);
        boolean flatTerrain =
                local.edgeRatio < 0.075f &&
                        compactness < 0.28f &&
                        area > width * height * 0.0025f;
        boolean purpleStop =
                local.neonMagentaRatio > 0.14f &&
                        objectHeight > height * 0.020f;
        float boxWidthCells =
                component.maxColumn - component.minColumn + 1f;
        float boxHeightCells =
                component.maxRow - component.minRow + 1f;
        float elongation = Math.max(boxWidthCells, boxHeightCells) /
                Math.max(1f, Math.min(boxWidthCells, boxHeightCells));
        boolean thinBoundary =
                elongation > 2.60f ||
                        compactness < 0.24f && component.activeCells >= 5;
        boolean yellowGreenBoundary =
                local.yellowGreenRatio > 0.43f &&
                        (compactness < 0.48f || elongation > 1.65f) &&
                        local.shadowRatio < 0.16f;
        if (flatTerrain || thinBoundary || yellowGreenBoundary || purpleStop) {
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
                        objectWidth >= width * 0.025f &&
                        objectWidth <= width * 0.18f &&
                        objectHeight > height * 0.025f &&
                        objectHeight <= height * 0.13f;
        boolean gymLike =
                (
                        objectWidth > width * 0.12f ||
                                objectHeight > height * 0.085f ||
                                area > width * height * 0.008f
                ) &&
                        (
                                surrounding.redWhiteRatio > 0.14f ||
                                        local.cyanRatio > 0.18f
                        );
        float aspectRatio = objectHeight / (float) Math.max(1, objectWidth);
        boolean personLike =
                objectHeight > height * 0.050f &&
                        aspectRatio > 2.15f;
        boolean pokemonLike =
                confidence >= 0.30f &&
                        local.edgeRatio >= 0.10f &&
                        local.cyanRatio < 0.16f &&
                        surrounding.cyanRatio < 0.20f &&
                        structureContext.cyanRatio < 0.22f &&
                        !mountedOnMapStructure &&
                        compactness >= 0.18f &&
                        component.activeCells >= 3 &&
                        objectWidth >= width * 0.020f &&
                        objectWidth <= width * 0.15f &&
                        objectHeight >= height * 0.012f &&
                        objectHeight <= height * 0.085f &&
                        !personLike;
        if (gymLike) {
            return new TargetCandidate(
                    TargetType.GYM,
                    new PointF(weightedX, weightedY),
                    confidence
            );
        }
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
        for (int dy = -8; dy <= 8; dy += 2) {
            for (int dx = -8; dx <= 8; dx += 2) {
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
        return Math.min(
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
        int yellowGreen = 0;
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
                if (g > 110 && b < 125 &&
                        g > b * 1.22f &&
                        (r > 105 || g > r * 1.12f)) {
                    yellowGreen++;
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
                yellowGreen / (float) Math.max(1, total),
                redWhite / (float) Math.max(1, total),
                edge / (float) Math.max(1, total),
                shadow / (float) Math.max(1, total)
        );
    }

    private static boolean looksLikeEncounterReady(Bitmap bitmap) {
        float circleScore = findEncounterBallCircleScore(bitmap);

        // Ball colour is deliberately not fixed: normal, Great, Ultra and
        // Master Balls all differ.  Readiness is the encounter HUD plus a
        // large circular ball at the lower centre.
        return looksLikeEncounterContext(bitmap) &&
                circleScore >= 0.28f;
    }

    private static boolean looksLikeCloseButton(Bitmap bitmap) {
        return findCloseButton(bitmap) != null;
    }

    private static boolean looksLikeMapScreen(Bitmap bitmap) {
        if (!looksLikeMapMenuBall(bitmap)) {
            return false;
        }
        RegionStats avatar =
                stats(bitmap, 0.00f, 0.76f, 0.31f, 0.995f);
        RegionStats rightActions =
                stats(bitmap, 0.72f, 0.70f, 0.995f, 0.97f);
        return looksLikeAvatarAnchor(avatar) ||
                looksLikeRightMapAnchor(rightActions);
    }

    private static boolean looksLikeMapScreenRelaxed(Bitmap bitmap) {
        RegionStats avatar =
                stats(bitmap, 0.00f, 0.76f, 0.31f, 0.995f);
        RegionStats rightActions =
                stats(bitmap, 0.72f, 0.70f, 0.995f, 0.97f);
        boolean avatarAnchor = looksLikeAvatarAnchor(avatar);
        boolean rightAnchor = looksLikeRightMapAnchor(rightActions);
        return looksLikeMapMenuBall(bitmap)
                ? avatarAnchor || rightAnchor
                : avatarAnchor && rightAnchor;
    }

    private static boolean looksLikeMapMenuBall(Bitmap bitmap) {
        RegionStats wholeBall =
                stats(bitmap, 0.40f, 0.88f, 0.60f, 0.99f);
        RegionStats redTop =
                stats(bitmap, 0.41f, 0.88f, 0.59f, 0.945f);
        RegionStats whiteBottom =
                stats(bitmap, 0.41f, 0.925f, 0.59f, 0.995f);
        boolean redWhiteBall = (
                wholeBall.redRatio > 0.055f &&
                        wholeBall.whiteRatio > 0.040f
                ) || (
                redTop.redRatio > 0.060f &&
                        whiteBottom.whiteRatio > 0.045f
                );
        return redWhiteBall && findMapMenuBallCircleScore(bitmap) >= 0.22f;
    }

    private static boolean looksLikeAvatarAnchor(RegionStats avatar) {
        return avatar.edgeRatio > 0.105f &&
                (
                        avatar.redRatio +
                                avatar.blueRatio +
                                avatar.whiteRatio +
                                avatar.darkRatio
                ) > 0.075f;
    }

    private static boolean looksLikeRightMapAnchor(RegionStats rightActions) {
        return rightActions.edgeRatio > 0.085f &&
                rightActions.whiteRatio > 0.045f;
    }

    private static boolean looksLikeEncounterContext(Bitmap bitmap) {
        RegionStats runControl =
                stats(bitmap, 0.02f, 0.035f, 0.18f, 0.15f);
        RegionStats cameraControl =
                stats(bitmap, 0.38f, 0.035f, 0.62f, 0.15f);
        RegionStats cpPanel =
                stats(bitmap, 0.16f, 0.24f, 0.84f, 0.44f);
        RegionStats leftButton =
                stats(bitmap, 0.03f, 0.82f, 0.23f, 0.98f);
        RegionStats rightButton =
                stats(bitmap, 0.77f, 0.82f, 0.97f, 0.98f);
        boolean runAnchor =
                runControl.whiteRatio > 0.009f &&
                        runControl.edgeRatio > 0.038f;
        boolean cameraAnchor =
                cameraControl.whiteRatio > 0.006f &&
                        cameraControl.edgeRatio > 0.035f;
        boolean cpAnchor =
                cpPanel.whiteRatio > 0.004f &&
                        cpPanel.edgeRatio > 0.035f &&
                        (
                                cpPanel.darkRatio > 0.025f ||
                                        cpPanel.edgeRatio > 0.075f
                        );
        boolean captureDocks =
                leftButton.edgeRatio > 0.045f &&
                        rightButton.edgeRatio > 0.045f &&
                        (
                                leftButton.darkRatio > 0.20f ||
                                        leftButton.whiteRatio > 0.003f
                        ) &&
                        (
                                rightButton.darkRatio > 0.20f ||
                                        rightButton.whiteRatio > 0.003f
                        );
        // Bottom capture docks rule out PokéStop/Gym pages.  Requiring the CP
        // band prevents roads and dense map objects from becoming encounters.
        return runAnchor &&
                cameraAnchor &&
                cpAnchor &&
                captureDocks &&
                !looksLikeMapScreenRelaxed(bitmap);
    }

    private static boolean looksLikeCaptureAnimation(Bitmap bitmap) {
        RegionStats leftButton =
                stats(bitmap, 0.03f, 0.82f, 0.23f, 0.98f);
        RegionStats rightButton =
                stats(bitmap, 0.77f, 0.82f, 0.97f, 0.98f);
        return leftButton.edgeRatio > 0.045f &&
                rightButton.edgeRatio > 0.045f &&
                findEncounterBallCircleScore(bitmap) >= 0.35f;
    }

    private static float findMapMenuBallCircleScore(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float best = 0f;
        int yStart = Math.round(height * 0.89f);
        int yEnd = Math.round(height * 0.975f);
        int yStep = Math.max(4, height / 150);
        int radiusStart = Math.max(10, Math.round(width * 0.045f));
        int radiusEnd = Math.max(radiusStart, Math.round(width * 0.090f));
        int radiusStep = Math.max(3, width / 180);
        for (int y = yStart; y <= yEnd; y += yStep) {
            for (int radius = radiusStart;
                 radius <= radiusEnd;
                 radius += radiusStep) {
                best = Math.max(
                        best,
                        circleBoundaryScore(
                                bitmap,
                                width * 0.50f,
                                y,
                                radius
                        )
                );
            }
        }
        return best;
    }

    private static float findEncounterBallCircleScore(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float best = 0f;
        int yStart = Math.round(height * 0.82f);
        int yEnd = Math.round(height * 0.975f);
        int yStep = Math.max(5, height / 120);
        int radiusStart = Math.max(12, Math.round(width * 0.105f));
        int radiusEnd = Math.max(radiusStart, Math.round(width * 0.235f));
        int radiusStep = Math.max(4, width / 100);
        for (int y = yStart; y <= yEnd; y += yStep) {
            for (int radius = radiusStart;
                 radius <= radiusEnd;
                 radius += radiusStep) {
                best = Math.max(
                        best,
                        circleBoundaryScore(
                                bitmap,
                                width * 0.50f,
                                y,
                                radius
                        )
                );
            }
        }
        return best;
    }

    private static float circleBoundaryScore(
            Bitmap bitmap,
            float centerX,
            float centerY,
            float radius
    ) {
        int valid = 0;
        int strong = 0;
        long totalDifference = 0L;
        int samples = 36;
        float inset = Math.max(2f, radius * 0.055f);
        for (int index = 0; index < samples; index++) {
            double angle = Math.PI * 2.0 * index / samples;
            float cosine = (float) Math.cos(angle);
            float sine = (float) Math.sin(angle);
            int innerX = Math.round(centerX + (radius - inset) * cosine);
            int innerY = Math.round(centerY + (radius - inset) * sine);
            int outerX = Math.round(centerX + (radius + inset) * cosine);
            int outerY = Math.round(centerY + (radius + inset) * sine);
            if (innerX < 0 || innerY < 0 ||
                    outerX < 0 || outerY < 0 ||
                    innerX >= bitmap.getWidth() ||
                    outerX >= bitmap.getWidth() ||
                    innerY >= bitmap.getHeight() ||
                    outerY >= bitmap.getHeight()) {
                continue;
            }
            int difference = colorDifference(
                    bitmap.getPixel(innerX, innerY),
                    bitmap.getPixel(outerX, outerY)
            );
            valid++;
            totalDifference += difference;
            if (difference >= 48) {
                strong++;
            }
        }
        if (valid < samples * 0.55f) {
            return 0f;
        }
        float strongRatio = strong / (float) valid;
        float averageDifference =
                Math.min(1f, totalDifference / (float) (valid * 150));
        return strongRatio * 0.72f + averageDifference * 0.28f;
    }

    private static float xInteriorScore(
            Bitmap bitmap,
            float centerX,
            float centerY,
            float radius
    ) {
        long difference = 0L;
        int comparisons = 0;
        for (float factor = 0.16f; factor <= 0.48f; factor += 0.08f) {
            float diagonal = radius * factor;
            float axial = diagonal * 1.4142f;
            int[][] diagonalPoints = new int[][]{
                    {Math.round(centerX + diagonal),
                            Math.round(centerY + diagonal)},
                    {Math.round(centerX - diagonal),
                            Math.round(centerY - diagonal)},
                    {Math.round(centerX + diagonal),
                            Math.round(centerY - diagonal)},
                    {Math.round(centerX - diagonal),
                            Math.round(centerY + diagonal)}
            };
            int[][] axialPoints = new int[][]{
                    {Math.round(centerX + axial), Math.round(centerY)},
                    {Math.round(centerX - axial), Math.round(centerY)},
                    {Math.round(centerX), Math.round(centerY + axial)},
                    {Math.round(centerX), Math.round(centerY - axial)}
            };
            for (int index = 0; index < diagonalPoints.length; index++) {
                int dx = diagonalPoints[index][0];
                int dy = diagonalPoints[index][1];
                int ax = axialPoints[index][0];
                int ay = axialPoints[index][1];
                if (dx < 0 || dy < 0 || ax < 0 || ay < 0 ||
                        dx >= bitmap.getWidth() ||
                        ax >= bitmap.getWidth() ||
                        dy >= bitmap.getHeight() ||
                        ay >= bitmap.getHeight()) {
                    continue;
                }
                difference += colorDifference(
                        bitmap.getPixel(dx, dy),
                        bitmap.getPixel(ax, ay)
                );
                comparisons++;
            }
        }
        return Math.min(
                1f,
                difference / (float) Math.max(1, comparisons * 120)
        );
    }

    private static boolean looksLikeRocketDialog(Bitmap bitmap) {
        RegionStats character =
                stats(bitmap, 0.18f, 0.18f, 0.82f, 0.72f);
        RegionStats lowerPanel =
                stats(bitmap, 0.03f, 0.70f, 0.97f, 0.95f);
        return character.darkRatio > 0.20f &&
                character.redRatio > 0.015f &&
                character.edgeRatio > 0.065f &&
                lowerPanel.darkRatio > 0.35f;
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
        int dark = 0;
        int yellow = 0;
        int edge = 0;
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
                if (max < 92) {
                    dark++;
                }
                if (r > 140 && g > 120 && b < 110 &&
                        r > b * 1.50f && g > b * 1.30f) {
                    yellow++;
                }
                if (edgeStrength(bitmap, x, y, step) > 42) {
                    edge++;
                }
            }
        }
        return new RegionStats(
                total,
                red,
                white,
                blue,
                green,
                dark,
                yellow,
                edge
        );
    }

    private static boolean isBlocked(
            float x,
            float y,
            int width,
            List<PointF> blockedPoints
    ) {
        float radius = width * 0.18f;
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

    private static final class CandidateCluster {
        final TargetType type;
        int count;
        float xTotal;
        float yTotal;
        float confidenceTotal;

        CandidateCluster(TargetType type) {
            this.type = type;
        }

        void add(TargetCandidate candidate) {
            count++;
            xTotal += candidate.point.x;
            yTotal += candidate.point.y;
            confidenceTotal += candidate.confidence;
        }

        float centerX() {
            return xTotal / Math.max(1, count);
        }

        float centerY() {
            return yTotal / Math.max(1, count);
        }

        float score() {
            return confidenceTotal / Math.max(1, count) +
                    Math.min(0.35f, count * 0.055f);
        }

        float preferenceScore(int width, int height) {
            float dx = centerX() - width * PLAYER_CENTER_X_RATIO;
            float dy = centerY() - height * PLAYER_CENTER_Y_RATIO;
            float distance = (float) Math.sqrt(dx * dx + dy * dy) /
                    Math.max(1f, width * POKEMON_RADIUS_BY_WIDTH);
            return score() - Math.min(0.45f, distance * 0.30f);
        }

        float distanceSquared(CandidateCluster other) {
            float dx = centerX() - other.centerX();
            float dy = centerY() - other.centerY();
            return dx * dx + dy * dy;
        }

        TargetCandidate asCandidate() {
            return new TargetCandidate(
                    type,
                    new PointF(centerX(), centerY()),
                    confidenceTotal / Math.max(1, count)
            );
        }
    }

    private static final class VisualStats {
        final float cyanRatio;
        final float neonMagentaRatio;
        final float warmRatio;
        final float yellowGreenRatio;
        final float redWhiteRatio;
        final float edgeRatio;
        final float shadowRatio;

        VisualStats(
                float cyanRatio,
                float neonMagentaRatio,
                float warmRatio,
                float yellowGreenRatio,
                float redWhiteRatio,
                float edgeRatio,
                float shadowRatio
        ) {
            this.cyanRatio = cyanRatio;
            this.neonMagentaRatio = neonMagentaRatio;
            this.warmRatio = warmRatio;
            this.yellowGreenRatio = yellowGreenRatio;
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
        final float darkRatio;
        final float yellowRatio;
        final float edgeRatio;

        RegionStats(
                int total,
                int red,
                int white,
                int blue,
                int green,
                int dark,
                int yellow,
                int edge
        ) {
            int safeTotal = Math.max(1, total);
            redRatio = red / (float) safeTotal;
            whiteRatio = white / (float) safeTotal;
            blueRatio = blue / (float) safeTotal;
            greenRatio = green / (float) safeTotal;
            darkRatio = dark / (float) safeTotal;
            yellowRatio = yellow / (float) safeTotal;
            edgeRatio = edge / (float) safeTotal;
        }
    }
}
