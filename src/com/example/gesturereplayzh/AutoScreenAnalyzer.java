package com.example.gesturereplayzh;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;

import java.util.List;

final class AutoScreenAnalyzer {
    enum ScreenState {
        ENCOUNTER,
        HAS_CLOSE_BUTTON,
        MAP_RETURNED,
        ROCKET_DIALOG
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
                int first = colors[i];
                int second = other.colors[i];
                difference += Math.abs(Color.red(first) - Color.red(second));
                difference += Math.abs(Color.green(first) - Color.green(second));
                difference += Math.abs(Color.blue(first) - Color.blue(second));
            }
            return difference / (colors.length * 765f);
        }
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

    private AutoScreenAnalyzer() {}

    static FrameSignature signature(Bitmap bitmap) {
        return new FrameSignature(bitmap);
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

    static PointF closeButton(Bitmap bitmap) {
        return new PointF(bitmap.getWidth() * 0.5f, bitmap.getHeight() * 0.938f);
    }

    static PointF rocketDialogAdvance(Bitmap bitmap) {
        return new PointF(bitmap.getWidth() * 0.5f, bitmap.getHeight() * 0.69f);
    }

    static TargetCandidate findMapTarget(
            Bitmap first,
            Bitmap second,
            List<PointF> blockedPoints
    ) {
        int width = Math.min(first.getWidth(), second.getWidth());
        int height = Math.min(first.getHeight(), second.getHeight());
        int left = Math.round(width * 0.05f);
        int right = Math.round(width * 0.84f);
        int top = Math.round(height * 0.13f);
        int bottom = Math.round(height * 0.82f);
        int stride = Math.max(14, width / 34);
        int patchRadius = Math.max(26, width / 17);

        TargetCandidate bestPokemon = null;
        TargetCandidate bestBlueStop = null;
        for (int y = top + patchRadius; y < bottom - patchRadius; y += stride) {
            for (int x = left + patchRadius; x < right - patchRadius; x += stride) {
                if (isBlocked(x, y, width, blockedPoints)) {
                    continue;
                }
                MotionStats local = motionStats(
                        first,
                        second,
                        x,
                        y,
                        patchRadius
                );
                if (local.motionRatio < 0.055f) {
                    continue;
                }

                MotionStats surrounding = motionStats(
                        first,
                        second,
                        x,
                        y,
                        patchRadius * 2
                );
                if (surrounding.motionRatio > 0.23f ||
                        surrounding.magentaRatio > 0.19f ||
                        surrounding.redWhiteStructureRatio > 0.22f) {
                    continue;
                }

                float centerDistance = distanceFromCenter(x, y, width, height);
                float confidence =
                        local.motionRatio * 1.9f -
                                centerDistance * 0.16f;
                PointF point = new PointF(x, y);

                boolean looksPurple =
                        local.magentaRatio > 0.075f &&
                                local.magentaRatio > local.cyanRatio * 0.62f;
                if (looksPurple) {
                    continue;
                }

                boolean looksBlueStop =
                        local.cyanRatio > 0.17f &&
                                local.cyanRatio > local.warmRatio * 1.7f;
                if (looksBlueStop) {
                    TargetCandidate candidate = new TargetCandidate(
                            TargetType.BLUE_STOP,
                            point,
                            confidence + local.cyanRatio * 0.45f
                    );
                    if (bestBlueStop == null ||
                            candidate.confidence > bestBlueStop.confidence) {
                        bestBlueStop = candidate;
                    }
                } else if (local.motionRatio < 0.18f) {
                    TargetCandidate candidate = new TargetCandidate(
                            TargetType.POKEMON,
                            point,
                            confidence + local.warmRatio * 0.18f
                    );
                    if (bestPokemon == null ||
                            candidate.confidence > bestPokemon.confidence) {
                        bestPokemon = candidate;
                    }
                }
            }
        }
        return bestPokemon != null ? bestPokemon : bestBlueStop;
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
                stats(bitmap, 0.40f, 0.88f, 0.60f, 0.99f);
        return centerBottom.redRatio < 0.075f &&
                centerBottom.whiteRatio > 0.055f &&
                centerBottom.blueRatio > 0.045f;
    }

    private static boolean looksLikeMapMenu(Bitmap bitmap) {
        RegionStats centerBottom =
                stats(bitmap, 0.40f, 0.88f, 0.60f, 0.99f);
        return centerBottom.redRatio > 0.080f &&
                centerBottom.whiteRatio > 0.050f;
    }

    private static boolean isBlocked(
            float x,
            float y,
            int width,
            List<PointF> blockedPoints
    ) {
        float radius = width * 0.12f;
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
        float dx = (x - width * 0.45f) / width;
        float dy = (y - height * 0.48f) / height;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static MotionStats motionStats(
            Bitmap first,
            Bitmap second,
            int centerX,
            int centerY,
            int radius
    ) {
        int width = Math.min(first.getWidth(), second.getWidth());
        int height = Math.min(first.getHeight(), second.getHeight());
        int startX = Math.max(0, centerX - radius);
        int endX = Math.min(width, centerX + radius);
        int startY = Math.max(0, centerY - radius);
        int endY = Math.min(height, centerY + radius);
        int step = Math.max(3, width / 190);

        int total = 0;
        int moving = 0;
        int cyan = 0;
        int magenta = 0;
        int warm = 0;
        int redWhiteStructure = 0;
        for (int y = startY; y < endY; y += step) {
            for (int x = startX; x < endX; x += step) {
                int firstColor = first.getPixel(x, y);
                int secondColor = second.getPixel(x, y);
                int r = Color.red(secondColor);
                int g = Color.green(secondColor);
                int b = Color.blue(secondColor);
                int difference =
                        Math.abs(Color.red(firstColor) - r) +
                                Math.abs(Color.green(firstColor) - g) +
                                Math.abs(Color.blue(firstColor) - b);
                total++;
                if (difference > 72) {
                    moving++;
                }
                if (b > 145 && g > 135 && b > r * 1.18f && g > r * 1.12f) {
                    cyan++;
                }
                if (r > 145 && b > 135 && r > g * 1.10f && b > g * 1.08f) {
                    magenta++;
                }
                if (r > 135 && r > b * 1.18f && g > b * 0.82f) {
                    warm++;
                }
                boolean strongRed =
                        r > 175 && r > g * 1.45f && r > b * 1.35f;
                boolean nearWhite =
                        r > 205 && g > 205 && b > 205 &&
                                Math.max(r, Math.max(g, b)) -
                                        Math.min(r, Math.min(g, b)) < 30;
                if (strongRed || nearWhite) {
                    redWhiteStructure++;
                }
            }
        }
        return new MotionStats(
                moving / (float) Math.max(1, total),
                cyan / (float) Math.max(1, total),
                magenta / (float) Math.max(1, total),
                warm / (float) Math.max(1, total),
                redWhiteStructure / (float) Math.max(1, total)
        );
    }

    private static final class MotionStats {
        final float motionRatio;
        final float cyanRatio;
        final float magentaRatio;
        final float warmRatio;
        final float redWhiteStructureRatio;

        MotionStats(
                float motionRatio,
                float cyanRatio,
                float magentaRatio,
                float warmRatio,
                float redWhiteStructureRatio
        ) {
            this.motionRatio = motionRatio;
            this.cyanRatio = cyanRatio;
            this.magentaRatio = magentaRatio;
            this.warmRatio = warmRatio;
            this.redWhiteStructureRatio = redWhiteStructureRatio;
        }
    }

    private static RegionStats stats(
            Bitmap bitmap,
            float left,
            float top,
            float right,
            float bottom
    ) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int startX = Math.max(0, Math.round(width * left));
        int endX = Math.min(width, Math.round(width * right));
        int startY = Math.max(0, Math.round(height * top));
        int endY = Math.min(height, Math.round(height * bottom));
        int step = Math.max(2, Math.min(width, height) / 240);

        int total = 0;
        int red = 0;
        int white = 0;
        int dark = 0;
        int skin = 0;
        int green = 0;
        int blue = 0;
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
                if (r < 72 && g < 76 && b < 82) {
                    dark++;
                }
                if (r > 120 && g > 70 && b > 50 &&
                        r > g * 1.12f && g > b * 1.05f) {
                    skin++;
                }
                if (g > 125 && g > r * 1.08f && g > b * 1.02f) {
                    green++;
                }
                if (b > 120 && b > r * 1.08f && b >= g * 0.82f) {
                    blue++;
                }
            }
        }
        return new RegionStats(total, red, white, dark, skin, green, blue);
    }

    private static final class RegionStats {
        final float redRatio;
        final float whiteRatio;
        final float darkRatio;
        final float skinRatio;
        final float greenRatio;
        final float blueRatio;

        RegionStats(
                int total,
                int red,
                int white,
                int dark,
                int skin,
                int green,
                int blue
        ) {
            int safeTotal = Math.max(1, total);
            redRatio = red / (float) safeTotal;
            whiteRatio = white / (float) safeTotal;
            darkRatio = dark / (float) safeTotal;
            skinRatio = skin / (float) safeTotal;
            greenRatio = green / (float) safeTotal;
            blueRatio = blue / (float) safeTotal;
        }
    }
}
