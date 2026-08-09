package com.example.gesturereplayzh;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** One-shot overlay used to lock the visible PGSharp list by dragging. */
final class ListRegionSelectionView extends View {
    interface Listener {
        void onSelected(RectF region);
        void onCancelled();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Listener listener;
    private final float density;
    private final RectF selection = new RectF();
    private float startX;
    private float startY;
    private boolean dragging;

    ListRegionSelectionView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.TRANSPARENT);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);

        paint.setColor(0xDD111111);
        canvas.drawRoundRect(
                12f * density,
                18f * density,
                getWidth() - 12f * density,
                66f * density,
                10f * density,
                10f * density,
                paint
        );
        paint.setColor(Color.WHITE);
        paint.setTextSize(16f * density);
        canvas.drawText(
                "拖曳框住完整條列；點一下取消",
                24f * density,
                49f * density,
                paint
        );

        if (!selection.isEmpty()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x334CAF50);
            canvas.drawRect(selection, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f * density);
            paint.setColor(0xFFFFD600);
            canvas.drawRect(selection, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = Math.max(0f, Math.min(getWidth(), event.getX()));
        float y = Math.max(0f, Math.min(getHeight(), event.getY()));
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = x;
                startY = y;
                dragging = true;
                selection.set(x, y, x, y);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return false;
                updateSelection(x, y);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) return false;
                dragging = false;
                updateSelection(x, y);
                invalidate();
                if (selection.width() < 42f * density ||
                        selection.height() < 110f * density) {
                    listener.onCancelled();
                } else {
                    listener.onSelected(new RectF(selection));
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                listener.onCancelled();
                return true;
            default:
                return true;
        }
    }

    private void updateSelection(float x, float y) {
        selection.set(
                Math.min(startX, x),
                Math.min(startY, y),
                Math.max(startX, x),
                Math.max(startY, y)
        );
    }
}
