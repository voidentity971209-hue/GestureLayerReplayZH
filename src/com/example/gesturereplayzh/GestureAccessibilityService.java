package com.example.gesturereplayzh;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class GestureAccessibilityService extends AccessibilityService {
    private static volatile GestureAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private WindowManager windowManager;
    private View floatingControls;
    private WindowManager.LayoutParams floatingParams;
    private boolean gestureInFlight;

    static GestureAccessibilityService getInstance() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        showFloatingControls();
        toast("手勢服務已啟用");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // This service does not inspect screen contents.
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
        generation.incrementAndGet();
        handler.removeCallbacksAndMessages(null);
        boolean shouldCancelGesture = gestureInFlight;
        gestureInFlight = false;
        if (shouldCancelGesture) {
            cancelCurrentGesture();
        }
        toast("已停止播放");
    }

    void play(List<GestureLayer> layers, boolean loop) {
        final int token = generation.incrementAndGet();
        handler.removeCallbacksAndMessages(null);
        dispatchLayers(layers, loop, token);
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
        Button close = floatingButton("×");
        panel.addView(drag);
        panel.addView(record);
        panel.addView(playOnce);
        panel.addView(playLoop);
        panel.addView(stop);
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

    private void dispatchLayers(List<GestureLayer> layers, boolean loop, int token) {
        if (token != generation.get()) {
            return;
        }
        if (layers.isEmpty()) {
            toast("尚未錄製軌跡");
            return;
        }
        if (layers.size() > GestureDescription.getMaxStrokeCount()) {
            toast("軌跡數量超過此手機限制：" + GestureDescription.getMaxStrokeCount());
            return;
        }

        GestureDescription.Builder builder = new GestureDescription.Builder();
        long totalDuration = 0L;
        Point targetScreen = ScreenDimensions.get(this);
        for (GestureLayer layer : layers) {
            if (layer.points.size() < 2) {
                continue;
            }
            int sourceWidth = Math.max(1, layer.sourceWidth);
            int sourceHeight = Math.max(1, layer.sourceHeight);
            boolean sourceLandscape = sourceWidth > sourceHeight;
            boolean targetLandscape = targetScreen.x > targetScreen.y;
            if (sourceLandscape != targetLandscape) {
                toast("錄製與播放的畫面方向不同，請先旋轉手機");
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
            totalDuration = Math.max(totalDuration, start + duration);
            builder.addStroke(new GestureDescription.StrokeDescription(path, start, duration));
        }

        if (totalDuration > GestureDescription.getMaxGestureDuration()) {
            toast("腳本長度超過此手機限制：" +
                    (GestureDescription.getMaxGestureDuration() / 1000f) + " 秒");
            return;
        }

        boolean accepted = dispatchGesture(
                builder.build(),
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        if (token == generation.get()) {
                            gestureInFlight = false;
                        }
                        if (loop && token == generation.get()) {
                            handler.postDelayed(
                                    () -> dispatchLayers(layers, true, token),
                                    GestureStore.getLoopInterval(GestureAccessibilityService.this)
                            );
                        }
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        if (token == generation.get()) {
                            gestureInFlight = false;
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
        }
    }

    private float scaled(float value, float scale, int targetSize) {
        return Math.max(0f, Math.min(targetSize - 1f, value * scale));
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
