package com.example.gesturereplayzh;

import android.content.Context;
import android.graphics.Point;
import android.view.WindowManager;

final class ScreenDimensions {
    private ScreenDimensions() {}

    static Point get(Context context) {
        WindowManager manager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Point size = new Point();
        if (manager != null) {
            manager.getDefaultDisplay().getRealSize(size);
        }
        if (size.x <= 0 || size.y <= 0) {
            size.x = context.getResources().getDisplayMetrics().widthPixels;
            size.y = context.getResources().getDisplayMetrics().heightPixels;
        }
        return size;
    }
}
