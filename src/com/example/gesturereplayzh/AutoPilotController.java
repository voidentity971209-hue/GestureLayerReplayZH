package com.example.gesturereplayzh;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.PointF;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;

final class AutoPilotController {
    private final GestureAccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<PointF> blockedPoints = new ArrayList<>();

    private boolean active;
    private boolean screenshotPending;
    private int generation;
    private Bitmap firstMapFrame;
    private AutoScreenAnalyzer.FrameSignature mapBeforeTap;
    private AutoScreenAnalyzer.TargetCandidate lastTarget;

    AutoPilotController(GestureAccessibilityService service) {
        this.service = service;
    }

    boolean isActive() {
        return active;
    }

    void start() {
        stop();
        active = true;
        generation++;
        blockedPoints.clear();
        service.autoStatus("自動辨識已啟動：請保持最大視野、最高角度");
        scheduleScan(500L, generation);
    }

    void stop() {
        active = false;
        generation++;
        screenshotPending = false;
        handler.removeCallbacksAndMessages(null);
        recycle(firstMapFrame);
        firstMapFrame = null;
        mapBeforeTap = null;
        lastTarget = null;
    }

    private void scheduleScan(long delayMs, int token) {
        handler.postDelayed(() -> captureFirstMapFrame(token), delayMs);
    }

    private void captureFirstMapFrame(int token) {
        if (!isCurrent(token)) {
            return;
        }
        takeScreenshot(token, bitmap -> {
            recycle(firstMapFrame);
            firstMapFrame = bitmap;
            AutoSettings settings = AutoSettings.load(service);
            handler.postDelayed(
                    () -> captureSecondMapFrame(token),
                    settings.scanIntervalMs
            );
        });
    }

    private void captureSecondMapFrame(int token) {
        if (!isCurrent(token) || firstMapFrame == null) {
            return;
        }
        takeScreenshot(token, secondFrame -> {
            AutoScreenAnalyzer.TargetCandidate target =
                    AutoScreenAnalyzer.findMapTarget(
                            firstMapFrame,
                            secondFrame,
                            blockedPoints
                    );
            recycle(firstMapFrame);
            firstMapFrame = null;
            if (target == null) {
                recycle(secondFrame);
                scheduleScan(
                        AutoSettings.load(service).scanIntervalMs,
                        token
                );
                return;
            }

            lastTarget = target;
            mapBeforeTap = AutoScreenAnalyzer.signature(secondFrame);
            recycle(secondFrame);
            String targetName = target.type ==
                    AutoScreenAnalyzer.TargetType.POKEMON
                    ? "寶可夢候選"
                    : "藍色補給站候選";
            service.autoStatus(
                    targetName + "：" +
                            Math.round(target.point.x) + "," +
                            Math.round(target.point.y)
            );
            service.dispatchAutoTap(
                    target.point.x,
                    target.point.y,
                    () -> {
                        AutoSettings settings = AutoSettings.load(service);
                        handler.postDelayed(
                                () -> inspectOpenedScreen(token),
                                settings.afterTargetTapMs
                        );
                    }
            );
        });
    }

    private void inspectOpenedScreen(int token) {
        if (!isCurrent(token)) {
            return;
        }
        takeScreenshot(token, bitmap -> {
            AutoScreenAnalyzer.ScreenState state =
                    AutoScreenAnalyzer.classifyAfterTap(bitmap, mapBeforeTap);
            mapBeforeTap = null;
            AutoSettings settings = AutoSettings.load(service);
            switch (state) {
                case ENCOUNTER:
                    recycle(bitmap);
                    service.autoStatus(settings.encounterDescription);
                    handler.postDelayed(
                            () -> playCatchGesture(token),
                            settings.beforeCatchMs
                    );
                    break;
                case MAP_RETURNED:
                    recycle(bitmap);
                    service.autoStatus("已自動返回地圖");
                    scheduleScan(settings.scanIntervalMs, token);
                    break;
                case HAS_CLOSE_BUTTON:
                    int width = bitmap.getWidth();
                    int height = bitmap.getHeight();
                    recycle(bitmap);
                    blockLastTarget();
                    service.autoStatus(settings.exitDescription);
                    service.dispatchAutoTap(
                            width * settings.exitXRatio,
                            height * settings.exitYRatio,
                            () -> scheduleScan(settings.afterExitMs, token)
                    );
                    break;
                case ROCKET_DIALOG:
                default:
                    recycle(bitmap);
                    blockLastTarget();
                    service.autoStatus(settings.rocketDescription);
                    runRocketTap(token, 0);
                    break;
            }
        });
    }

    private void runRocketTap(int token, int completedTaps) {
        if (!isCurrent(token)) {
            return;
        }
        AutoSettings settings = AutoSettings.load(service);
        if (completedTaps >= settings.rocketTapCount) {
            PointF screen = screenSize();
            service.dispatchAutoTap(
                    screen.x * settings.exitXRatio,
                    screen.y * settings.exitYRatio,
                    () -> scheduleScan(settings.afterExitMs, token)
            );
            return;
        }
        PointF screen = screenSize();
        service.dispatchAutoTap(
                screen.x * settings.rocketTapXRatio,
                screen.y * settings.rocketTapYRatio,
                () -> handler.postDelayed(
                        () -> runRocketTap(token, completedTaps + 1),
                        settings.rocketTapIntervalMs
                )
        );
    }

    private void playCatchGesture(int token) {
        if (!isCurrent(token)) {
            return;
        }
        List<GestureLayer> catchLayers = CatchGestureStore.load(service);
        if (catchLayers.isEmpty()) {
            service.autoStatus("捕捉手勢不存在，已停止自動辨識");
            stop();
            return;
        }
        service.playAutoGesture(catchLayers);
        AutoSettings settings = AutoSettings.load(service);
        handler.postDelayed(
                () -> scheduleScan(settings.scanIntervalMs, token),
                settings.afterCatchMs
        );
    }

    private void blockLastTarget() {
        if (lastTarget == null) {
            return;
        }
        blockedPoints.add(new PointF(lastTarget.point.x, lastTarget.point.y));
        if (blockedPoints.size() > 20) {
            blockedPoints.remove(0);
        }
        lastTarget = null;
    }

    private PointF screenSize() {
        android.graphics.Point point = ScreenDimensions.get(service);
        return new PointF(point.x, point.y);
    }

    private void takeScreenshot(int token, BitmapReceiver receiver) {
        if (!isCurrent(token) || screenshotPending) {
            return;
        }
        screenshotPending = true;
        service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(
                            AccessibilityService.ScreenshotResult result
                    ) {
                        screenshotPending = false;
                        if (!isCurrent(token)) {
                            result.getHardwareBuffer().close();
                            return;
                        }
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        ColorSpace colorSpace = result.getColorSpace();
                        Bitmap hardwareBitmap =
                                Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                        Bitmap bitmap = hardwareBitmap == null
                                ? null
                                : hardwareBitmap.copy(
                                        Bitmap.Config.ARGB_8888,
                                        false
                                );
                        if (hardwareBitmap != null) {
                            hardwareBitmap.recycle();
                        }
                        buffer.close();
                        if (bitmap == null) {
                            service.autoStatus("無法讀取畫面，稍後重試");
                            scheduleScan(1200L, token);
                            return;
                        }
                        receiver.receive(bitmap);
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        screenshotPending = false;
                        if (isCurrent(token)) {
                            service.autoStatus("畫面擷取失敗：" + errorCode);
                            scheduleScan(1400L, token);
                        }
                    }
                }
        );
    }

    private boolean isCurrent(int token) {
        return active && token == generation;
    }

    private static void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private interface BitmapReceiver {
        void receive(Bitmap bitmap);
    }
}
