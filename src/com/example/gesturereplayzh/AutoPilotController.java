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
import java.util.List;

final class AutoPilotController {
    private static final String LOG_TAG = "GestureReplayAuto";
    private static final int MAP_FRAME_COUNT = 3;
    private static final long MAP_FRAME_GAP_MS = 40L;
    private static final long MIN_SCREENSHOT_INTERVAL_MS = 350L;
    private static final long POST_TAP_CLASSIFY_DELAY_MS = 650L;
    private static final long OPEN_SCREEN_POLL_MS = 250L;
    private static final int MAX_OPEN_SCREEN_POLLS = 6;
    private static final int REQUIRED_ENCOUNTER_POLLS = 1;
    private static final int MAX_CATCH_RETRIES = 2;
    private static final long CATCH_RETRY_DELAY_MS = 900L;
    private static final long NON_ENCOUNTER_COOLDOWN_MS = 60_000L;

    private final GestureAccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<BlockedTarget> blockedTargets = new ArrayList<>();
    private final List<Bitmap> mapFrames = new ArrayList<>();

    private boolean active;
    private boolean screenshotPending;
    private int screenshotFailures;
    private int generation;
    private long lastScreenshotRequestAt;
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
        blockedTargets.clear();
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
        handler.postDelayed(() -> inspectCurrentScreen(token, 0, 0), delayMs);
    }

    private void inspectCurrentScreen(
            int token,
            int unknownCount,
            int closeCount
    ) {
        if (!isCurrent(token)) {
            return;
        }
        takeScreenshot(token, bitmap -> {
            AutoScreenAnalyzer.ScreenState state =
                    AutoScreenAnalyzer.classifyAfterTap(bitmap, null);
            Log.i(
                    LOG_TAG,
                    "current-screen state=" + state +
                            " unknown=" + unknownCount +
                            " closeConfirm=" + closeCount
            );
            AutoSettings settings = AutoSettings.load(service);
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            switch (state) {
                case ENCOUNTER:
                    recycle(bitmap);
                    service.autoStatus(settings.encounterDescription);
                    handler.postDelayed(
                            () -> playCatchGesture(token, 0),
                            settings.beforeCatchMs
                    );
                    break;
                case HAS_CLOSE_BUTTON:
                    if (closeCount < 1) {
                        recycle(bitmap);
                        handler.postDelayed(
                                () -> inspectCurrentScreen(
                                        token,
                                        0,
                                        closeCount + 1
                                ),
                                OPEN_SCREEN_POLL_MS
                        );
                        break;
                    }
                    recycle(bitmap);
                    service.autoStatus(settings.exitDescription);
                    service.dispatchAutoTap(
                            width * settings.exitXRatio,
                            height * settings.exitYRatio,
                            () -> scheduleCycle(settings.afterExitMs, token)
                    );
                    break;
                case MAP_RETURNED:
                    beginMapScan(bitmap, token);
                    break;
                case ROCKET_DIALOG:
                    recycle(bitmap);
                    service.autoStatus(settings.rocketDescription);
                    runRocketTap(token, 0);
                    break;
                case UNKNOWN:
                default:
                    recycle(bitmap);
                    if (unknownCount >= 8) {
                        service.autoStatus(
                                "畫面尚未確認，不執行點擊，稍後重新判斷"
                        );
                        scheduleCycle(settings.scanIntervalMs, token);
                    } else {
                        handler.postDelayed(
                                () -> inspectCurrentScreen(
                                        token,
                                        unknownCount + 1,
                                        0
                                ),
                                OPEN_SCREEN_POLL_MS
                        );
                    }
                    break;
            }
        });
    }

    private void beginMapScan(Bitmap firstFrame, int token) {
        if (!isCurrent(token)) {
            recycle(firstFrame);
            return;
        }
        recycleMapFrames();
        mapFrames.add(firstFrame);
        handler.postDelayed(
                () -> captureMapFrame(token),
                MAP_FRAME_GAP_MS
        );
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
                AutoScreenAnalyzer.findMapTarget(
                        mapFrames,
                        activeBlockedPoints()
                );
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
                        () -> inspectOpenedScreen(token, 0, 0, 0, 0),
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
            int unknownCount,
            int closeCount
    ) {
        if (!isCurrent(token)) {
            return;
        }
        takeScreenshot(token, bitmap -> {
            AutoScreenAnalyzer.ScreenState state =
                    AutoScreenAnalyzer.classifyAfterTap(bitmap, mapBeforeTap);
            Log.i(
                    LOG_TAG,
                    "opened-screen state=" + state +
                            " poll=" + pollCount +
                            " closeConfirm=" + closeCount +
                            " target=" +
                            (lastTarget == null ? "none" : lastTarget.type)
            );
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
                                0,
                                0
                        );
                    }
                    break;
                case MAP_RETURNED:
                    recycle(bitmap);
                    mapBeforeTap = null;
                    blockLastTarget();
                    service.autoStatus(
                            "未進入捕捉畫面，該區域暫停 1 分鐘並繼續掃描"
                    );
                    scheduleCycle(settings.scanIntervalMs, token);
                    break;
                case HAS_CLOSE_BUTTON:
                    if (closeCount < 1) {
                        recycle(bitmap);
                        pollOpenedScreen(
                                token,
                                pollCount,
                                0,
                                0,
                                closeCount + 1
                        );
                        break;
                    }
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
                    recycle(bitmap);
                    mapBeforeTap = null;
                    blockLastTarget();
                    service.autoStatus(settings.rocketDescription);
                    runRocketTap(token, 0);
                    break;
                case UNKNOWN:
                default:
                    recycle(bitmap);
                    int nextUnknownCount = unknownCount + 1;
                    if (pollCount + 1 >= MAX_OPEN_SCREEN_POLLS) {
                        mapBeforeTap = null;
                        blockLastTarget();
                        service.autoStatus(
                                "點擊後未確認畫面，該區域暫停 1 分鐘"
                        );
                        scheduleCycle(settings.scanIntervalMs, token);
                    } else {
                        pollOpenedScreen(
                                token,
                                pollCount,
                                0,
                                nextUnknownCount,
                                0
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
            int unknownCount,
            int closeCount
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
                        unknownCount,
                        closeCount
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
        if (screenshotPending) {
            handler.postDelayed(
                    () -> playCatchGesture(token, retryCount),
                    150L
            );
            return;
        }
        List<GestureLayer> catchLayers =
                AutoCatchGestureStore.load(service);
        if (catchLayers.isEmpty()) {
            service.autoStatus(
                    "尚未套用全自動捕捉手勢，請回主畫面按「套用目前手勢至全自動」"
            );
            stop();
            return;
        }
        handler.removeCallbacksAndMessages(null);
        Log.i(
                LOG_TAG,
                "catch-dispatch source=snapshot layers=" + catchLayers.size() +
                        " fingerprint=" +
                        GestureIdentity.fingerprint(catchLayers)
        );
        recycleMapFrames();
        mapBeforeTap = null;
        lastTarget = null;
        service.autoStatus(
                "播放捕捉手勢：" + catchLayers.size() +
                        " 條，識別碼 " +
                        GestureIdentity.fingerprint(catchLayers)
        );
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
                        if (retryCount < MAX_CATCH_RETRIES) {
                            service.autoStatus(
                                    "捕捉手勢被取消，確認畫面後重試"
                            );
                            handler.postDelayed(
                                    () -> confirmEncounterAndRetry(
                                            token,
                                            retryCount + 1
                                    ),
                                    CATCH_RETRY_DELAY_MS
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

    private void confirmEncounterAndRetry(int token, int retryCount) {
        if (!isCurrent(token)) {
            return;
        }
        takeScreenshot(token, bitmap -> {
            AutoScreenAnalyzer.ScreenState state =
                    AutoScreenAnalyzer.classifyAfterTap(bitmap, null);
            recycle(bitmap);
            if (state == AutoScreenAnalyzer.ScreenState.ENCOUNTER) {
                playCatchGesture(token, retryCount);
            } else {
                service.autoStatus("已離開捕捉畫面，不再重試手勢");
                scheduleCycle(
                        AutoSettings.load(service).scanIntervalMs,
                        token
                );
            }
        });
    }

    private void blockLastTarget() {
        if (lastTarget == null) {
            return;
        }
        blockedTargets.add(
                new BlockedTarget(
                        new PointF(lastTarget.point.x, lastTarget.point.y),
                        System.currentTimeMillis() +
                                NON_ENCOUNTER_COOLDOWN_MS
                )
        );
        if (blockedTargets.size() > 40) {
            blockedTargets.remove(0);
        }
        lastTarget = null;
    }

    private List<PointF> activeBlockedPoints() {
        long now = System.currentTimeMillis();
        List<PointF> result = new ArrayList<>();
        for (int i = blockedTargets.size() - 1; i >= 0; i--) {
            BlockedTarget target = blockedTargets.get(i);
            if (target.expiresAt <= now) {
                blockedTargets.remove(i);
            } else {
                result.add(target.point);
            }
        }
        return result;
    }

    private PointF screenSize() {
        android.graphics.Point point = ScreenDimensions.get(service);
        return new PointF(point.x, point.y);
    }

    private void takeScreenshot(int token, BitmapReceiver receiver) {
        if (!isCurrent(token)) {
            return;
        }
        long elapsed = System.currentTimeMillis() - lastScreenshotRequestAt;
        if (elapsed < MIN_SCREENSHOT_INTERVAL_MS) {
            handler.postDelayed(
                    () -> takeScreenshot(token, receiver),
                    MIN_SCREENSHOT_INTERVAL_MS - elapsed
            );
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
        lastScreenshotRequestAt = System.currentTimeMillis();
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
                                        MIN_SCREENSHOT_INTERVAL_MS
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

    private static final class BlockedTarget {
        final PointF point;
        final long expiresAt;

        BlockedTarget(PointF point, long expiresAt) {
            this.point = point;
            this.expiresAt = expiresAt;
        }
    }
}
