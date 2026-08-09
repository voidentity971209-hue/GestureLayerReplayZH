package com.example.gesturereplayzh;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;

/** One-shot overlay that records the exact movement-arrow press point. */
final class MovementPointSelectionView extends View {
    interface Listener {
        void onSelected(PointF point);
        void onCancelled();
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Listener listener;
    private final float density;

    MovementPointSelectionView(Context context, Listener listener) {
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
        paint.setTextSize(15f * density);
        canvas.drawText(
                "點一下白色箭頭尖端；不要點圓盤中心",
                24f * density,
                49f * density,
                paint
        );
        paint.setColor(0xFFFFD600);
        paint.setTextSize(14f * density);
        canvas.drawText(
                "取消",
                getWidth() - 58f * density,
                49f * density,
                paint
        );
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_UP) {
            return true;
        }
        float x = Math.max(0f, Math.min(getWidth() - 1f, event.getX()));
        float y = Math.max(0f, Math.min(getHeight() - 1f, event.getY()));
        if (y <= 72f * density && x >= getWidth() - 92f * density) {
            listener.onCancelled();
        } else {
            listener.onSelected(new PointF(x, y));
        }
        return true;
    }
}
