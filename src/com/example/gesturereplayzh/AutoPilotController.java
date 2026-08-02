package com.example.gesturereplayzh;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.PointF;
import android.graphics.RectF;
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
    private static final long MAP_FRAME_GAP_MS = 200L;
    private static final long MIN_SCREENSHOT_INTERVAL_MS = 100L;
    private static final long POST_TAP_CLASSIFY_DELAY_MS = 450L;
    private static final long OPEN_SCREEN_POLL_MS = 180L;
    private static final int MAX_OPEN_SCREEN_POLLS = 5;
    private static final int REQUIRED_ENCOUNTER_POLLS = 1;
    private static final int MAX_CATCH_RETRIES = 2;
    private static final long CATCH_RETRY_DELAY_MS = 900L;
    private static final long NON_ENCOUNTER_COOLDOWN_MS = 12_000L;

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
    private TfliteObjectDetector modelDetector;
    private String loadedModelToken = "none";
    private String lastModelEventId;

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
        ModelEventStore.discardIncomplete(service, lastModelEventId);
        active = false;
        generation++;
        screenshotPending = false;
        screenshotFailures = 0;
        handler.removeCallbacksAndMessages(null);
        recycleMapFrames();
        mapBeforeTap = null;
        lastTarget = null;
        lastModelEventId = null;
        closeModelDetector();
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
                case ENCOUNTER_WAIT:
                    recycle(bitmap);
                    service.autoStatus("捕捉畫面正在進場或結算，等待可投球狀態");
                    handler.postDelayed(
                            () -> inspectCurrentScreen(token, 0, 0),
                            OPEN_SCREEN_POLL_MS
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
                    PointF currentClose =
                            AutoScreenAnalyzer.findCloseButton(bitmap);
                    recycle(bitmap);
                    service.autoStatus(settings.exitDescription);
                    service.dispatchAutoTap(
                            currentClose == null
                                    ? width * settings.exitXRatio
                                    : currentClose.x,
                            currentClose == null
                                    ? height * settings.exitYRatio
                                    : currentClose.y,
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
        AutoSettings settings = AutoSettings.load(service);
        Bitmap lastFrame = mapFrames.get(mapFrames.size() - 1);
        AutoScreenAnalyzer.TargetCandidate target;
        boolean trackedRingWasPresent = false;
        if (settings.detectorMode == AutoSettings.DETECTOR_LEGACY) {
            target = AutoScreenAnalyzer.findMapTarget(
                    mapFrames,
                    activeBlockedPoints(),
                    settings.pokemonOnlyMode
            );
        } else {
            AutoScreenAnalyzer.RingScanResult ringScan =
                    AutoScreenAnalyzer.findTrackedWhiteRingTarget(
                            mapFrames,
                            activeBlockedPoints()
                    );
            trackedRingWasPresent = ringScan.sawTrackedRing;
            target = trackedRingWasPresent
                    ? ringScan.target
                    : findModelTarget(lastFrame, settings);
            if (!trackedRingWasPresent &&
                    target == null &&
                    !ModelManager.hasActive(service)) {
                recycleMapFrames();
                service.autoStatus(
                        "尚未匯入 TFLite 模型；為避免亂點，模型模式暫停掃描"
                );
                scheduleCycle(1200L, token);
                return;
            }
        }
        if (target == null) {
            recycleMapFrames();
            scheduleCycle(settings.scanIntervalMs, token);
            return;
        }

        lastTarget = target;
        mapBeforeTap = AutoScreenAnalyzer.signature(lastFrame);
        if (settings.collectModelEvents) {
            boolean modelTarget = target.fromModel();
            String eventLabel = modelTarget
                    ? target.modelLabel
                    : target.type == AutoScreenAnalyzer.TargetType.POKEMON
                    ? "pokemon"
                    : "pokestop";
            RectF eventBox = modelTarget
                    ? target.modelRoiBox
                    : TfliteObjectDetector.estimatedRoiBox(
                            lastFrame.getWidth(),
                            lastFrame.getHeight(),
                            target.point.x,
                            target.point.y,
                            target.type ==
                                    AutoScreenAnalyzer.TargetType.POKEMON
                                    ? 0.13f : 0.18f,
                            target.type ==
                                    AutoScreenAnalyzer.TargetType.POKEMON
                                    ? 0.17f : 0.22f
                    );
            TfliteObjectDetector.Detection eventDetection =
                    new TfliteObjectDetector.Detection(
                            eventLabel,
                            0,
                            target.confidence,
                            eventBox,
                            new RectF()
                    );
            lastModelEventId = ModelEventStore.begin(
                    service,
                    lastFrame,
                    eventDetection,
                    settings
            );
        } else {
            lastModelEventId = null;
        }
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
        ModelEventStore.discardIncomplete(service, lastModelEventId);
        mapBeforeTap = null;
        lastTarget = null;
        lastModelEventId = null;
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
                    int nextEncounterCount = encounterCount + 1;
                    if (nextEncounterCount >= REQUIRED_ENCOUNTER_POLLS) {
                        completeModelEvent(bitmap, "encounter", settings);
                        recycle(bitmap);
                        mapBeforeTap = null;
                        lastTarget = null;
                        service.autoStatus(settings.encounterDescription);
                        handler.postDelayed(
                                () -> playCatchGesture(token, 0),
                                settings.beforeCatchMs
                        );
                    } else {
                        recycle(bitmap);
                        pollOpenedScreen(
                                token,
                                pollCount,
                                nextEncounterCount,
                                0,
                                0
                        );
                    }
                    break;
                case ENCOUNTER_WAIT:
                    recycle(bitmap);
                    service.autoStatus("已進入捕捉流程，等待球與左右按鈕穩定");
                    pollOpenedScreen(
                            token,
                            pollCount,
                            0,
                            0,
                            0
                    );
                    break;
                case MAP_RETURNED:
                    completeModelEvent(bitmap, "map_unchanged", settings);
                    recycle(bitmap);
                    mapBeforeTap = null;
                    blockLastTarget();
                    service.autoStatus(
                            "未進入捕捉畫面，該小區域暫停 12 秒並繼續快掃"
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
                    PointF openedClose =
                            AutoScreenAnalyzer.findCloseButton(bitmap);
                    completeModelEvent(bitmap, "facility_or_close", settings);
                    recycle(bitmap);
                    mapBeforeTap = null;
                    blockLastTarget();
                    service.autoStatus(settings.exitDescription);
                    service.dispatchAutoTap(
                            openedClose == null
                                    ? width * settings.exitXRatio
                                    : openedClose.x,
                            openedClose == null
                                    ? height * settings.exitYRatio
                                    : openedClose.y,
                            () -> scheduleCycle(settings.afterExitMs, token)
                    );
                    break;
                case ROCKET_DIALOG:
                    completeModelEvent(bitmap, "rocket", settings);
                    recycle(bitmap);
                    mapBeforeTap = null;
                    blockLastTarget();
                    service.autoStatus(settings.rocketDescription);
                    runRocketTap(token, 0);
                    break;
                case UNKNOWN:
                default:
                    int nextUnknownCount = unknownCount + 1;
                    if (pollCount + 1 >= MAX_OPEN_SCREEN_POLLS) {
                        completeModelEvent(bitmap, "unknown", settings);
                        recycle(bitmap);
                        mapBeforeTap = null;
                        blockLastTarget();
                        service.autoStatus(
                                "未確認捕捉畫面，立即嘗試返回並繼續快掃"
                        );
                        PointF size = screenSize();
                        service.dispatchAutoTap(
                                size.x * settings.exitXRatio,
                                size.y * settings.exitYRatio,
                                () -> scheduleCycle(
                                        Math.min(300L, settings.afterExitMs),
                                        token
                                )
                        );
                    } else {
                        recycle(bitmap);
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
            takeScreenshot(token, bitmap -> {
                PointF close = AutoScreenAnalyzer.findCloseButton(bitmap);
                recycle(bitmap);
                service.dispatchAutoTap(
                        close == null
                                ? screen.x * settings.exitXRatio
                                : close.x,
                        close == null
                                ? screen.y * settings.exitYRatio
                                : close.y,
                        () -> scheduleCycle(settings.afterExitMs, token)
                );
            });
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
            } else if (state ==
                    AutoScreenAnalyzer.ScreenState.ENCOUNTER_WAIT) {
                handler.postDelayed(
                        () -> confirmEncounterAndRetry(token, retryCount),
                        OPEN_SCREEN_POLL_MS
                );
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

    private AutoScreenAnalyzer.TargetCandidate findModelTarget(
            Bitmap frame,
            AutoSettings settings
    ) {
        try {
            String currentToken = ModelManager.versionToken(service);
            if (!currentToken.equals(loadedModelToken)) {
                closeModelDetector();
                if (!ModelManager.hasActive(service)) {
                    loadedModelToken = "none";
                    return null;
                }
                modelDetector = new TfliteObjectDetector(
                        ModelManager.activeFile(service),
                        settings.modelThreads
                );
                loadedModelToken = currentToken;
            }
            if (modelDetector == null) {
                return null;
            }
            List<TfliteObjectDetector.Detection> detections =
                    modelDetector.detect(
                            frame,
                            settings.modelConfidenceThreshold,
                            settings.modelMaxResults
                    );
            float playerX = frame.getWidth() * 0.50f;
            float playerY = frame.getHeight() * 0.63f;
            float radius = frame.getWidth() * 0.38f;
            AutoScreenAnalyzer.TargetCandidate best = null;
            float bestScore = -1f;
            for (TfliteObjectDetector.Detection detection : detections) {
                if (!"pokemon".equals(detection.label)) {
                    continue;
                }
                float x = detection.screenBox.centerX();
                float y = detection.screenBox.centerY();
                float distance = (float) Math.hypot(
                        x - playerX,
                        y - playerY
                );
                if (distance > radius ||
                        isBlocked(new PointF(x, y))) {
                    continue;
                }
                float centerPreference =
                        1f - Math.min(1f, distance / radius);
                float combined = detection.score * 0.85f +
                        centerPreference * 0.15f;
                if (combined > bestScore) {
                    bestScore = combined;
                    best = new AutoScreenAnalyzer.TargetCandidate(
                            AutoScreenAnalyzer.TargetType.POKEMON,
                            new PointF(x, y),
                            detection.score,
                            detection.roiBox,
                            detection.label
                    );
                }
            }
            return best;
        } catch (Exception error) {
            Log.e(LOG_TAG, "TFLite inference failed", error);
            closeModelDetector();
            service.autoStatus(
                    "TFLite 模型推論失敗：" + error.getMessage()
            );
            return null;
        }
    }

    private boolean isBlocked(PointF point) {
        float radius = screenSize().x * 0.07f;
        for (PointF blocked : activeBlockedPoints()) {
            if (Math.hypot(
                    point.x - blocked.x,
                    point.y - blocked.y
            ) <= radius) {
                return true;
            }
        }
        return false;
    }

    private void completeModelEvent(
            Bitmap after,
            String outcome,
            AutoSettings settings
    ) {
        if (lastModelEventId == null) {
            return;
        }
        ModelEventStore.complete(
                service,
                lastModelEventId,
                after,
                outcome,
                settings
        );
        lastModelEventId = null;
    }

    private void closeModelDetector() {
        if (modelDetector != null) {
            try {
                modelDetector.close();
            } catch (Exception ignored) {
                // Closing must not interrupt the controller.
            }
            modelDetector = null;
        }
        loadedModelToken = "none";
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
