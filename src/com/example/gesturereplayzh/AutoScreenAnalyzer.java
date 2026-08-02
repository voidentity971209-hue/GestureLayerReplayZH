package com.example.gesturereplayzh;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.RectF;

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
        GYM,
        POWER_SPOT
    }

    static final class TargetCandidate {
        final TargetType type;
        final PointF point;
        final float confidence;
        final RectF modelRoiBox;
        final String modelLabel;

        TargetCandidate(TargetType type, PointF point, float confidence) {
            this(type, point, confidence, null, null);
        }

        TargetCandidate(
                TargetType type,
                PointF point,
                float confidence,
                RectF modelRoiBox,
                String modelLabel
        ) {
            this.type = type;
            this.point = point;
            this.confidence = confidence;
            this.modelRoiBox = modelRoiBox == null
                    ? null
                    : new RectF(modelRoiBox);
            this.modelLabel = modelLabel;
        }

        boolean fromModel() {
            return modelRoiBox != null && modelLabel != null;
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
        // A map also contains a running-person icon and lower controls.  Only
        // accept an encounter when the flee icon, both side docks and the
        // large central throw ball are present together.
        if (looksLikeEncounterReady(bitmap)) {
            return ScreenState.ENCOUNTER;
        }
        if (looksLikeRocketDialog(bitmap)) {
            return ScreenState.ROCKET_DIALOG;
        }
        if (looksLikeCloseButton(bitmap)) {
            return ScreenState.HAS_CLOSE_BUTTON;
        }
        if (looksLikeMapScreen(bitmap)) {
            return ScreenState.MAP_RETURNED;
        }
        if (looksLikeCaptureAnimation(bitmap)) {
            return ScreenState.ENCOUNTER_WAIT;
        }
        if (mapBeforeTap != null &&
                mapBeforeTap.distance(new FrameSignature(bitmap)) < 0.105f &&
                looksLikeMapScreenRelaxed(bitmap)) {
            return ScreenState.MAP_RETURNED;
        }
        return ScreenState.UNKNOWN;
    }

    static PointF findCloseButton(Bitmap bitmap) {
        if (looksLikeEncounterContext(bitmap) ||
                looksLikeCaptureAnimation(bitmap)) {
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
        return findMapTarget(frames, blockedPoints, false, 0.07f);
    }

    static TargetCandidate findMapTarget(
            List<Bitmap> frames,
            List<PointF> blockedPoints,
            boolean pokemonOnlyMode
    ) {
        return findMapTarget(frames, blockedPoints, pokemonOnlyMode, 0.07f);
    }

    static TargetCandidate findMapTarget(
            List<Bitmap> frames,
            List<PointF> blockedPoints,
            boolean pokemonOnlyMode,
            float blockedRadiusRatio
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
                    findMapCandidatesInWindow(
                            window,
                            blockedPoints,
                            pokemonOnlyMode,
                            blockedRadiusRatio
                    );
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
                : Math.max(2, Math.round(windowCount * 0.40f));
        CandidateCluster bestPokemon = null;
        for (CandidateCluster cluster : clusters) {
            if (cluster.type == TargetType.POKEMON &&
                    cluster.count >= pokemonRequired &&
                    !nearStableStructure(cluster, clusters, width)) {
                if (bestPokemon == null ||
                        cluster.preferenceScore(width, height) >
                                bestPokemon.preferenceScore(width, height)) {
                    bestPokemon = cluster;
                }
            }
        }
        return bestPokemon == null ? null : bestPokemon.asCandidate();
    }

    static float modelCandidateTemporalSupport(
            List<Bitmap> frames,
            RectF screenBox
    ) {
        if (frames.size() < 3) {
            return 0f;
        }
        Bitmap first = frames.get(0);
        Bitmap middle = frames.get(frames.size() / 2);
        Bitmap last = frames.get(frames.size() - 1);
        Bounds bounds = Bounds.forMap(middle.getWidth(), middle.getHeight());
        Shift firstShift = estimateShift(middle, first, bounds);
        Shift lastShift = estimateShift(middle, last, bounds);
        int left = Math.max(bounds.left, Math.round(screenBox.left));
        int top = Math.max(bounds.top, Math.round(screenBox.top));
        int right = Math.min(bounds.right, Math.round(screenBox.right));
        int bottom = Math.min(bounds.bottom, Math.round(screenBox.bottom));
        if (right <= left || bottom <= top) {
            return 0f;
        }
        TemporalStats temporal = temporalStats(
                first,
                middle,
                last,
                firstShift,
                lastShift,
                left,
                top,
                right,
                bottom,
                middle.getWidth()
        );
        return temporal.presentFrames < 2
                ? 0f
                : temporal.stability * 0.70f +
                        temporal.edgeSimilarity * 0.30f;
    }

    static float modelCandidateBackgroundSupport(
            Bitmap bitmap,
            RectF screenBox
    ) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = Math.max(0, Math.round(screenBox.left));
        int top = Math.max(0, Math.round(screenBox.top));
        int right = Math.min(width, Math.round(screenBox.right));
        int bottom = Math.min(height, Math.round(screenBox.bottom));
        if (right <= left || bottom <= top) {
            return 0f;
        }
        int horizontalMargin = Math.max(4, (right - left) / 2);
        int verticalMargin = Math.max(4, (bottom - top) / 2);
        VisualStats local = visualStats(bitmap, left, top, right, bottom);
        VisualStats surrounding = visualStats(
                bitmap,
                Math.max(0, left - horizontalMargin),
                Math.max(0, top - verticalMargin),
                Math.min(width, right + horizontalMargin),
                Math.min(height, bottom + verticalMargin)
        );
        return Math.min(
                1f,
                Math.max(
                        0f,
                        (local.edgeRatio - surrounding.edgeRatio * 0.45f) *
                                4.2f
                ) + Math.min(0.30f, local.shadowRatio * 1.8f)
        );
    }

    private static List<TargetCandidate> findMapCandidatesInWindow(
            List<Bitmap> frames,
            List<PointF> blockedPoints,
            boolean fastRecall,
            float blockedRadiusRatio
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
        int threshold = fastRecall
                ? Math.max(30, Math.min(96, percentile70 + 14))
                : Math.max(42, Math.min(120, percentile70 + 24));

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

        // Join immediately-neighbouring animated fragments so classification
        // sees one whole silhouette. Without this, a rotating fragment of a
        // PokéStop or Gym can look like a Pokémon-sized independent object.
        boolean[] joinedMotion = dilate(rawMotion, columns, rows, 1);
        boolean[] continuityMotion = dilate(rawMotion, columns, rows, 3);
        List<Component> components = components(
                joinedMotion,
                rawMotion,
                differences,
                columns,
                rows
        );
        for (Component component : components) {
            TargetCandidate candidate = classifyComponent(
                    first,
                    middle,
                    last,
                    firstShift,
                    lastShift,
                    component,
                    bounds,
                    gridStep,
                    width,
                    height,
                    blockedPoints,
                    blockedRadiusRatio,
                    threshold,
                    fastRecall,
                    isPartOfExtendedMotionNetwork(
                            component,
                            continuityMotion,
                            columns,
                            rows
                    )
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
                    (
                            structure.type != TargetType.GYM &&
                                    structure.type != TargetType.BLUE_STOP &&
                                    structure.type != TargetType.POWER_SPOT
                    )) {
                continue;
            }
            float radius = structure.type == TargetType.GYM
                    ? width * 0.12f
                    : structure.type == TargetType.POWER_SPOT
                    ? width * 0.11f
                    : width * 0.07f;
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
            Bitmap first,
            Bitmap bitmap,
            Bitmap last,
            Shift firstShift,
            Shift lastShift,
            Component component,
            Bounds bounds,
            int gridStep,
            int width,
            int height,
            List<PointF> blockedPoints,
            float blockedRadiusRatio,
            int threshold,
            boolean fastRecall,
            boolean extendedBackgroundNetwork
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
        if (objectWidth < 8 || objectHeight < 8) {
            return null;
        }

        float weightedX = bounds.left +
                component.weightedColumn / Math.max(1f, component.weight) *
                        gridStep;
        float weightedY = bounds.top +
                component.weightedRow / Math.max(1f, component.weight) *
                        gridStep;
        weightedY = Math.min(bottom, weightedY + objectHeight * 0.12f);
        if (isBlocked(weightedX, weightedY, width, blockedPoints,
                blockedRadiusRatio)) {
            return null;
        }

        TemporalStats temporal = temporalStats(
                first,
                bitmap,
                last,
                firstShift,
                lastShift,
                left,
                top,
                right,
                bottom,
                width
        );
        if (temporal.presentFrames < 2) {
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
        int pedestalHalfWidth = Math.max(
                Math.round(objectWidth * 0.75f),
                width / 22
        );
        RegionStats pedestal = stats(
                bitmap,
                Math.max(bounds.left, weightedX - pedestalHalfWidth) / width,
                Math.max(bounds.top, bottom) / (float) height,
                Math.min(bounds.right, weightedX + pedestalHalfWidth) / width,
                Math.min(bounds.bottom, bottom + height * 0.08f) / height
        );
        RegionStats mountBand = stats(
                bitmap,
                Math.max(bounds.left, weightedX - width * 0.070f) / width,
                Math.max(bounds.top, weightedY + height * 0.020f) / height,
                Math.min(bounds.right, weightedX + width * 0.070f) / width,
                Math.min(bounds.bottom, weightedY + height * 0.110f) / height
        );
        boolean mountedOnMapStructure =
                (
                        pedestal.edgeRatio > 0.090f &&
                                (
                                        pedestal.blueRatio > 0.30f ||
                                                pedestal.whiteRatio > 0.035f
                                )
                ) ||
                        (
                                mountBand.edgeRatio > 0.095f &&
                                        (
                                                mountBand.blueRatio > 0.45f ||
                                                        mountBand.whiteRatio >
                                                                0.025f
                                        )
                        );

        float boxCells =
                (component.maxColumn - component.minColumn + 1f) *
                        (component.maxRow - component.minRow + 1f);
        float compactness = component.activeCells / Math.max(1f, boxCells);
        boolean flatTerrain =
                local.edgeRatio < 0.075f &&
                        compactness < 0.28f &&
                        area > width * height * 0.0025f;
        boolean magentaFacilityHint =
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
        if (flatTerrain || thinBoundary || yellowGreenBoundary ||
                extendedBackgroundNetwork) {
            return null;
        }

        float aspectRatio = objectHeight / (float) Math.max(1, objectWidth);
        boolean wholeLargeStructure =
                objectWidth > width * 0.155f ||
                        objectHeight > height * 0.105f ||
                        area > width * height * 0.010f;
        boolean fixedDiscShape =
                objectWidth >= width * 0.015f &&
                        objectWidth <= width * 0.19f &&
                        objectHeight >= height * 0.008f &&
                        objectHeight <= height * 0.13f &&
                        aspectRatio >= 0.20f &&
                        aspectRatio <= 4.50f &&
                        compactness >= 0.10f;
        boolean blueStop =
                fixedDiscShape &&
                        local.cyanRatio > 0.08f &&
                        local.cyanRatio > local.warmRatio * 1.35f &&
                        surrounding.cyanRatio > 0.075f;
        boolean powerSpotLike =
                magentaFacilityHint &&
                        (
                                wholeLargeStructure ||
                                        mountedOnMapStructure ||
                                        surrounding.neonMagentaRatio > 0.085f
                        );
        boolean rotatingMountedFacility =
                mountedOnMapStructure &&
                        (
                                temporal.areaRatio > 1.45f ||
                                        temporal.edgeSimilarity < 0.55f
                        );
        boolean gymLike =
                rotatingMountedFacility ||
                        mountedOnMapStructure && wholeLargeStructure ||
                        (
                                objectWidth > width * 0.105f &&
                                        (
                                                surrounding.redWhiteRatio >
                                                        0.14f ||
                                                        local.cyanRatio > 0.18f
                                        )
                        );
        boolean personLike =
                objectHeight > height * 0.050f &&
                        aspectRatio > 2.15f;
        float completeness = Math.min(
                1f,
                Math.max(0f, (compactness - 0.09f) / 0.48f) * 0.65f +
                        Math.min(1f, component.activeCells / 18f) * 0.35f
        );
        float backgroundSeparation = Math.min(
                1f,
                Math.max(
                        0f,
                        (local.edgeRatio - surrounding.edgeRatio * 0.45f) *
                                4.2f
                ) + Math.min(0.30f, local.shadowRatio * 1.8f)
        );
        float sizeScore = sizeSuitability(
                objectWidth,
                objectHeight,
                width,
                height,
                aspectRatio
        );
        float confidence =
                temporal.stability * 0.35f +
                        completeness * 0.25f +
                        temporal.edgeSimilarity * 0.15f +
                        backgroundSeparation * 0.15f +
                        sizeScore * 0.10f;

        boolean normalPokemon =
                confidence >= 0.42f &&
                        local.edgeRatio >= 0.075f &&
                        local.cyanRatio < 0.24f &&
                        surrounding.cyanRatio < 0.30f &&
                        !mountedOnMapStructure &&
                        compactness >= 0.13f &&
                        component.activeCells >= 2;
        boolean fastPokemon =
                confidence >= 0.38f &&
                        local.edgeRatio >= 0.055f &&
                        compactness >= 0.09f &&
                        component.activeCells >= 2 &&
                        (
                                !mountedOnMapStructure ||
                                        confidence >= 0.42f
                        );
        boolean pokemonLike =
                !wholeLargeStructure &&
                        (fastRecall ? fastPokemon : normalPokemon) &&
                        objectWidth >= width * 0.012f &&
                        objectWidth <= width * 0.17f &&
                        objectHeight >= height * 0.007f &&
                        objectHeight <= height * 0.115f &&
                        !blueStop &&
                        !powerSpotLike &&
                        !personLike;
        if (powerSpotLike) {
            return new TargetCandidate(
                    TargetType.POWER_SPOT,
                    new PointF(weightedX, weightedY),
                    confidence
            );
        }
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

    private static float sizeSuitability(
            int objectWidth,
            int objectHeight,
            int screenWidth,
            int screenHeight,
            float aspectRatio
    ) {
        float widthRatio = objectWidth / (float) Math.max(1, screenWidth);
        float heightRatio = objectHeight / (float) Math.max(1, screenHeight);
        if (widthRatio < 0.012f || heightRatio < 0.007f ||
                widthRatio > 0.17f || heightRatio > 0.115f ||
                aspectRatio > 2.15f) {
            return 0f;
        }
        float widthFit = 1f - Math.min(
                1f,
                Math.abs(widthRatio - 0.065f) / 0.105f
        );
        float heightFit = 1f - Math.min(
                1f,
                Math.abs(heightRatio - 0.050f) / 0.065f
        );
        return Math.max(0.20f, (widthFit + heightFit) / 2f);
    }

    private static TemporalStats temporalStats(
            Bitmap first,
            Bitmap middle,
            Bitmap last,
            Shift firstShift,
            Shift lastShift,
            int left,
            int top,
            int right,
            int bottom,
            int screenWidth
    ) {
        int step = Math.max(2, screenWidth / 240);
        FrameMoment firstMoment = frameMoment(
                first,
                left,
                top,
                right,
                bottom,
                firstShift.dx,
                firstShift.dy,
                step
        );
        FrameMoment middleMoment = frameMoment(
                middle,
                left,
                top,
                right,
                bottom,
                0,
                0,
                step
        );
        FrameMoment lastMoment = frameMoment(
                last,
                left,
                top,
                right,
                bottom,
                lastShift.dx,
                lastShift.dy,
                step
        );
        FrameMoment[] moments = {firstMoment, middleMoment, lastMoment};
        int minimumEdges = Math.max(
                2,
                ((right - left) / step) * ((bottom - top) / step) / 30
        );
        int presentFrames = 0;
        int minimumCount = Integer.MAX_VALUE;
        int maximumCount = 0;
        for (FrameMoment moment : moments) {
            if (moment.edgeCount >= minimumEdges) {
                presentFrames++;
            }
            minimumCount = Math.min(minimumCount, moment.edgeCount);
            maximumCount = Math.max(maximumCount, moment.edgeCount);
        }
        float objectWidth = Math.max(1f, right - left);
        float centerTolerance = Math.max(
                screenWidth * 0.018f,
                objectWidth * 0.35f
        );
        float maximumDrift = Math.max(
                firstMoment.distanceTo(middleMoment),
                lastMoment.distanceTo(middleMoment)
        );
        float centerScore = Math.max(
                0f,
                1f - maximumDrift / Math.max(1f, centerTolerance)
        );
        float areaRatio = maximumCount / (float) Math.max(1, minimumCount);
        float areaScore = areaRatio <= 1.35f
                ? 1f
                : Math.max(0f, 1f - (areaRatio - 1.35f) / 1.15f);
        float presenceScore = presentFrames == 3
                ? 1f
                : presentFrames == 2 ? 0.65f : 0f;
        float edgeSimilarity = minimumCount /
                (float) Math.max(1, maximumCount);
        float stability =
                centerScore * 0.45f +
                        areaScore * 0.35f +
                        presenceScore * 0.20f;
        return new TemporalStats(
                presentFrames,
                stability,
                edgeSimilarity,
                areaRatio
        );
    }

    private static FrameMoment frameMoment(
            Bitmap bitmap,
            int left,
            int top,
            int right,
            int bottom,
            int shiftX,
            int shiftY,
            int step
    ) {
        int edgeCount = 0;
        float xTotal = 0f;
        float yTotal = 0f;
        for (int y = top; y < bottom; y += step) {
            for (int x = left; x < right; x += step) {
                int shiftedX = x + shiftX;
                int shiftedY = y + shiftY;
                if (shiftedX < 0 || shiftedY < 0 ||
                        shiftedX >= bitmap.getWidth() ||
                        shiftedY >= bitmap.getHeight()) {
                    continue;
                }
                if (edgeStrength(bitmap, shiftedX, shiftedY, step) > 42) {
                    edgeCount++;
                    xTotal += x;
                    yTotal += y;
                }
            }
        }
        return new FrameMoment(
                edgeCount,
                edgeCount == 0 ? (left + right) / 2f : xTotal / edgeCount,
                edgeCount == 0 ? (top + bottom) / 2f : yTotal / edgeCount
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

    private static boolean[] dilate(
            boolean[] source,
            int columns,
            int rows,
            int radius
    ) {
        boolean[] result = Arrays.copyOf(source, source.length);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (!source[index]) {
                    continue;
                }
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int x = column + dx;
                        int y = row + dy;
                        if (x >= 0 && y >= 0 && x < columns && y < rows) {
                            result[y * columns + x] = true;
                        }
                    }
                }
            }
        }
        return result;
    }

    private static boolean isPartOfExtendedMotionNetwork(
            Component candidate,
            boolean[] bridgedMotion,
            int columns,
            int rows
    ) {
        if (candidate.activeCells < 2 ||
                candidate.minColumn > candidate.maxColumn ||
                candidate.minRow > candidate.maxRow) {
            return false;
        }
        int seed = -1;
        for (int row = candidate.minRow;
             row <= candidate.maxRow && seed < 0;
             row++) {
            for (int column = candidate.minColumn;
                 column <= candidate.maxColumn;
                 column++) {
                int index = row * columns + column;
                if (bridgedMotion[index]) {
                    seed = index;
                    break;
                }
            }
        }
        if (seed < 0) {
            return false;
        }

        boolean[] visited = new boolean[bridgedMotion.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(seed);
        visited[seed] = true;
        int networkCells = 0;
        int minColumn = columns;
        int maxColumn = 0;
        int minRow = rows;
        int maxRow = 0;
        while (!queue.isEmpty()) {
            int current = queue.removeFirst();
            int row = current / columns;
            int column = current % columns;
            networkCells++;
            minColumn = Math.min(minColumn, column);
            maxColumn = Math.max(maxColumn, column);
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = column + dx;
                    int y = row + dy;
                    if (x < 0 || y < 0 || x >= columns || y >= rows) {
                        continue;
                    }
                    int next = y * columns + x;
                    if (bridgedMotion[next] && !visited[next]) {
                        visited[next] = true;
                        queue.add(next);
                    }
                }
            }
        }

        int candidateWidth = candidate.maxColumn - candidate.minColumn + 1;
        int candidateHeight = candidate.maxRow - candidate.minRow + 1;
        int networkWidth = maxColumn - minColumn + 1;
        int networkHeight = maxRow - minRow + 1;
        boolean growsBeyondCandidate =
                networkWidth >= candidateWidth + Math.max(5, candidateWidth) ||
                        networkHeight >= candidateHeight +
                                Math.max(5, candidateHeight);
        boolean crossesLargeMapSpan =
                networkWidth >= Math.max(12, Math.round(columns * 0.16f)) ||
                        networkHeight >= Math.max(12, Math.round(rows * 0.12f));
        float networkElongation = Math.max(networkWidth, networkHeight) /
                (float) Math.max(1, Math.min(networkWidth, networkHeight));
        boolean roadLikeNetwork =
                networkElongation >= 1.75f ||
                        networkCells >= Math.max(45, candidate.activeCells * 5);
        return growsBeyondCandidate && crossesLargeMapSpan &&
                roadLikeNetwork;
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
        return looksLikeFleeIcon(bitmap) &&
                looksLikeEncounterSideDocks(bitmap) &&
                findEncounterBallCircleScore(bitmap) >= 0.15f;
    }

    static boolean isStrictMapScreen(Bitmap bitmap) {
        return looksLikeMapScreen(bitmap);
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
        return looksLikeEncounterReady(bitmap);
    }

    private static boolean looksLikeCaptureAnimation(Bitmap bitmap) {
        return looksLikeFleeIcon(bitmap) &&
                findEncounterBallCircleScore(bitmap) >= 0.08f;
    }

    private static boolean looksLikeEncounterSideDocks(Bitmap bitmap) {
        RegionStats leftDock =
                stats(bitmap, 0.015f, 0.80f, 0.255f, 0.985f);
        RegionStats rightDock =
                stats(bitmap, 0.745f, 0.80f, 0.985f, 0.985f);
        float leftCircle = findEncounterDockCircleScore(bitmap, 0.115f);
        float rightCircle = findEncounterDockCircleScore(bitmap, 0.885f);
        boolean berryContent = leftDock.redRatio >= 0.006f &&
                (leftDock.greenRatio >= 0.004f ||
                        leftDock.whiteRatio >= 0.012f);
        boolean ballContent =
                (rightDock.redRatio >= 0.009f ||
                        rightDock.blueRatio >= 0.008f ||
                        rightDock.yellowRatio >= 0.006f) &&
                        (rightDock.whiteRatio >= 0.010f ||
                                rightDock.darkRatio >= 0.040f);
        boolean leftPresent = leftDock.edgeRatio >= 0.025f &&
                leftCircle >= 0.10f &&
                (berryContent || leftDock.whiteRatio >= 0.025f);
        boolean rightPresent = rightDock.edgeRatio >= 0.025f &&
                rightCircle >= 0.10f &&
                (ballContent || rightDock.whiteRatio >= 0.025f);
        return leftPresent && rightPresent;
    }

    private static float findEncounterDockCircleScore(
            Bitmap bitmap,
            float centerXRatio
    ) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float best = 0f;
        int yStart = Math.round(height * 0.845f);
        int yEnd = Math.round(height * 0.955f);
        int yStep = Math.max(4, height / 160);
        int radiusStart = Math.max(10, Math.round(width * 0.035f));
        int radiusEnd = Math.max(radiusStart, Math.round(width * 0.090f));
        int radiusStep = Math.max(3, width / 180);
        float centerX = width * centerXRatio;
        for (int y = yStart; y <= yEnd; y += yStep) {
            for (int radius = radiusStart;
                 radius <= radiusEnd;
                 radius += radiusStep) {
                best = Math.max(
                        best,
                        circleBoundaryScore(bitmap, centerX, y, radius)
                );
            }
        }
        return best;
    }

    private static boolean looksLikeFleeIcon(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int step = Math.max(1, width / 360);
        int left = Math.round(width * 0.025f);
        int right = Math.round(width * 0.18f);
        // The supplied 591x1280 capture places the flee icon at roughly
        // x=5.5%-13% and y=4.8%-9.2%. Leave margin around that normalized
        // anchor so the bright component is not clipped at the search edge.
        int top = Math.round(height * 0.035f);
        int bottom = Math.round(height * 0.14f);
        int columns = Math.max(1, (right - left) / step);
        int rows = Math.max(1, (bottom - top) / step);
        boolean[] bright = new boolean[columns * rows];
        for (int row = 0; row < rows; row++) {
            int y = Math.min(height - 1, top + row * step);
            for (int column = 0; column < columns; column++) {
                int x = Math.min(width - 1, left + column * step);
                int color = bitmap.getPixel(x, y);
                int red = Color.red(color);
                int green = Color.green(color);
                int blue = Color.blue(color);
                int maximum = Math.max(red, Math.max(green, blue));
                int minimum = Math.min(red, Math.min(green, blue));
                bright[row * columns + column] =
                        minimum >= 160 && maximum - minimum <= 95;
            }
        }

        boolean[] visited = new boolean[bright.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int index = 0; index < bright.length; index++) {
            if (!bright[index] || visited[index]) {
                continue;
            }
            int count = 0;
            int minColumn = columns;
            int maxColumn = 0;
            int minRow = rows;
            int maxRow = 0;
            int[] verticalBands = new int[3];
            visited[index] = true;
            queue.add(index);
            List<Integer> pixels = new ArrayList<>();
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                pixels.add(current);
                int row = current / columns;
                int column = current % columns;
                count++;
                minColumn = Math.min(minColumn, column);
                maxColumn = Math.max(maxColumn, column);
                minRow = Math.min(minRow, row);
                maxRow = Math.max(maxRow, row);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int x = column + dx;
                        int y = row + dy;
                        if (x < 0 || y < 0 || x >= columns || y >= rows) {
                            continue;
                        }
                        int next = y * columns + x;
                        if (bright[next] && !visited[next]) {
                            visited[next] = true;
                            queue.add(next);
                        }
                    }
                }
            }
            int componentWidth = (maxColumn - minColumn + 1) * step;
            int componentHeight = (maxRow - minRow + 1) * step;
            float widthRatio = componentWidth / (float) width;
            float heightRatio = componentHeight / (float) height;
            float aspect = componentHeight /
                    (float) Math.max(1, componentWidth);
            float fill = count /
                    (float) Math.max(
                            1,
                            (maxColumn - minColumn + 1) *
                                    (maxRow - minRow + 1)
                    );
            boolean touchesSearchEdge =
                    minColumn == 0 || minRow == 0 ||
                            maxColumn == columns - 1 || maxRow == rows - 1;
            if (touchesSearchEdge || widthRatio < 0.032f ||
                    widthRatio > 0.115f || heightRatio < 0.025f ||
                    heightRatio > 0.095f || aspect < 0.65f ||
                    aspect > 2.40f || fill < 0.075f || fill > 0.62f) {
                continue;
            }
            int componentRows = Math.max(1, maxRow - minRow + 1);
            int componentColumns = Math.max(1,
                    maxColumn - minColumn + 1);
            int[][] poseCells = new int[3][3];
            for (int pixel : pixels) {
                int row = pixel / columns;
                int column = pixel % columns;
                int band = Math.min(
                        2,
                        (row - minRow) * 3 / componentRows
                );
                verticalBands[band]++;
                int columnBand = Math.min(
                        2,
                        (column - minColumn) * 3 / componentColumns
                );
                poseCells[band][columnBand]++;
            }
            boolean headAndArm = poseCells[0][1] > 0 ||
                    poseCells[0][2] > 0;
            boolean torso = poseCells[1][0] > 0 &&
                    (poseCells[1][1] > 0 || poseCells[1][2] > 0);
            boolean separatedLegs = poseCells[2][0] > 0 &&
                    poseCells[2][2] > 0;
            if (verticalBands[0] >= Math.max(2, count / 18) &&
                    verticalBands[1] >= Math.max(2, count / 12) &&
                    verticalBands[2] >= Math.max(2, count / 18) &&
                    headAndArm && torso && separatedLegs) {
                return true;
            }
        }
        // Some phones draw the same flee icon as translucent gray. In that
        // case it merges with a bright sky and is not a standalone bright
        // component, but its strokes still create a concentrated edge cluster
        // at the icon's fixed, normalized position.
        float iconEdges = edgeRatioInRegion(
                bitmap,
                0.050f,
                0.058f,
                0.145f,
                0.125f,
                28
        );
        float surroundingEdges = edgeRatioInRegion(
                bitmap,
                0.025f,
                0.045f,
                0.180f,
                0.150f,
                28
        );
        RegionStats fixedIcon = stats(
                bitmap,
                0.045f,
                0.040f,
                0.150f,
                0.130f
        );
        return (iconEdges >= 0.12f &&
                iconEdges - surroundingEdges >= 0.065f) ||
                (fixedIcon.whiteRatio >= 0.020f &&
                        fixedIcon.edgeRatio >= 0.060f);
    }

    private static float edgeRatioInRegion(
            Bitmap bitmap,
            float leftRatio,
            float topRatio,
            float rightRatio,
            float bottomRatio,
            int threshold
    ) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = Math.max(1, Math.round(width * leftRatio));
        int top = Math.max(1, Math.round(height * topRatio));
        int right = Math.min(width - 2, Math.round(width * rightRatio));
        int bottom = Math.min(height - 2, Math.round(height * bottomRatio));
        int step = Math.max(1, Math.min(width, height) / 360);
        int samples = 0;
        int edges = 0;
        for (int y = top; y <= bottom; y += step) {
            for (int x = left; x <= right; x += step) {
                samples++;
                if (edgeStrength(bitmap, x, y, step) > threshold) {
                    edges++;
                }
            }
        }
        return edges / (float) Math.max(1, samples);
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
            List<PointF> blockedPoints,
            float blockedRadiusRatio
    ) {
        float radius = width * blockedRadiusRatio;
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
            if (active) {
                minColumn = Math.min(minColumn, column);
                maxColumn = Math.max(maxColumn, column);
                minRow = Math.min(minRow, row);
                maxRow = Math.max(maxRow, row);
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

    private static final class FrameMoment {
        final int edgeCount;
        final float centerX;
        final float centerY;

        FrameMoment(int edgeCount, float centerX, float centerY) {
            this.edgeCount = edgeCount;
            this.centerX = centerX;
            this.centerY = centerY;
        }

        float distanceTo(FrameMoment other) {
            float dx = centerX - other.centerX;
            float dy = centerY - other.centerY;
            return (float) Math.sqrt(dx * dx + dy * dy);
        }
    }

    private static final class TemporalStats {
        final int presentFrames;
        final float stability;
        final float edgeSimilarity;
        final float areaRatio;

        TemporalStats(
                int presentFrames,
                float stability,
                float edgeSimilarity,
                float areaRatio
        ) {
            this.presentFrames = presentFrames;
            this.stability = stability;
            this.edgeSimilarity = edgeSimilarity;
            this.areaRatio = areaRatio;
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
