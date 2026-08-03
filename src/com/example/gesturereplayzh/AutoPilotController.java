package com.example.gesturereplayzh;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.PointF;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class AutoPilotController {
    private static final String LOG_TAG = "GestureReplayAuto";
    private static final long MIN_SCREENSHOT_INTERVAL_MS = 100L;

    private final GestureAccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<BlockedTarget> blockedTargets = new ArrayList<>();
    private boolean active;
    private boolean screenshotPending;
    private int screenshotFailures;
    private int generation;
    private long lastScreenshotRequestAt;
    private long unknownStartedAt;
    private int groundIndex;

    AutoPilotController(GestureAccessibilityService service) {
        this.service = service;
    }

    boolean isActive() { return active; }

    void start() {
        stop();
        if (!AutoSettings.load(service).autoEnabled) {
            service.autoStatus("請先在 App 勾選允許全自動實驗功能");
            return;
        }
        active = true;
        generation++;
        blockedTargets.clear();
        groundIndex = 0;
        unknownStartedAt = 0L;
        service.autoStatus("自動流程已啟動：全螢幕搜尋 Pokémon 條列");
        schedule(400L, generation);
    }

    void stop() {
        active = false;
        generation++;
        screenshotPending = false;
        screenshotFailures = 0;
        unknownStartedAt = 0L;
        handler.removeCallbacksAndMessages(null);
    }

    void onDrivingPromptConfirmed() {
        if (active) schedule(500L, generation);
    }

    private void schedule(long delay, int token) {
        if (!isCurrent(token)) return;
        handler.postDelayed(() -> inspect(token), Math.max(100L, delay));
    }

    private void inspect(int token) {
        confirmState(token, null, 0);
    }

    private void confirmState(
            int token,
            AutoScreenAnalyzer.ScreenState previous,
            int matching
    ) {
        takeScreenshot(token, bitmap -> {
            AutoScreenAnalyzer.ScreenState state =
                    AutoScreenAnalyzer.classifyAfterTap(bitmap, null);
            int nextMatching = state == previous ? matching + 1 : 1;
            int required = AutoSettings.load(service).recognitionFrameCount;
            if (nextMatching < required) {
                recycle(bitmap);
                handler.postDelayed(
                        () -> confirmState(token, state, nextMatching),
                        AutoSettings.load(service).scanIntervalMs
                );
                return;
            }
            processState(bitmap, state, token);
        });
    }

    private void processState(
            Bitmap bitmap,
            AutoScreenAnalyzer.ScreenState state,
            int token
    ) {
            Log.i(LOG_TAG, "state=" + state);
            switch (state) {
                case ENCOUNTER:
                    unknownStartedAt = 0L;
                    recycle(bitmap);
                    playCatchGesture(token);
                    break;
                case ENCOUNTER_WAIT:
                    unknownStartedAt = 0L;
                    recycle(bitmap);
                    schedule(AutoSettings.load(service).scanIntervalMs, token);
                    break;
                case ROCKET_DIALOG:
                    unknownStartedAt = 0L;
                    recycle(bitmap);
                    advanceRocket(token, 0);
                    break;
                case HAS_CLOSE_BUTTON:
                    unknownStartedAt = 0L;
                    PointF close = AutoScreenAnalyzer.findCloseButton(bitmap);
                    recycle(bitmap);
                    if (close == null) {
                        unknown(token, "看到非地圖畫面，但沒有可靠 X");
                    } else {
                        tap(close.x, close.y, token,
                                () -> schedule(AutoSettings.load(service).afterExitMs, token));
                    }
                    break;
                case MAP_RETURNED:
                    unknownStartedAt = 0L;
                    scanList(bitmap, token);
                    break;
                default:
                    recycle(bitmap);
                    unknown(token, "未知畫面：安全等待，不觸控");
            }
    }

    private void scanList(Bitmap bitmap, int token) {
        cleanupBlocked();
        PokemonListDetector.Result result = PokemonListDetector.find(bitmap);
        PointF target = null;
        for (PointF candidate : result.candidates) {
            if (!isBlocked(candidate, bitmap.getWidth())) {
                target = candidate;
                break;
            }
        }
        Log.i(LOG_TAG, "list confidence=" + result.confidence +
                " candidates=" + result.candidates.size() +
                " panel=" + result.panel);
        if (target == null) {
            PointF ground = nearestGroundPoint(bitmap, result.panel);
            recycle(bitmap);
            if (ground == null) {
                service.autoStatus("未找到條列候選，也沒有安全移動點");
                schedule(AutoSettings.load(service).scanIntervalMs, token);
            } else {
                tap(ground.x, ground.y, token, () ->
                        schedule(AutoSettings.load(service).groundMoveWaitMs, token));
            }
            return;
        }
        recycle(bitmap);
        service.autoStatus("找到條列候選，重新確認後點擊");
        tapRepeated(target, token, 0);
    }

    private void tapRepeated(PointF target, int token, int completed) {
        AutoSettings settings = AutoSettings.load(service);
        if (completed >= settings.listTapCount) {
            handler.postDelayed(() -> classifyCandidateResult(target, token),
                    settings.postTapClassifyDelayMs);
            return;
        }
        tap(target.x, target.y, token, () ->
                handler.postDelayed(
                        () -> tapRepeated(target, token, completed + 1),
                        settings.listTapIntervalMs
                ));
    }

    private void classifyCandidateResult(PointF target, int token) {
        takeScreenshot(token, bitmap -> {
            AutoScreenAnalyzer.ScreenState state =
                    AutoScreenAnalyzer.classifyAfterTap(bitmap, null);
            recycle(bitmap);
            Log.i(LOG_TAG, "candidate result=" + state + " point=" + target);
            if (state == AutoScreenAnalyzer.ScreenState.ENCOUNTER) {
                playCatchGesture(token);
            } else if (state == AutoScreenAnalyzer.ScreenState.ROCKET_DIALOG) {
                block(target);
                advanceRocket(token, 0);
            } else if (state == AutoScreenAnalyzer.ScreenState.HAS_CLOSE_BUTTON) {
                block(target);
                schedule(100L, token);
            } else if (state == AutoScreenAnalyzer.ScreenState.MAP_RETURNED) {
                block(target);
                schedule(AutoSettings.load(service).scanIntervalMs, token);
            } else if (state == AutoScreenAnalyzer.ScreenState.ENCOUNTER_WAIT) {
                schedule(AutoSettings.load(service).scanIntervalMs, token);
            } else {
                unknown(token, "候選點擊後畫面不明，停止觸控並重查");
            }
        });
    }

    private void playCatchGesture(int token) {
        AutoSettings settings = AutoSettings.load(service);
        List<GestureLayer> layers = GestureStore.load(service);
        if (layers.isEmpty()) {
            service.autoStatus("目前手勢沒有軌跡，已停止自動流程");
            stop();
            return;
        }
        String fingerprint = GestureIdentity.fingerprint(layers);
        Log.i(LOG_TAG, "catch current gesture fingerprint=" + fingerprint +
                " layers=" + layers.size());
        handler.postDelayed(() -> {
            if (!isCurrent(token)) return;
            service.playAutoGesture(layers,
                    new GestureAccessibilityService.AutoGestureCallback() {
                        @Override public void onCompleted() {
                            Log.i(LOG_TAG, "catch gesture completed fingerprint=" + fingerprint);
                            schedule(settings.afterCatchMs, token);
                        }
                        @Override public void onCancelled() {
                            Log.w(LOG_TAG, "catch gesture cancelled fingerprint=" + fingerprint);
                            service.autoStatus("目前捕捉手勢被取消，稍後重新辨識");
                            schedule(settings.scanIntervalMs, token);
                        }
                    });
        }, settings.beforeCatchMs);
    }

    private void advanceRocket(int token, int completed) {
        AutoSettings settings = AutoSettings.load(service);
        if (completed >= settings.rocketTapCount) {
            schedule(settings.rocketTapIntervalMs, token);
            return;
        }
        android.graphics.Point size = ScreenDimensions.get(service);
        tap(size.x * settings.rocketTapXRatio,
                size.y * settings.rocketTapYRatio,
                token,
                () -> handler.postDelayed(
                        () -> advanceRocket(token, completed + 1),
                        settings.rocketTapIntervalMs));
    }

    private PointF nearestGroundPoint(Bitmap bitmap, android.graphics.RectF panel) {
        float[][] offsets = {
                {0f, -.10f}, {.075f, -.075f}, {-.075f, -.075f},
                {.10f, -.02f}, {-.10f, -.02f}, {0f, .07f}
        };
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int limit = Math.min(offsets.length,
                AutoSettings.load(service).groundMoveRetries);
        for (int tries = 0; tries < limit; tries++) {
            int index = (groundIndex + tries) % offsets.length;
            PointF p = new PointF(w * (.50f + offsets[index][0]),
                    h * (.63f + offsets[index][1]));
            if ((panel == null || panel.isEmpty() || !panel.contains(p.x, p.y)) &&
                    !isBlocked(p, w) && p.y < h * .84f) {
                groundIndex = index + 1;
                block(p);
                return p;
            }
        }
        return null;
    }

    private void unknown(int token, String status) {
        long now = System.currentTimeMillis();
        if (unknownStartedAt == 0L) unknownStartedAt = now;
        AutoSettings settings = AutoSettings.load(service);
        if (now - unknownStartedAt >= settings.unknownTimeoutMs) {
            service.autoStatus(status + "；已達安全逾時並停止");
            stop();
        } else {
            service.autoStatus(status);
            schedule(settings.scanIntervalMs, token);
        }
    }

    private void block(PointF point) {
        AutoSettings settings = AutoSettings.load(service);
        blockedTargets.add(new BlockedTarget(point.x, point.y,
                System.currentTimeMillis() + settings.blockedDurationMs));
        while (blockedTargets.size() > settings.blockedMaxCount) {
            blockedTargets.remove(0);
        }
    }

    private boolean isBlocked(PointF point, int screenWidth) {
        float radius = screenWidth * AutoSettings.load(service).blockedRadiusRatio;
        for (BlockedTarget blocked : blockedTargets) {
            if (Math.hypot(point.x - blocked.x, point.y - blocked.y) <= radius) return true;
        }
        return false;
    }

    private void cleanupBlocked() {
        long now = System.currentTimeMillis();
        Iterator<BlockedTarget> it = blockedTargets.iterator();
        while (it.hasNext()) if (it.next().expiresAt <= now) it.remove();
    }

    private void tap(float x, float y, int token, Runnable completed) {
        if (!isCurrent(token)) return;
        service.dispatchAutoTap(x, y, new GestureAccessibilityService.AutoTapCallback() {
            @Override public void onCompleted() { if (isCurrent(token)) completed.run(); }
            @Override public void onCancelled() {
                if (isCurrent(token)) {
                    service.autoStatus("點擊被系統取消，未當成成功動作");
                    schedule(AutoSettings.load(service).scanIntervalMs, token);
                }
            }
        });
    }

    private void takeScreenshot(int token, BitmapReceiver receiver) {
        if (!isCurrent(token)) return;
        long elapsed = System.currentTimeMillis() - lastScreenshotRequestAt;
        if (elapsed < MIN_SCREENSHOT_INTERVAL_MS || screenshotPending) {
            handler.postDelayed(() -> takeScreenshot(token, receiver),
                    Math.max(100L, MIN_SCREENSHOT_INTERVAL_MS - elapsed));
            return;
        }
        screenshotPending = true;
        lastScreenshotRequestAt = System.currentTimeMillis();
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        screenshotPending = false;
                        screenshotFailures = 0;
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        ColorSpace colorSpace = result.getColorSpace();
                        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                        Bitmap bitmap = hardware == null ? null :
                                hardware.copy(Bitmap.Config.ARGB_8888, false);
                        if (hardware != null) hardware.recycle();
                        buffer.close();
                        if (!isCurrent(token)) { recycle(bitmap); return; }
                        if (bitmap == null) { schedule(1000L, token); return; }
                        PointF driving = AutoScreenAnalyzer.findDrivingConfirmationButton(bitmap);
                        if (driving != null) {
                            recycle(bitmap);
                            tap(driving.x, driving.y, token,
                                    () -> schedule(500L, token));
                        } else receiver.receive(bitmap);
                    }
                    @Override public void onFailure(int errorCode) {
                        screenshotPending = false;
                        if (!isCurrent(token)) return;
                        screenshotFailures++;
                        service.autoStatus("畫面擷取失敗：" + errorCode);
                        schedule(screenshotFailures <= 3 ? 400L : 1400L, token);
                    }
                });
    }

    private boolean isCurrent(int token) { return active && token == generation; }
    private static void recycle(Bitmap b) { if (b != null && !b.isRecycled()) b.recycle(); }
    private interface BitmapReceiver { void receive(Bitmap bitmap); }

    private static final class BlockedTarget {
        final float x, y;
        final long expiresAt;
        BlockedTarget(float x, float y, long expiresAt) {
            this.x = x; this.y = y; this.expiresAt = expiresAt;
        }
    }
}
