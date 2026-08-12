package com.example.gesturereplayzh;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class RecordActivity extends Activity {
    private TraceView traceView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        traceView = new TraceView();
        root.addView(traceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(8), dp(12), dp(8));
        toolbar.setBackgroundColor(0xDDFFFFFF);

        TextView instruction = new TextView(this);
        instruction.setText("請用一根手指完成一次滑動，再按「儲存」");
        instruction.setTextColor(Color.BLACK);
        instruction.setTextSize(15f);
        toolbar.addView(instruction, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ));

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(v -> finish());
        toolbar.addView(cancel);

        Button save = new Button(this);
        save.setText("儲存");
        save.setOnClickListener(v -> saveLayer());
        toolbar.addView(save);

        FrameLayout.LayoutParams toolbarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        toolbarParams.gravity = Gravity.TOP;
        root.addView(toolbar, toolbarParams);
        setContentView(root);
    }

    private void saveLayer() {
        GestureLayer layer = traceView.getLayer();
        if (layer == null || layer.points.size() < 2) {
            Toast.makeText(this, "請先完成一次滑動", Toast.LENGTH_SHORT).show();
            return;
        }
        GestureStore.append(this, layer);
        setResult(RESULT_OK);
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class TraceView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path drawnPath = new Path();
        private GestureLayer layer;
        private long downTime;
        private int pointerId = -1;

        TraceView() {
            super(RecordActivity.this);
            setBackgroundColor(0x33000000);
            paint.setColor(0xFFFF3D00);
            paint.setStrokeWidth(dp(5));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawPath(drawnPath, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                layer = new GestureLayer();
                layer.startDelayMs = 0L;
                Point screen = ScreenDimensions.get(RecordActivity.this);
                layer.sourceWidth = screen.x;
                layer.sourceHeight = screen.y;
                layer.points.clear();
                drawnPath.reset();
                pointerId = event.getPointerId(0);
                downTime = event.getEventTime();
                addPoint(event.getRawX(), event.getRawY(), true);
                return true;
            }
            if (layer == null || pointerId < 0) {
                return true;
            }

            int index = event.findPointerIndex(pointerId);
            if (index < 0) {
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) {
                int historySize = event.getHistorySize();
                float rawOffsetX = event.getRawX() - event.getX();
                float rawOffsetY = event.getRawY() - event.getY();
                for (int i = 0; i < historySize; i++) {
                    addPoint(event.getHistoricalX(index, i) + rawOffsetX,
                            event.getHistoricalY(index, i) + rawOffsetY, false);
                }
                addPoint(event.getRawX(), event.getRawY(), false);
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                addPoint(event.getRawX(), event.getRawY(), false);
                layer.durationMs = Math.max(100L, event.getEventTime() - downTime);
                pointerId = -1;
                return true;
            }
            return true;
        }

        private void addPoint(float x, float y, boolean first) {
            if (!layer.points.isEmpty()) {
                PointF previous = layer.points.get(layer.points.size() - 1);
                if (Math.abs(previous.x - x) < 1f && Math.abs(previous.y - y) < 1f) {
                    return;
                }
            }
            layer.points.add(new PointF(x, y));
            if (first) {
                drawnPath.moveTo(x, y);
            } else {
                drawnPath.lineTo(x, y);
            }
            invalidate();
        }

        GestureLayer getLayer() {
            return layer;
        }
    }
}
