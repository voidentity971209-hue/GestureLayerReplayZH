package com.example.gesturereplayzh;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class GestureAccessibilityService extends AccessibilityService {
    private static final String LOG_TAG = "GestureReplayAuto";
    interface AutoGestureCallback {
        void onCompleted();
        void onCancelled();
    }

    interface AutoTapCallback {
        void onCompleted();
        void onCancelled();
    }

    private static volatile GestureAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private WindowManager windowManager;
    private View floatingControls;
    private WindowManager.LayoutParams floatingParams;
    private boolean gestureInFlight;
    private AutoPilotController autoPilotController;
    private Button autoControlButton;
    private long lastDrivingConfirmationAt;

    static GestureAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        if (isExperimentalBuild()) {
            autoPilotController = new AutoPilotController(this);
        }
        showFloatingControls();
        toast("手勢服務已啟用");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (autoPilotController == null ||
                !autoPilotController.isActive() ||
                System.currentTimeMillis() - lastDrivingConfirmationAt < 5000L) {
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo confirmation = findDrivingConfirmation(root);
        if (confirmation == null) {
            return;
        }
        AccessibilityNodeInfo clickable = confirmation;
        while (clickable != null && !clickable.isClickable()) {
            clickable = clickable.getParent();
        }
        if (clickable != null &&
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            lastDrivingConfirmationAt = System.currentTimeMillis();
            autoPilotController.onDrivingPromptConfirmed();
            return;
        }
        Rect bounds = new Rect();
        confirmation.getBoundsInScreen(bounds);
        if (!bounds.isEmpty()) {
            lastDrivingConfirmationAt = System.currentTimeMillis();
            dispatchAutoTap(
                    bounds.exactCenterX(),
                    bounds.exactCenterY(),
                    () -> {
                        if (autoPilotController != null) {
                            autoPilotController.onDrivingPromptConfirmed();
                        }
                    }
            );
        }
    }

    private AccessibilityNodeInfo findDrivingConfirmation(
            AccessibilityNodeInfo node
    ) {
        if (node == null) {
            return null;
        }
        if (isDrivingConfirmationText(node.getText()) ||
                isDrivingConfirmationText(node.getContentDescription())) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found =
                    findDrivingConfirmation(node.getChild(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean isDrivingConfirmationText(CharSequence value) {
        if (value == null) {
            return false;
        }
        String text = value.toString().toLowerCase(java.util.Locale.ROOT);
        return text.contains("我不是駕駛") ||
                text.contains("我沒有在駕駛") ||
                text.contains("我是乘客") ||
                text.contains("i'm a passenger") ||
                text.contains("i’m a passenger") ||
                text.contains("im a passenger") ||
                text.contains("sou passageiro");
    }

    @Override
    public void onInterrupt() {
        stopPlayback();
    }

    @Override
    public void onDestroy() {
        hideFloatingControls();
        if (instance == this) {
            instance = null;
        }
        stopPlayback();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopPlayback();
        super.onTaskRemoved(rootIntent);
    }

    void stopPlayback() {
        if (autoPilotController != null) {
            autoPilotController.stop();
        }
        setAutoPanelRunning(false);
        cancelGesturePlayback();
        toast("已停止播放");
    }

    private void cancelGesturePlayback() {
        generation.incrementAndGet();
        handler.removeCallbacksAndMessages(null);
        boolean shouldCancelGesture = gestureInFlight;
        gestureInFlight = false;
        if (shouldCancelGesture) {
            cancelCurrentGesture();
        }
    }

    void play(List<GestureLayer> layers, boolean loop) {
        if (autoPilotController != null) {
            autoPilotController.stop();
        }
        playInternal(layers, loop, null);
    }

    void playAutoGesture(
            List<GestureLayer> layers,
            AutoGestureCallback callback
    ) {
        Log.i(
                LOG_TAG,
                "playAutoGesture layers=" + layers.size() +
                        " fingerprint=" + GestureIdentity.fingerprint(layers)
        );
        playInternal(layers, false, callback);
    }

    private void playInternal(
            List<GestureLayer> layers,
            boolean loop,
            AutoGestureCallback callback
    ) {
        final int token = generation.incrementAndGet();
        handler.removeCallbacksAndMessages(null);
        dispatchLayers(layers, loop, token, callback);
    }

    void showFloatingControls() {
        if (floatingControls != null) {
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(4), dp(4), dp(4), dp(4));
        panel.setBackgroundColor(0xEEFFF8E1);

        Button drag = floatingButton("≡");
        Button record = floatingButton("錄");
        Button playOnce = floatingButton("播");
        Button playLoop = floatingButton("循");
        Button stop = floatingButton("停");
        Button auto = isExperimentalBuild() ? floatingButton("自") : null;
        autoControlButton = auto;
        Button close = floatingButton("×");
        panel.addView(drag);
        panel.addView(record);
        panel.addView(playOnce);
        panel.addView(playLoop);
        panel.addView(stop);
        if (auto != null) {
            panel.addView(auto);
        }
        panel.addView(close);

        floatingParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        floatingParams.gravity = Gravity.TOP | Gravity.END;
        floatingParams.x = dp(8);
        floatingParams.y = dp(180);

        drag.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int startX;
            private int startY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    startX = floatingParams.x;
                    startY = floatingParams.y;
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    floatingParams.x = Math.max(
                            0,
                            startX - Math.round(event.getRawX() - downRawX)
                    );
                    floatingParams.y = Math.max(
                            0,
                            startY + Math.round(event.getRawY() - downRawY)
                    );
                    windowManager.updateViewLayout(floatingControls, floatingParams);
                    return true;
                }
                return event.getActionMasked() == MotionEvent.ACTION_UP;
            }
        });
        record.setOnClickListener(view -> {
            Intent intent = new Intent(this, RecordActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                    Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
        });
        playOnce.setOnClickListener(view ->
                play(GestureStore.load(this), false));
        playLoop.setOnClickListener(view ->
                play(GestureStore.load(this), true));
        stop.setOnClickListener(view -> stopPlayback());
        if (auto != null) {
            auto.setOnClickListener(view -> {
                if (autoPilotController.isActive()) {
                    stopPlayback();
                } else {
                    if (!AutoSettings.load(this).autoEnabled) {
                        toast("請先回主程式勾選允許自動操作");
                        return;
                    }
                    cancelGesturePlayback();
                    autoPilotController.start();
                    setAutoPanelRunning(true);
                }
            });
        }
        close.setOnClickListener(view -> {
            stopPlayback();
            hideFloatingControls();
        });

        floatingControls = panel;
        windowManager.addView(floatingControls, floatingParams);
    }

    void hideFloatingControls() {
        if (floatingControls != null && windowManager != null) {
            windowManager.removeView(floatingControls);
            floatingControls = null;
            autoControlButton = null;
        }
    }

    private Button floatingButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13f);
        button.setTextColor(Color.BLACK);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(dp(46), dp(42));
        params.setMargins(dp(1), 0, dp(1), 0);
        button.setLayoutParams(params);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void dispatchLayers(
            List<GestureLayer> layers,
            boolean loop,
            int token,
            AutoGestureCallback callback
    ) {
        if (token != generation.get()) {
            notifyCancelled(callback);
            return;
        }
        if (layers.isEmpty()) {
            toast("尚未錄製軌跡");
            notifyCancelled(callback);
            return;
        }
        if (layers.size() > GestureDescription.getMaxStrokeCount()) {
            toast("軌跡數量超過此手機限制：" + GestureDescription.getMaxStrokeCount());
            notifyCancelled(callback);
            return;
        }

        GestureDescription.Builder builder = new GestureDescription.Builder();
        long totalDuration = 0L;
        Point targetScreen = ScreenDimensions.get(this);
        Log.i(
                LOG_TAG,
                "dispatchLayers token=" + token +
                        " layers=" + layers.size() +
                        " target=" + targetScreen.x + "x" + targetScreen.y
        );
        int layerIndex = 0;
        for (GestureLayer layer : layers) {
            layerIndex++;
            if (layer.points.size() < 2) {
                Log.w(LOG_TAG, "layer=" + layerIndex + " skipped points=" + layer.points.size());
                continue;
            }
            int sourceWidth = Math.max(1, layer.sourceWidth);
            int sourceHeight = Math.max(1, layer.sourceHeight);
            boolean sourceLandscape = sourceWidth > sourceHeight;
            boolean targetLandscape = targetScreen.x > targetScreen.y;
            if (sourceLandscape != targetLandscape) {
                toast("錄製與播放的畫面方向不同，請先旋轉手機");
                notifyCancelled(callback);
                return;
            }
            float scaleX = targetScreen.x / (float) sourceWidth;
            float scaleY = targetScreen.y / (float) sourceHeight;
            Path path = new Path();
            PointF first = layer.points.get(0);
            path.moveTo(
                    scaled(first.x, scaleX, targetScreen.x),
                    scaled(first.y, scaleY, targetScreen.y)
            );
            for (int i = 1; i < layer.points.size(); i++) {
                PointF point = layer.points.get(i);
                path.lineTo(
                        scaled(point.x, scaleX, targetScreen.x),
                        scaled(point.y, scaleY, targetScreen.y)
                );
            }
            long duration = Math.max(100L, layer.durationMs);
            long start = Math.max(0L, layer.startDelayMs);
            Log.i(
                    LOG_TAG,
                    "layer=" + layerIndex +
                            " startMs=" + start +
                            " durationMs=" + duration +
                            " points=" + layer.points.size() +
                            " source=" + sourceWidth + "x" + sourceHeight
            );
            totalDuration = Math.max(totalDuration, start + duration);
            builder.addStroke(new GestureDescription.StrokeDescription(path, start, duration));
        }

        if (totalDuration > GestureDescription.getMaxGestureDuration()) {
            toast("腳本長度超過此手機限制：" +
                    (GestureDescription.getMaxGestureDuration() / 1000f) + " 秒");
            notifyCancelled(callback);
            return;
        }

        boolean accepted = dispatchGesture(
                builder.build(),
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        if (token == generation.get()) {
                            gestureInFlight = false;
                            if (callback != null) {
                                callback.onCompleted();
                            }
                        }
                        if (loop && token == generation.get()) {
                            handler.postDelayed(
                                    () -> dispatchLayers(
                                            layers,
                                            true,
                                            token,
                                            callback
                                    ),
                                    GestureStore.getLoopInterval(GestureAccessibilityService.this)
                            );
                        }
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        if (token == generation.get()) {
                            gestureInFlight = false;
                            notifyCancelled(callback);
                        }
                        if (token == generation.get()) {
                            toast("手勢被系統或手動觸控取消");
                        }
                    }
                },
                null
        );
        gestureInFlight = accepted;
        if (!accepted) {
            toast("系統拒絕播放手勢");
            notifyCancelled(callback);
        }
    }

    private void notifyCancelled(AutoGestureCallback callback) {
        if (callback != null) {
            callback.onCancelled();
        }
    }

    private float scaled(float value, float scale, int targetSize) {
        return Math.max(0f, Math.min(targetSize - 1f, value * scale));
    }

    void dispatchAutoTap(float x, float y, Runnable completion) {
        dispatchAutoTap(
                x,
                y,
                new AutoTapCallback() {
                    @Override
                    public void onCompleted() {
                        completion.run();
                    }

                    @Override
                    public void onCancelled() {
                        // A cancelled tap did not happen. The caller must not
                        // advance as if it succeeded.
                    }
                }
        );
    }

    void dispatchAutoTap(
            float x,
            float y,
            AutoTapCallback callback
    ) {
        if (gestureInFlight) {
            callback.onCancelled();
            return;
        }
        Path tapPath = new Path();
        tapPath.moveTo(x, y);
        boolean accepted = dispatchGesture(
                new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(
                                tapPath,
                                0L,
                                80L
                        ))
                        .build(),
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        gestureInFlight = false;
                        callback.onCompleted();
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        gestureInFlight = false;
                        callback.onCancelled();
                    }
                },
                null
        );
        gestureInFlight = accepted;
        if (!accepted) {
            callback.onCancelled();
        }
    }

    void autoStatus(String message) {
        toast(message);
    }

    private void setAutoPanelRunning(boolean running) {
        if (!(floatingControls instanceof LinearLayout) ||
                autoControlButton == null) {
            return;
        }
        LinearLayout panel = (LinearLayout) floatingControls;
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            child.setVisibility(
                    !running || child == autoControlButton
                            ? View.VISIBLE
                            : View.GONE
            );
        }
        autoControlButton.setText(running ? "自停" : "自");
        LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) autoControlButton.getLayoutParams();
        params.width = dp(running ? 36 : 46);
        autoControlButton.setLayoutParams(params);
    }

    private boolean isExperimentalBuild() {
        return getPackageName().endsWith(".autoexperimental");
    }

    private void cancelCurrentGesture() {
        try {
            Path cancelPath = new Path();
            cancelPath.moveTo(0f, 0f);
            cancelPath.lineTo(0f, 0f);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(
                            cancelPath,
                            0L,
                            1L
                    ))
                    .build();
            dispatchGesture(gesture, null, null);
        } catch (RuntimeException ignored) {
            // The generation token still prevents another loop from starting.
        }
    }

    private void toast(String message) {
        handler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
}
