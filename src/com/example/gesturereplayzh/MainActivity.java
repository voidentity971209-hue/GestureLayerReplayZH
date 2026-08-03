package com.example.gesturereplayzh;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_EXPORT_BACKUP = 1001;

    private final Handler handler = new Handler();
    private LinearLayout layerContainer;
    private LinearLayout versionContainer;
    private TextView serviceStatus;
    private TextView catchGestureInfo;
    private List<GestureLayer> layers;
    private List<SavedVersionStore.SavedVersion> versions;
    private Runnable pendingPlayback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LegacyModelDataCleanup.run(this);
        BuiltInGestureStore.ensureInstalled(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void buildUi() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(0xFFF7F5F0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(36));
        scrollView.addView(root);

        TextView title = text(
                isExperimentalBuild()
                        ? "刷機"
                        : "大師球手勢模擬器",
                28f,
                Color.BLACK
        );
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text(
                isExperimentalBuild()
                        ? "錄製、保存與播放手勢，也可啟動 Pokémon 條列自動操作。"
                        : "分開錄製每一條滑動，再疊加成同一個手勢播放。永久免費、免 Root、不含廣告。",
                16f,
                0xFF444444
        );
        subtitle.setPadding(0, dp(8), 0, dp(16));
        root.addView(subtitle);

        serviceStatus = text("", 15f, 0xFF555555);
        serviceStatus.setPadding(dp(12), dp(10), dp(12), dp(10));
        serviceStatus.setBackgroundColor(0xFFE8EAF6);
        root.addView(serviceStatus);

        root.addView(sectionTitle(
                "① 手勢模擬與編輯",
                "錄製、疊加、排序與播放手勢。這一區不會判斷畫面內容。"
        ));

        Button record = button("＋ 錄製新軌跡");
        record.setOnClickListener(v ->
                startActivity(new Intent(this, RecordActivity.class)));
        root.addView(record);

        Button floating = button("顯示其他 App 上方的懸浮控制器");
        floating.setOnClickListener(v -> {
            GestureAccessibilityService service = GestureAccessibilityService.getInstance();
            if (service == null) {
                toast("請先啟用無障礙手勢服務");
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } else {
                service.showFloatingControls();
                moveTaskToBack(true);
            }
        });
        root.addView(floating);

        TextView timelineTitle = text("已儲存軌跡", 20f, Color.BLACK);
        timelineTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        timelineTitle.setPadding(0, dp(20), 0, dp(8));
        root.addView(timelineTitle);

        TextView help = text(
                "起點與長度都能用 0.1 秒為單位慢慢微調。",
                14f,
                0xFF666666
        );
        help.setPadding(0, 0, 0, dp(8));
        root.addView(help);

        layerContainer = new LinearLayout(this);
        layerContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(layerContainer);

        TextView versionTitle = text("保存版本", 20f, Color.BLACK);
        versionTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        versionTitle.setPadding(0, dp(20), 0, dp(8));
        root.addView(versionTitle);

        TextView versionHelp = text(
                "可保存目前全部軌跡，之後套用、覆蓋更新或刪除。",
                14f,
                0xFF666666
        );
        root.addView(versionHelp);

        Button saveVersion = button("保存目前版本");
        saveVersion.setOnClickListener(v -> saveCurrentVersion());
        root.addView(saveVersion);

        versionContainer = new LinearLayout(this);
        versionContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(versionContainer);

        Button playOnce = button("播放一次");
        playOnce.setOnClickListener(v -> play(false));
        root.addView(playOnce);

        Button playLoop = button("循環播放");
        playLoop.setOnClickListener(v -> play(true));
        root.addView(playLoop);

        Button stop = button("停止播放");
        stop.setOnClickListener(v -> stopPlayback());
        root.addView(stop);

        TextView warning = text(
                "播放前先開啟目標 App，再切回本程式按播放；本程式會退到背景，1.2 秒後執行。播放期間手動觸控可能取消手勢。請勿用於密碼、付款或違反服務規則的操作。",
                14f,
                0xFF7A3E00
        );
        warning.setPadding(dp(12), dp(16), dp(12), 0);
        root.addView(warning);

        if (isExperimentalBuild()) {
            root.addView(sectionTitle(
                    "② 自動操作",
                    "全畫面尋找固定 Pokémon 長條；白色＝只用來定位，程式不會點擊它。"
            ));

            TextView autoWarning = infoBox(
                    "開始前請把地圖調成最大視野、最高角度。自動操作仍可能誤判。",
                    0xFFFFF3E0,
                    0xFF7A3E00
            );
            root.addView(autoWarning);

            CheckBox autoEnabled = new CheckBox(this);
            autoEnabled.setText("允許啟動自動操作");
            autoEnabled.setChecked(AutoSettings.load(this).autoEnabled);
            autoEnabled.setOnCheckedChangeListener((buttonView, checked) -> {
                AutoSettings settings = AutoSettings.load(this);
                settings.autoEnabled = checked;
                settings.save(this);
                toast(checked ? "已允許自動操作" : "已停用自動操作");
            });
            root.addView(autoEnabled);

            Button autoSettings = button("調整自動操作設定");
            autoSettings.setOnClickListener(v -> showAutoSettingsDialog());
            root.addView(autoSettings);

            catchGestureInfo = text("", 14f, 0xFF455A64);
            catchGestureInfo.setPadding(dp(12), dp(8), dp(12), dp(8));
            root.addView(catchGestureInfo);

        }

        root.addView(sectionTitle(
                isExperimentalBuild() ? "③ 權限與備份" : "② 權限與備份",
                "管理無障礙服務並匯出手勢資料。更新 App 不會主動清除已保存手勢。"
        ));

        Button accessibility = button("開啟無障礙服務設定");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility);

        Button exportBackup = button("匯出手勢備份（JSON）");
        exportBackup.setOnClickListener(v -> chooseBackupDestination());
        root.addView(exportBackup);

        setContentView(scrollView);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            stopPlayback();
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK ||
                data == null ||
                data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_EXPORT_BACKUP) {
            writeBackup(data.getData());
        }
    }

    private void chooseBackupDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(
                Intent.EXTRA_TITLE,
                "GestureLayerReplayZH-backup.json"
        );
        startActivityForResult(intent, REQUEST_EXPORT_BACKUP);
    }

    private void writeBackup(Uri destination) {
        try (OutputStream output =
                     getContentResolver().openOutputStream(destination, "wt")) {
            if (output == null) {
                toast("無法建立備份檔案");
                return;
            }
            String content = GestureBackup.create(this).toString(2);
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.flush();
            toast("手勢備份已匯出");
        } catch (IOException | JSONException error) {
            toast("匯出失敗：" + error.getMessage());
        }
    }

    private void refresh() {
        layers = GestureStore.load(this);
        boolean enabled = GestureAccessibilityService.getInstance() != null;
        serviceStatus.setText(enabled
                ? "狀態：無障礙手勢服務已啟用"
                : "狀態：尚未啟用無障礙手勢服務");
        serviceStatus.setTextColor(enabled ? 0xFF1B5E20 : 0xFFB71C1C);

        layerContainer.removeAllViews();
        if (layers.isEmpty()) {
            TextView empty = text("尚未錄製任何軌跡", 15f, 0xFF777777);
            empty.setPadding(dp(12), dp(14), dp(12), dp(14));
            layerContainer.addView(empty);
        } else {
            for (int i = 0; i < layers.size(); i++) {
                addLayerRow(i, layers.get(i));
            }
        }
        refreshVersions();
        refreshCatchGestureInfo();
    }

    private void addLayerRow(int index, GestureLayer layer) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, dp(5), 0, dp(5));
        layerContainer.addView(card, cardParams);

        TextView name = text(
                layer.name + "　長度 " + formatSeconds(layer.durationMs) +
                        " 秒　起點 " + formatSeconds(layer.startDelayMs) + " 秒",
                16f,
                Color.BLACK
        );
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(name);

        LinearLayout orderControls = new LinearLayout(this);
        orderControls.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(orderControls);

        TextView orderLabel = text("順序", 14f, 0xFF555555);
        orderControls.addView(orderLabel, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0.8f
        ));

        Button moveUp = compactButton("上移");
        moveUp.setEnabled(index > 0);
        moveUp.setOnClickListener(v -> {
            Collections.swap(layers, index, index - 1);
            renumberLayers();
            GestureStore.save(this, layers);
            refresh();
        });
        orderControls.addView(moveUp);

        Button moveDown = compactButton("下移");
        moveDown.setEnabled(index < layers.size() - 1);
        moveDown.setOnClickListener(v -> {
            Collections.swap(layers, index, index + 1);
            renumberLayers();
            GestureStore.save(this, layers);
            refresh();
        });
        orderControls.addView(moveDown);

        LinearLayout startControls = new LinearLayout(this);
        startControls.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(startControls);

        TextView startLabel = text("起點", 14f, 0xFF555555);
        startControls.addView(startLabel, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0.8f
        ));

        Button earlier = compactButton("－0.1 秒");
        earlier.setOnClickListener(v -> {
            layer.startDelayMs = Math.max(0L, layer.startDelayMs - 100L);
            GestureStore.save(this, layers);
            refresh();
        });
        startControls.addView(earlier);

        Button later = compactButton("＋0.1 秒");
        later.setOnClickListener(v -> {
            layer.startDelayMs += 100L;
            GestureStore.save(this, layers);
            refresh();
        });
        startControls.addView(later);

        LinearLayout durationControls = new LinearLayout(this);
        durationControls.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(durationControls);

        TextView durationLabel = text("長度", 14f, 0xFF555555);
        durationControls.addView(durationLabel, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                0.8f
        ));

        Button shorter = compactButton("－0.1 秒");
        shorter.setOnClickListener(v -> {
            layer.durationMs = Math.max(100L, layer.durationMs - 100L);
            GestureStore.save(this, layers);
            refresh();
        });
        durationControls.addView(shorter);

        Button longer = compactButton("＋0.1 秒");
        longer.setOnClickListener(v -> {
            layer.durationMs += 100L;
            GestureStore.save(this, layers);
            refresh();
        });
        durationControls.addView(longer);

        Button delete = button("刪除這條軌跡");
        delete.setTextColor(0xFFB71C1C);
        delete.setOnClickListener(v -> {
            layers.remove(index);
            renumberLayers();
            GestureStore.save(this, layers);
            refresh();
        });
        card.addView(delete);
    }

    private void renumberLayers() {
        for (int i = 0; i < layers.size(); i++) {
            layers.get(i).name = "軌跡 " + (i + 1);
        }
    }

    private void saveCurrentVersion() {
        layers = GestureStore.load(this);
        if (layers.isEmpty()) {
            toast("目前沒有可保存的軌跡");
            return;
        }
        versions = SavedVersionStore.load(this);
        String suggestedName = "保存版本 " + (versions.size() + 1);
        EditText input = versionNameInput(suggestedName);
        new AlertDialog.Builder(this)
                .setTitle("命名保存版本")
                .setMessage("輸入方便辨認的版本名稱")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = suggestedName;
                    }
                    SavedVersionStore.SavedVersion version =
                            SavedVersionStore.create(name, layers);
                    versions.add(version);
                    SavedVersionStore.save(this, versions);
                    refreshVersions();
                    toast("已保存：" + name);
                })
                .show();
    }

    private void refreshVersions() {
        versions = SavedVersionStore.load(this);
        versionContainer.removeAllViews();
        if (versions.isEmpty()) {
            TextView empty = text("尚未保存任何版本", 15f, 0xFF777777);
            empty.setPadding(dp(12), dp(14), dp(12), dp(14));
            versionContainer.addView(empty);
            return;
        }
        for (int i = 0; i < versions.size(); i++) {
            addVersionRow(i, versions.get(i));
        }
    }

    private void addVersionRow(
            int index,
            SavedVersionStore.SavedVersion version
    ) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(0xFFE8F5E9);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, dp(5), 0, dp(5));
        versionContainer.addView(card, cardParams);

        String savedTime = version.savedAt > 0
                ? new SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN)
                        .format(new Date(version.savedAt))
                : "";
        TextView title = text(
                version.name + "　" + version.layers.size() +
                        " 條軌跡　" + savedTime,
                15f,
                Color.BLACK
        );
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(controls);

        Button apply = compactButton("套用");
        apply.setOnClickListener(v -> {
            layers = SavedVersionStore.copyLayers(version.layers);
            renumberLayers();
            GestureStore.save(this, layers);
            refresh();
            toast("已套用 " + version.name);
        });
        controls.addView(apply);

        Button overwrite = compactButton("更新保存");
        overwrite.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("覆蓋保存版本？")
                .setMessage("將用目前畫面中的全部軌跡取代 " + version.name)
                .setNegativeButton("取消", null)
                .setPositiveButton("覆蓋", (dialog, which) -> {
                    layers = GestureStore.load(this);
                    version.layers = SavedVersionStore.copyLayers(layers);
                    version.savedAt = System.currentTimeMillis();
                    SavedVersionStore.save(this, versions);
                    refreshVersions();
                    toast("已更新保存");
                })
                .show());
        controls.addView(overwrite);

        LinearLayout manageControls = new LinearLayout(this);
        manageControls.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(manageControls);

        Button rename = compactButton("改名");
        rename.setOnClickListener(v -> {
            EditText input = versionNameInput(version.name);
            new AlertDialog.Builder(this)
                    .setTitle("修改版本名稱")
                    .setView(input)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("保存名稱", (dialog, which) -> {
                        String name = input.getText().toString().trim();
                        if (name.isEmpty()) {
                            toast("名稱不能空白");
                            return;
                        }
                        version.name = name;
                        SavedVersionStore.save(this, versions);
                        refreshVersions();
                        toast("已改名");
                    })
                    .show();
        });
        manageControls.addView(rename);

        Button delete = compactButton("刪除");
        delete.setTextColor(0xFFB71C1C);
        delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("刪除保存版本？")
                .setMessage(version.name + " 將被刪除，目前正在編輯的軌跡不受影響。")
                .setNegativeButton("取消", null)
                .setPositiveButton("刪除", (dialog, which) -> {
                    versions.remove(index);
                    SavedVersionStore.save(this, versions);
                    refreshVersions();
                })
                .show());
        manageControls.addView(delete);
    }

    private EditText versionNameInput(String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setSelection(value.length());
        input.setHint("例如：寶可夢測試版");
        int horizontalPadding = dp(20);
        input.setPadding(
                horizontalPadding,
                input.getPaddingTop(),
                horizontalPadding,
                input.getPaddingBottom()
        );
        return input;
    }

    private void showAutoSettingsDialog() {
        AutoSettings settings = AutoSettings.load(this);
        LinearLayout dialogContent = new LinearLayout(this);
        dialogContent.setOrientation(LinearLayout.VERTICAL);
        dialogContent.setPadding(dp(8), 0, dp(8), dp(8));
        dialogContent.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                Math.round(
                        getResources().getDisplayMetrics().heightPixels * 0.72f
                )
        ));

        TextView explanation = text(
                "只保留目前流程會使用的等待時間與次數。畫面辨識規則固定。",
                14f,
                0xFF555555
        );
        explanation.setPadding(dp(12), dp(6), dp(12), dp(6));
        dialogContent.addView(explanation);

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        dialogContent.addView(scroll, scrollParams);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(20), dp(8), dp(20), dp(8));
        scroll.addView(fields);

        EditText scanInterval = addNumberField(
                fields,
                "掃描間隔（秒，0.1 為單位）",
                settings.scanIntervalMs / 1000f
        );
        addFieldHelp(
                fields,
                "兩輪地圖掃描之間等待多久。數值越小反應越快，" +
                        "但耗電、發熱與畫面尚未穩定就誤判的機率也會增加。"
        );
        EditText recognitionFrameCount = addIntegerField(
                fields,
                "畫面判定連續截圖數（1～5）",
                settings.recognitionFrameCount
        );
        addFieldHelp(
                fields,
                "預設 1 張最省電；提高數量會較慢。條列位置每次動作後都會重新掃描。"
        );
        EditText listTapCount = addIntegerField(
                fields,
                "條列候選連續點擊次數（1～4）",
                settings.listTapCount
        );
        EditText listTapInterval = addNumberField(
                fields,
                "條列連點間隔（秒）",
                settings.listTapIntervalMs / 1000f
        );
        EditText postTapClassifyDelay = addNumberField(
                fields,
                "點擊候選後等待辨識（秒）",
                settings.postTapClassifyDelayMs / 1000f
        );
        EditText groundMoveWait = addNumberField(
                fields,
                "點擊附近地板後等待（秒）",
                settings.groundMoveWaitMs / 1000f
        );
        EditText groundMoveRetries = addIntegerField(
                fields,
                "附近地板最多候選數（1～8）",
                settings.groundMoveRetries
        );
        EditText unknownTimeout = addNumberField(
                fields,
                "未知畫面安全停止時間（秒）",
                settings.unknownTimeoutMs / 1000f
        );
        EditText rocketInterval = addNumberField(
                fields,
                "火箭隊每次對話點擊間隔（秒）",
                settings.rocketTapIntervalMs / 1000f
        );
        addFieldHelp(
                fields,
                "每次推進火箭隊對話之間的等待時間。太短可能漏掉尚未出現的對話。"
        );
        EditText rocketCount = addIntegerField(
                fields,
                "火箭隊對話點擊次數",
                settings.rocketTapCount
        );
        addFieldHelp(fields, "進入火箭隊畫面後，先點擊對話區幾次再尋找 X。");
        EditText beforeCatch = addNumberField(
                fields,
                "確認捕捉畫面後等待（秒）",
                settings.beforeCatchMs / 1000f
        );
        addFieldHelp(
                fields,
                "確認捕捉介面成立後，等待畫面與球穩定再播放捕捉手勢。"
        );
        EditText afterCatch = addNumberField(
                fields,
                "播放捕捉手勢後等待（秒）",
                settings.afterCatchMs / 1000f
        );
        addFieldHelp(
                fields,
                "手勢送出後等待結果動畫的時間；之後程式才會重新判斷畫面。"
        );
        EditText afterExit = addNumberField(
                fields,
                "按 X 後等待（秒）",
                settings.afterExitMs / 1000f
        );
        addFieldHelp(
                fields,
                "退出補給站、道館或其他頁面後，等待地圖重新顯示的時間。"
        );
        TextView catchSourceNotice = text(
                "捕捉時會播放主畫面的「目前手勢」，" +
                        "不會讀取任何保存版本。",
                14f,
                0xFF1B5E20
        );
        catchSourceNotice.setPadding(dp(8), dp(12), dp(8), dp(12));
        fields.addView(catchSourceNotice);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(4), dp(4), dp(4), 0);
        Button reset = compactButton("恢復預設");
        Button cancel = compactButton("取消");
        Button save = compactButton("保存");
        actions.addView(reset);
        actions.addView(cancel);
        actions.addView(save);
        dialogContent.addView(actions);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("自動操作設定")
                .setView(dialogContent)
                .create();

        reset.setOnClickListener(v -> {
            AutoSettings.reset(this);
            dialog.dismiss();
            refreshCatchGestureInfo();
            toast("已恢復預設操作設定");
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            settings.scanIntervalMs =
                    seconds(scanInterval, settings.scanIntervalMs);
            settings.recognitionFrameCount = integerValue(
                    recognitionFrameCount,
                    settings.recognitionFrameCount,
                    1,
                    5
            );
            settings.listTapCount = integerValue(
                    listTapCount, settings.listTapCount, 1, 4);
            settings.listTapIntervalMs = seconds(
                    listTapInterval, settings.listTapIntervalMs);
            settings.postTapClassifyDelayMs = seconds(
                    postTapClassifyDelay, settings.postTapClassifyDelayMs);
            settings.groundMoveWaitMs = seconds(
                    groundMoveWait, settings.groundMoveWaitMs);
            settings.groundMoveRetries = integerValue(
                    groundMoveRetries, settings.groundMoveRetries, 1, 8);
            settings.unknownTimeoutMs = seconds(
                    unknownTimeout,
                    settings.unknownTimeoutMs
            );
            settings.rocketTapIntervalMs =
                    seconds(rocketInterval, settings.rocketTapIntervalMs);
            settings.beforeCatchMs =
                    seconds(beforeCatch, settings.beforeCatchMs);
            settings.afterCatchMs =
                    seconds(afterCatch, settings.afterCatchMs);
            settings.afterExitMs =
                    seconds(afterExit, settings.afterExitMs);
            settings.rocketTapCount =
                    integerValue(rocketCount, settings.rocketTapCount, 1, 5);
            settings.save(this);
            dialog.dismiss();
            refreshCatchGestureInfo();
            toast("已保存自動操作設定");
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(
                    Math.round(
                            getResources().getDisplayMetrics().widthPixels * 0.94f
                    ),
                    Math.round(
                            getResources().getDisplayMetrics().heightPixels * 0.86f
                    )
            );
        }
    }

    private void refreshCatchGestureInfo() {
        if (catchGestureInfo == null) {
            return;
        }
        List<GestureLayer> currentLayers = GestureStore.load(this);
        if (currentLayers.isEmpty()) {
            catchGestureInfo.setText(
                    "自動捕捉：目前沒有可播放軌跡。\n" +
                            "請先錄製手勢或套用一個保存版本。"
            );
            return;
        }
        long totalDuration = GestureIdentity.totalDuration(currentLayers);
        catchGestureInfo.setText(
                "自動捕捉會播放目前手勢：" + currentLayers.size() +
                        " 條，總長 " + formatSeconds(totalDuration) + " 秒。"
        );
    }

    private EditText addTextField(
            LinearLayout parent,
            String label,
            String value
    ) {
        parent.addView(text(label, 13f, 0xFF555555));
        EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setMaxLines(3);
        input.setText(value);
        parent.addView(input);
        return input;
    }

    private void addFieldHelp(LinearLayout parent, String value) {
        TextView help = text(value, 12f, 0xFF6D6D6D);
        help.setPadding(0, 0, 0, dp(10));
        parent.addView(help);
    }

    private EditText addNumberField(
            LinearLayout parent,
            String label,
            float value
    ) {
        parent.addView(text(label, 13f, 0xFF555555));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(
                InputType.TYPE_CLASS_NUMBER |
                        InputType.TYPE_NUMBER_FLAG_DECIMAL
        );
        input.setText(String.format(Locale.TAIWAN, "%.1f", value));
        parent.addView(input);
        return input;
    }

    private EditText addIntegerField(
            LinearLayout parent,
            String label,
            int value
    ) {
        parent.addView(text(label, 13f, 0xFF555555));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(value));
        parent.addView(input);
        return input;
    }

    private String nonEmpty(EditText input, String fallback) {
        String value = input.getText().toString().trim();
        return value.isEmpty() ? fallback : value;
    }

    private long seconds(EditText input, long fallback) {
        try {
            float value = Float.parseFloat(input.getText().toString());
            return Math.max(
                    100L,
                    Math.min(60000L, Math.round(value * 10f) * 100L)
            );
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int integerValue(
            EditText input,
            int fallback,
            int minimum,
            int maximum
    ) {
        try {
            return Math.max(
                    minimum,
                    Math.min(maximum, Integer.parseInt(input.getText().toString()))
            );
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private float numberValue(EditText input, float fallback) {
        try {
            return Float.parseFloat(input.getText().toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private float percentage(EditText input, float fallback) {
        try {
            float value = Float.parseFloat(input.getText().toString()) / 100f;
            return Math.max(0.02f, Math.min(0.98f, value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean isExperimentalBuild() {
        return getPackageName().endsWith(".autoexperimental");
    }

    private void play(boolean loop) {
        layers = GestureStore.load(this);
        if (layers.isEmpty()) {
            toast("請先錄製至少一條軌跡");
            return;
        }
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) {
            toast("請先啟用無障礙手勢服務");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        moveTaskToBack(true);
        cancelPendingPlayback();
        pendingPlayback = () -> {
            pendingPlayback = null;
            service.play(GestureStore.load(this), loop);
        };
        handler.postDelayed(pendingPlayback, 1200L);
    }

    private void stopPlayback() {
        cancelPendingPlayback();
        GestureAccessibilityService service =
                GestureAccessibilityService.getInstance();
        if (service != null) {
            service.stopPlayback();
        } else {
            toast("服務尚未啟用");
        }
    }

    private void cancelPendingPlayback() {
        if (pendingPlayback != null) {
            handler.removeCallbacks(pendingPlayback);
            pendingPlayback = null;
        }
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.15f);
        return view;
    }

    private TextView sectionTitle(String title, String description) {
        TextView view = text(
                title + "\n" + description,
                14f,
                0xFF303F9F
        );
        view.setTextSize(14f);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        view.setBackgroundColor(0xFFE8EAF6);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(24), 0, dp(8));
        view.setLayoutParams(params);
        return view;
    }

    private TextView infoBox(String value, int backgroundColor, int textColor) {
        TextView view = text(value, 14f, textColor);
        view.setPadding(dp(12), dp(12), dp(12), dp(12));
        view.setBackgroundColor(backgroundColor);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(4), 0, dp(8));
        view.setLayoutParams(params);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(16f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
        );
        params.setMargins(0, dp(6), 0, dp(6));
        button.setLayoutParams(params);
        return button;
    }

    private Button compactButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(13f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                dp(48),
                1f
        );
        params.setMargins(dp(2), dp(6), dp(2), 0);
        button.setLayoutParams(params);
        return button;
    }

    private String formatSeconds(long milliseconds) {
        return String.format(java.util.Locale.TAIWAN, "%.1f", milliseconds / 1000f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
