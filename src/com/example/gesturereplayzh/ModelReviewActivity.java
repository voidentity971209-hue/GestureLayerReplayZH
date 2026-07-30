package com.example.gesturereplayzh;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ModelReviewActivity extends Activity {
    private static final int REQUEST_EXPORT = 2201;
    private List<ModelEventStore.Event> events;
    private int index;
    private AnnotationReviewView reviewView;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        reload();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(14), dp(12), dp(14));
        root.setBackgroundColor(0xFFF7F5F0);

        TextView title = text("模型事件批改", 24f, 0xFF111111);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        status = text("", 14f, 0xFF444444);
        status.setPadding(0, dp(6), 0, dp(8));
        root.addView(status);

        reviewView = new AnnotationReviewView(this);
        root.addView(
                reviewView,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        TextView help = text(
                "黃框是模型候選框。可直接點圖片移動框中心，" +
                        "或用下方按鈕微調；選擇分類後會自動前往下一筆。",
                12f,
                0xFF666666
        );
        help.setPadding(0, dp(6), 0, dp(4));
        root.addView(help);

        LinearLayout adjust1 = row();
        addCompact(adjust1, "←", () -> reviewView.move(-0.02f, 0f));
        addCompact(adjust1, "↑", () -> reviewView.move(0f, -0.02f));
        addCompact(adjust1, "↓", () -> reviewView.move(0f, 0.02f));
        addCompact(adjust1, "→", () -> reviewView.move(0.02f, 0f));
        root.addView(adjust1);
        LinearLayout adjust2 = row();
        addCompact(adjust2, "縮小框", () -> reviewView.scale(0.90f));
        addCompact(adjust2, "放大框", () -> reviewView.scale(1.10f));
        addCompact(adjust2, "點擊後畫面", this::showAfterImage);
        addCompact(adjust2, "刪除此筆", this::deleteCurrent);
        root.addView(adjust2);

        ScrollView buttonsScroll = new ScrollView(this);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        buttonsScroll.addView(labels);
        LinearLayout.LayoutParams buttonArea =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(190)
                );
        root.addView(buttonsScroll, buttonArea);

        LinearLayout labels1 = row();
        addCompact(labels1, "寶可夢", () -> review("pokemon"));
        addCompact(labels1, "地面／背景", () -> review("background"));
        labels.addView(labels1);
        LinearLayout labels2 = row();
        addCompact(labels2, "補給站", () -> review("pokestop"));
        addCompact(labels2, "道館", () -> review("gym"));
        addCompact(labels2, "能量點", () -> review("power_spot"));
        labels.addView(labels2);
        LinearLayout labels3 = row();
        addCompact(labels3, "夥伴／玩家", () -> review("background"));
        addCompact(labels3, "框不正確", () -> review("box_wrong"));
        addCompact(labels3, "無法判斷", () -> review("unknown"));
        labels.addView(labels3);

        LinearLayout navigation = row();
        addCompact(navigation, "上一筆", this::previous);
        addCompact(navigation, "重新載入", this::reload);
        addCompact(navigation, "匯出已批改 ZIP", this::chooseExport);
        root.addView(navigation);
        setContentView(root);
    }

    private void reload() {
        events = ModelEventStore.loadEvents(this, true);
        index = 0;
        showCurrent();
    }

    private void showCurrent() {
        if (events == null || events.isEmpty()) {
            status.setText(
                    "沒有待批改事件。可返回模型中心匯出已完成的資料。"
            );
            reviewView.setSample(null, new android.graphics.RectF());
            return;
        }
        index = Math.max(0, Math.min(index, events.size() - 1));
        ModelEventStore.Event event = events.get(index);
        Bitmap image = BitmapFactory.decodeFile(
                event.beforeImage().getAbsolutePath()
        );
        reviewView.setSample(image, event.box());
        status.setText(
                (index + 1) + " / " + events.size() +
                        "　自動結果：" +
                        event.json.optString("automaticOutcome") +
                        "\n模型預測：" +
                        event.json.optString("predictedClass") +
                        "　信任值：" +
                        Math.round(
                                event.json.optDouble("score") * 100
                        ) + "%"
        );
    }

    private void review(String label) {
        if (events == null || events.isEmpty()) {
            return;
        }
        try {
            ModelEventStore.saveReview(
                    events.get(index),
                    label,
                    reviewView.getNormalizedBox()
            );
            events.remove(index);
            if (index >= events.size()) {
                index = Math.max(0, events.size() - 1);
            }
            showCurrent();
        } catch (Exception error) {
            toast("保存批改失敗：" + error.getMessage());
        }
    }

    private void deleteCurrent() {
        if (events == null || events.isEmpty()) {
            return;
        }
        ModelEventStore.deleteEvent(events.get(index));
        events.remove(index);
        if (index >= events.size()) {
            index = Math.max(0, events.size() - 1);
        }
        showCurrent();
    }

    private void showAfterImage() {
        if (events == null || events.isEmpty()) {
            return;
        }
        java.io.File after = new java.io.File(
                events.get(index).directory,
                "after.jpg"
        );
        Bitmap bitmap = BitmapFactory.decodeFile(after.getAbsolutePath());
        if (bitmap == null) {
            toast("這筆事件沒有點擊後畫面");
            return;
        }
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setImageBitmap(bitmap);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("點擊後畫面")
                .setView(image)
                .setPositiveButton("關閉", null)
                .create();
        dialog.setOnDismissListener(ignored -> {
            image.setImageDrawable(null);
            bitmap.recycle();
        });
        dialog.show();
    }

    private void previous() {
        if (events != null && !events.isEmpty()) {
            index = Math.max(0, index - 1);
            showCurrent();
        }
    }

    private void chooseExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(
                Intent.EXTRA_TITLE,
                "model-training-" +
                        new SimpleDateFormat(
                                "yyyyMMdd-HHmm",
                                Locale.TAIWAN
                        ).format(new Date()) +
                        ".zip"
        );
        startActivityForResult(intent, REQUEST_EXPORT);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT ||
                resultCode != RESULT_OK ||
                data == null ||
                data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try (OutputStream output =
                     getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) {
                throw new IllegalStateException("無法建立 ZIP");
            }
            ModelEventStore.exportReviewed(this, output);
            toast("已匯出批改資料");
        } catch (Exception error) {
            toast("匯出失敗：" + error.getMessage());
        }
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void addCompact(
            LinearLayout parent,
            String label,
            Runnable action
    ) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12f);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, dp(46), 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        parent.addView(button, params);
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density
        );
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
