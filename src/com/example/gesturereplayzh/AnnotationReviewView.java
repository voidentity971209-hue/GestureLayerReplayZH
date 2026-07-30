package com.example.gesturereplayzh;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class AnnotationReviewView extends View {
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap bitmap;
    private RectF normalizedBox = new RectF(0.4f, 0.4f, 0.6f, 0.6f);
    private RectF imageDestination = new RectF();

    AnnotationReviewView(Context context) {
        super(context);
        boxPaint.setColor(0xFFFFD600);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(
                3f * getResources().getDisplayMetrics().density
        );
        setBackgroundColor(Color.BLACK);
    }

    void setSample(Bitmap value, RectF box) {
        if (bitmap != null && bitmap != value && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
        bitmap = value;
        normalizedBox = new RectF(box);
        invalidate();
    }

    RectF getNormalizedBox() {
        return new RectF(normalizedBox);
    }

    void move(float deltaX, float deltaY) {
        float width = normalizedBox.width();
        float height = normalizedBox.height();
        float centerX = clamp(normalizedBox.centerX() + deltaX);
        float centerY = clamp(normalizedBox.centerY() + deltaY);
        normalizedBox.set(
                centerX - width / 2f,
                centerY - height / 2f,
                centerX + width / 2f,
                centerY + height / 2f
        );
        keepInside();
        invalidate();
    }

    void scale(float factor) {
        float halfWidth = Math.max(
                0.01f,
                Math.min(0.48f, normalizedBox.width() * factor / 2f)
        );
        float halfHeight = Math.max(
                0.01f,
                Math.min(0.48f, normalizedBox.height() * factor / 2f)
        );
        float centerX = normalizedBox.centerX();
        float centerY = normalizedBox.centerY();
        normalizedBox.set(
                centerX - halfWidth,
                centerY - halfHeight,
                centerX + halfWidth,
                centerY + halfHeight
        );
        keepInside();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        float scale = Math.min(
                getWidth() / (float) bitmap.getWidth(),
                getHeight() / (float) bitmap.getHeight()
        );
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;
        imageDestination.set(left, top, left + width, top + height);
        canvas.drawBitmap(bitmap, null, imageDestination, imagePaint);
        RectF box = new RectF(
                imageDestination.left +
                        normalizedBox.left * imageDestination.width(),
                imageDestination.top +
                        normalizedBox.top * imageDestination.height(),
                imageDestination.left +
                        normalizedBox.right * imageDestination.width(),
                imageDestination.top +
                        normalizedBox.bottom * imageDestination.height()
        );
        canvas.drawRect(box, boxPaint);
        canvas.drawCircle(
                box.centerX(),
                box.centerY(),
                5f * getResources().getDisplayMetrics().density,
                boxPaint
        );
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN ||
                imageDestination.width() <= 0f) {
            return true;
        }
        float x = (
                event.getX() - imageDestination.left
        ) / imageDestination.width();
        float y = (
                event.getY() - imageDestination.top
        ) / imageDestination.height();
        float width = normalizedBox.width();
        float height = normalizedBox.height();
        normalizedBox.set(
                x - width / 2f,
                y - height / 2f,
                x + width / 2f,
                y + height / 2f
        );
        keepInside();
        invalidate();
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
        bitmap = null;
        super.onDetachedFromWindow();
    }

    private void keepInside() {
        if (normalizedBox.left < 0f) {
            normalizedBox.offset(-normalizedBox.left, 0f);
        }
        if (normalizedBox.top < 0f) {
            normalizedBox.offset(0f, -normalizedBox.top);
        }
        if (normalizedBox.right > 1f) {
            normalizedBox.offset(1f - normalizedBox.right, 0f);
        }
        if (normalizedBox.bottom > 1f) {
            normalizedBox.offset(0f, 1f - normalizedBox.bottom);
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
