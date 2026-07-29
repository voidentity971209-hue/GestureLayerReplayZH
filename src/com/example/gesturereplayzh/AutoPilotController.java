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
    private static final int MAP_FRAME_COUNT = 3;
    private static final long MAP_FRAME_GAP_MS = 450L;
    private static final long POST_TAP_CLASSIFY_DELAY_MS = 2000L;
    private static final long OPEN_SCREEN_POLL_MS = 400L;
    private static final int MAX_OPEN_SCREEN_POLLS = 9;
    private static final int REQUIRED_ENCOUNTER_POLLS = 2;
    private static final int REQUIRED_UNKNOWN_POLLS = 9;

    private final GestureAccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<PointF> blockedPoints = new ArrayList<>();
    private final List<Bitmap> mapFrames = new ArrayList<>();

    private boolean active;
    private boolean screenshotPending;
    private int screenshotFailures;
    private int generation;
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
        scheduleCycle(500L, generation);
    }

    void stop() {
        active = false;
        generation++;
        screenshotPending = false;
        screenshotFailures = 0;
        handler.removeCallbacksAndMessages(null);
        recycleMapFrames();
        mapBeforeTap = null;
        lastTarget = null;
    }

    private void scheduleCycle(long delayMs, int token) {
        if (!isCurrent(token)) {
            return;
        }
        handler.postDelayed(() -> inspectCurrentScreen(token, 0), delayMs);
    }

    private void inspectCurrentScreen(int token, int unknownCount) {
        if (!isCurrent(token)) {
            return;
        }
        takeScreenshot(token, bitmap -> {
            AutoScreenAnalyzer.ScreenState state =
                    AutoScreenAnalyzer.classifyAfterTap(bitmap, null);
            AutoSettings settings = AutoSettings.load(service);
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            recycle(bitmap);
            switch (state) {
                case ENCOUNTER:
                    service.autoStatus(settings.encounterDescription);
                    handler.postDelayed(
                            () -> playCatchGesture(token, 0),
                            settings.beforeCatchMs
                    );
                    break;
                case HAS_CLOSE_BUTTON:
                    service.autoStatus(settings.exitDescription);
                    service.dispatchAutoTap(
                            width * settings.exitXRatio,
                            height * settings.exitYRatio,
                            () -> scheduleCycle(settings.afterExitMs, token)
                    );
                    break;
                case MAP_RETURNED:
                    scheduleMapScan(0L, token);
                    break;
                case ROCKET_DIALOG:
                default:
                    if (unknownCount >= 8) {
                        service.autoStatus(settings.rocketDescription);
                        runRocketTap(token, 0);
                    } else {
                        handler.postDelayed(
                                () -> inspectCurrentScreen(
                                        token,
                                        unknownCount + 1
                                ),
                                OPEN_SCREEN_POLL_MS
                        );
                    }
                    break;
            }
        });
    }

    private void scheduleMapScan(long delayMs, int token) {
        if (!isCurrent(token)) {
            return;
        }
        handler.postDelayed(() -> {
            recycleMapFrames();
            captureMapFrame(token);
        }, delayMs);
    }

    private void captureMapFrame(int token) {
        if (!isCurrent(token)) {
            return;
        }
        takeScreenshot(token, bitmap -> {
            mapFrames.add(bitmap);
            if (mapFrames.size() < MAP_FRAME_COUNT) {
                handler.postDelayed(
                        () -> captureMapFrame(token),
                        MAP_FRAME_GAP_MS
                );
                return;
            }
            analyzeMapFrames(token);
        });
    }

    private void analyzeMapFrames(int token) {
        if (!isCurrent(token) || mapFrames.size() < MAP_FRAME_COUNT) {
            return;
        }
        AutoScreenAnalyzer.TargetCandidate target =
                AutoScreenAnalyzer.findMapTarget(mapFrames, blockedPoints);
        Bitmap lastFrame = mapFrames.get(mapFrames.size() - 1);
        if (target == null) {
            recycleMapFrames();
            scheduleCycle(AutoSettings.load(service).scanIntervalMs, token);
            return;
        }

        lastTarget = target;
        mapBeforeTap = AutoScreenAnalyzer.signature(lastFrame);
        recycleMapFrames();
        String targetName =
                target.type == AutoScreenAnalyzer.TargetType.POKEMON
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
                () -> handler.postDelayed(
                        () -> inspectOpenedScreen(token, 0, 0, 0),
                        POST_TAP_CLASSIFY_DELAY_MS
                )
        );
    }

    void onDrivingPromptConfirmed() {
        if (!active) {
            return;
        }
        generation++;
        int token = generation;
        screenshotPending = false;
        handler.removeCallbacksAndMessages(null);
        recycleMapFrames();
        mapBeforeTap = null;
        lastTarget = null;
        service.autoStatus("已確認不是駕駛，2 秒後重新掃描");
        scheduleCycle(POST_TAP_CLASSIFY_DELAY_MS, token);
    }

    private void inspectOpenedScreen(
            int token,
            int pollCount,
            int encounterCount,
            int unknownCount
    ) {
        if (!isCurrent(token)) {
            return;
        }
        takeScreenshot(token, bitmap -> {
            AutoScreenAnalyzer.ScreenState state =
                    AutoScreenAnalyzer.classifyAfterTap(bitmap, mapBeforeTap);
            AutoSettings settings = AutoSettings.load(service);
            switch (state) {
                case ENCOUNTER:
                    recycle(bitmap);
                    int nextEncounterCount = encounterCount + 1;
                    if (nextEncounterCount >= REQUIRED_ENCOUNTER_POLLS) {
                        mapBeforeTap = null;
                        lastTarget = null;
                        service.autoStatus(settings.encounterDescription);
                        handler.postDelayed(
                                () -> playCatchGesture(token, 0),
                                settings.beforeCatchMs
                        );
                    } else {
                        pollOpenedScreen(
                                token,
                                pollCount,
                                nextEncounterCount,
                                0
                        );
                    }
                    break;
                case MAP_RETURNED:
                    recycle(bitmap);
                    mapBeforeTap = null;
                    blockLastTarget();
                    service.autoStatus("已返回地圖，繼續掃描");
                    scheduleCycle(settings.scanIntervalMs, token);
                    break;
                case HAS_CLOSE_BUTTON:
                    int width = bitmap.getWidth();
                    int height = bitmap.getHeight();
                    recycle(bitmap);
                    mapBeforeTap = null;
                    blockLastTarget();
                    service.autoStatus(settings.exitDescription);
                    service.dispatchAutoTap(
                            width * settings.exitXRatio,
                            height * settings.exitYRatio,
                            () -> scheduleCycle(settings.afterExitMs, token)
                    );
                    break;
                case ROCKET_DIALOG:
                default:
                    recycle(bitmap);
                    int nextUnknownCount = unknownCount + 1;
                    if (nextUnknownCount >= REQUIRED_UNKNOWN_POLLS ||
                            pollCount + 1 >= MAX_OPEN_SCREEN_POLLS) {
                        mapBeforeTap = null;
                        blockLastTarget();
                        service.autoStatus(settings.rocketDescription);
                        runRocketTap(token, 0);
                    } else {
                        pollOpenedScreen(
                                token,
                                pollCount,
                                0,
                                nextUnknownCount
                        );
                    }
                    break;
            }
        });
    }

    private void pollOpenedScreen(
            int token,
            int pollCount,
            int encounterCount,
            int unknownCount
    ) {
        if (pollCount + 1 >= MAX_OPEN_SCREEN_POLLS) {
            blockLastTarget();
            scheduleCycle(AutoSettings.load(service).scanIntervalMs, token);
            return;
        }
        handler.postDelayed(
                () -> inspectOpenedScreen(
                        token,
                        pollCount + 1,
                        encounterCount,
                        unknownCount
                ),
                OPEN_SCREEN_POLL_MS
        );
    }

    private void runRocketTap(int token, int completedTaps) {
        if (!isCurrent(token)) {
            return;
        }
        AutoSettings settings = AutoSettings.load(service);
        PointF screen = screenSize();
        if (completedTaps >= settings.rocketTapCount) {
            service.dispatchAutoTap(
                    screen.x * settings.exitXRatio,
                    screen.y * settings.exitYRatio,
                    () -> scheduleCycle(settings.afterExitMs, token)
            );
            return;
        }
        service.dispatchAutoTap(
                screen.x * settings.rocketTapXRatio,
                screen.y * settings.rocketTapYRatio,
                () -> handler.postDelayed(
                        () -> runRocketTap(token, completedTaps + 1),
                        settings.rocketTapIntervalMs
                )
        );
    }

    private void playCatchGesture(int token, int retryCount) {
        if (!isCurrent(token)) {
            return;
        }
        List<GestureLayer> catchLayers = GestureStore.load(service);
        if (catchLayers.isEmpty()) {
            service.autoStatus("目前套用的手勢不存在，已停止自動辨識");
            stop();
            return;
        }
        service.playAutoGesture(
                catchLayers,
                new GestureAccessibilityService.AutoGestureCallback() {
                    @Override
                    public void onCompleted() {
                        if (!isCurrent(token)) {
                            return;
                        }
                        service.autoStatus("捕捉手勢已完成");
                        AutoSettings settings = AutoSettings.load(service);
                        handler.postDelayed(
                                () -> scheduleCycle(
                                        settings.scanIntervalMs,
                                        token
                                ),
                                settings.afterCatchMs
                        );
                    }

                    @Override
                    public void onCancelled() {
                        if (!isCurrent(token)) {
                            return;
                        }
                        if (retryCount < 1) {
                            service.autoStatus("捕捉手勢被取消，0.3 秒後重試");
                            handler.postDelayed(
                                    () -> playCatchGesture(token, retryCount + 1),
                                    300L
                            );
                        } else {
                            service.autoStatus("捕捉手勢再次取消，繼續掃描");
                            scheduleCycle(
                                    AutoSettings.load(service).scanIntervalMs,
                                    token
                            );
                        }
                    }
                }
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
        if (!isCurrent(token)) {
            return;
        }
        if (screenshotPending) {
            handler.postDelayed(
                    () -> takeScreenshot(token, receiver),
                    120L
            );
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
                        screenshotFailures = 0;
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        if (!isCurrent(token)) {
                            buffer.close();
                            return;
                        }
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
                            scheduleCycle(1200L, token);
                            return;
                        }
                        PointF drivingButton =
                                AutoScreenAnalyzer
                                        .findDrivingConfirmationButton(bitmap);
                        if (drivingButton != null) {
                            recycle(bitmap);
                            service.autoStatus(
                                    "發現移動速度警告，按下「我不是駕駛」"
                            );
                            service.dispatchAutoTap(
                                    drivingButton.x,
                                    drivingButton.y,
                                    AutoPilotController.this
                                            ::onDrivingPromptConfirmed
                            );
                            return;
                        }
                        receiver.receive(bitmap);
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        screenshotPending = false;
                        if (isCurrent(token)) {
                            service.autoStatus("畫面擷取失敗：" + errorCode);
                            screenshotFailures++;
                            if (screenshotFailures <= 3) {
                                handler.postDelayed(
                                        () -> takeScreenshot(token, receiver),
                                        600L
                                );
                            } else {
                                screenshotFailures = 0;
                                scheduleCycle(1400L, token);
                            }
                        }
                    }
                }
        );
    }

    private void recycleMapFrames() {
        for (Bitmap bitmap : mapFrames) {
            recycle(bitmap);
        }
        mapFrames.clear();
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
