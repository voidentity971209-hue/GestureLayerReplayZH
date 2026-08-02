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
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
    private static final int REQUEST_IMPORT_MODEL = 1002;

    private final Handler handler = new Handler();
    private LinearLayout layerContainer;
    private LinearLayout versionContainer;
    private TextView serviceStatus;
    private TextView catchGestureInfo;
    private TextView modelStatusInfo;
    private List<GestureLayer> layers;
    private List<SavedVersionStore.SavedVersion> versions;
    private Runnable pendingPlayback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
                        ? "大師球手勢實驗室"
                        : "大師球手勢模擬器",
                28f,
                Color.BLACK
        );
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView subtitle = text(
                isExperimentalBuild()
                        ? "手勢模擬、自動操作、模型與資料各自分區。永久免費、免 Root、不含廣告。"
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
                    "② 全自動操作",
                    "控制何時掃描、捕捉、退出及重試。畫面判斷方法固定，時間與操作座標可修改。"
            ));

            TextView autoWarning = infoBox(
                    "開始前請把遊戲地圖調成最大視野、最高角度，" +
                            "並隱藏第三方功能按鈕。這是實驗功能，" +
                            "可能誤判，也可能被遊戲服務視為自動化操作。",
                    0xFFFFF3E0,
                    0xFF7A3E00
            );
            root.addView(autoWarning);

            CheckBox autoEnabled = new CheckBox(this);
            autoEnabled.setText("我了解風險，允許啟動全自動實驗功能");
            autoEnabled.setChecked(AutoSettings.load(this).autoEnabled);
            autoEnabled.setOnCheckedChangeListener((buttonView, checked) -> {
                AutoSettings settings = AutoSettings.load(this);
                settings.autoEnabled = checked;
                settings.save(this);
                toast(checked ? "已允許全自動實驗功能" : "已停用全自動實驗功能");
            });
            root.addView(autoEnabled);

            Button autoSettings = button("調整全自動操作設定");
            autoSettings.setOnClickListener(v -> showAutoSettingsDialog());
            root.addView(autoSettings);

            catchGestureInfo = text("", 14f, 0xFF455A64);
            catchGestureInfo.setPadding(dp(12), dp(8), dp(12), dp(8));
            root.addView(catchGestureInfo);

            root.addView(sectionTitle(
                    "③ 模型與錯誤資料",
                    "匯入、回復 TFLite 模型，實際點擊驗證結果，並在手機上批改與匯出訓練資料。"
            ));
            modelStatusInfo = infoBox(
                    "",
                    0xFFEDE7F6,
                    0xFF4527A0
            );
            root.addView(modelStatusInfo);
            Button modelCenter = button("開啟模型、信任值與資料中心");
            modelCenter.setOnClickListener(v -> showModelCenterDialog());
            root.addView(modelCenter);
        }

        root.addView(sectionTitle(
                isExperimentalBuild() ? "④ 權限與備份" : "② 權限與備份",
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
        } else if (requestCode == REQUEST_IMPORT_MODEL) {
            importModel(data.getData());
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
        refreshModelStatus();
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
                "畫面辨識條件不可修改；以下操作參數都可修改。",
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

        EditText scanDescription = addTextField(
                fields,
                "掃描步驟名稱／描述",
                settings.scanDescription
        );
        addFieldHelp(
                fields,
                "只改變介面顯示名稱，不會改變畫面判斷方法。"
        );
        EditText encounterDescription = addTextField(
                fields,
                "捕捉步驟名稱／描述",
                settings.encounterDescription
        );
        EditText rocketDescription = addTextField(
                fields,
                "火箭隊步驟名稱／描述",
                settings.rocketDescription
        );
        EditText exitDescription = addTextField(
                fields,
                "退出步驟名稱／描述",
                settings.exitDescription
        );
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
                "預設 1 張：捕捉、X 與地圖介面看到一次就採用，最省電。" +
                        "提高數量可降低瞬間誤判，但會更慢、更耗電。" +
                        "舊規則的寶可夢候選本身仍需要三張差異圖；" +
                        "TFLite 模型只分析單張。"
        );
        CheckBox pokemonOnlyMode = new CheckBox(this);
        pokemonOnlyMode.setText(
                "只抓寶可夢測試模式（不點補給站，建議保持開啟）"
        );
        pokemonOnlyMode.setChecked(settings.pokemonOnlyMode);
        fields.addView(pokemonOnlyMode);
        addFieldHelp(
                fields,
                "開啟後不使用補給站作為備援目標，適合測試寶可夢辨識。"
        );
        EditText legacySensitivity = addIntegerField(
                fields,
                "舊規則靈敏度（0 保守／1 平衡／2 快速）",
                settings.legacySensitivity
        );
        addFieldHelp(
                fields,
                "只改變舊規則候選門檻，不改變要抓的目標類型。" +
                        "保守可減少誤點；快速可提高找到寶可夢的機率。"
        );
        EditText scanRadius = addNumberField(
                fields,
                "玩家中心掃描半徑（螢幕寬度百分比，15～40）",
                settings.scanRadiusRatio * 100f
        );
        EditText unknownTimeout = addNumberField(
                fields,
                "未知畫面安全停止時間（秒）",
                settings.unknownTimeoutMs / 1000f
        );
        EditText closeTimeout = addNumberField(
                fields,
                "等待下方 X 最長時間（秒）",
                settings.closeTimeoutMs / 1000f
        );
        CheckBox allowFallbackExit = new CheckBox(this);
        allowFallbackExit.setText("X 逾時後允許使用備用退出座標（預設關閉）");
        allowFallbackExit.setChecked(settings.allowFallbackExit);
        fields.addView(allowFallbackExit);
        addFieldHelp(
                fields,
                "關閉時，逾時會安全停止；開啟後才會使用下方設定的退出座標。"
        );
        EditText blockedDuration = addNumberField(
                fields,
                "錯誤位置封鎖時間（秒）",
                settings.blockedDurationMs / 1000f
        );
        EditText blockedRadius = addNumberField(
                fields,
                "錯誤位置封鎖半徑（螢幕寬度百分比）",
                settings.blockedRadiusRatio * 100f
        );
        EditText blockedMaxCount = addIntegerField(
                fields,
                "最多保存錯誤位置數量（1～200）",
                settings.blockedMaxCount
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
        EditText rocketX = addNumberField(
                fields,
                "火箭隊對話點擊 X（螢幕百分比）",
                settings.rocketTapXRatio * 100f
        );
        EditText rocketY = addNumberField(
                fields,
                "火箭隊對話點擊 Y（螢幕百分比）",
                settings.rocketTapYRatio * 100f
        );
        EditText exitX = addNumberField(
                fields,
                "退出 X 座標（螢幕百分比）",
                settings.exitXRatio * 100f
        );
        EditText exitY = addNumberField(
                fields,
                "退出 Y 座標（螢幕百分比）",
                settings.exitYRatio * 100f
        );
        addFieldHelp(
                fields,
                "座標使用螢幕百分比而非固定像素；X 從左到右，Y 從上到下。" +
                        "只有開啟上方備用退出開關且等待 X 逾時時才使用。"
        );
        TextView catchSourceNotice = text(
                "捕捉手勢來源固定為本實驗版主畫面的「目前手勢」，" +
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
                .setTitle("全自動操作設定")
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
            settings.scanDescription =
                    nonEmpty(scanDescription, settings.scanDescription);
            settings.encounterDescription =
                    nonEmpty(encounterDescription, settings.encounterDescription);
            settings.rocketDescription =
                    nonEmpty(rocketDescription, settings.rocketDescription);
            settings.exitDescription =
                    nonEmpty(exitDescription, settings.exitDescription);
            settings.scanIntervalMs =
                    seconds(scanInterval, settings.scanIntervalMs);
            settings.recognitionFrameCount = integerValue(
                    recognitionFrameCount,
                    settings.recognitionFrameCount,
                    1,
                    5
            );
            settings.pokemonOnlyMode = pokemonOnlyMode.isChecked();
            settings.legacySensitivity = integerValue(
                    legacySensitivity,
                    settings.legacySensitivity,
                    0,
                    2
            );
            settings.scanRadiusRatio = Math.max(
                    0.15f,
                    Math.min(
                            0.40f,
                            percentage(scanRadius, settings.scanRadiusRatio)
                    )
            );
            settings.unknownTimeoutMs = seconds(
                    unknownTimeout,
                    settings.unknownTimeoutMs
            );
            settings.closeTimeoutMs = seconds(
                    closeTimeout,
                    settings.closeTimeoutMs
            );
            settings.allowFallbackExit = allowFallbackExit.isChecked();
            settings.blockedDurationMs = seconds(
                    blockedDuration,
                    settings.blockedDurationMs
            );
            settings.blockedRadiusRatio = Math.max(
                    0.01f,
                    Math.min(
                            0.20f,
                            percentage(
                                    blockedRadius,
                                    settings.blockedRadiusRatio
                            )
                    )
            );
            settings.blockedMaxCount = integerValue(
                    blockedMaxCount,
                    settings.blockedMaxCount,
                    1,
                    200
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
            settings.rocketTapXRatio =
                    percentage(rocketX, settings.rocketTapXRatio);
            settings.rocketTapYRatio =
                    percentage(rocketY, settings.rocketTapYRatio);
            settings.exitXRatio =
                    percentage(exitX, settings.exitXRatio);
            settings.exitYRatio =
                    percentage(exitY, settings.exitYRatio);
            settings.catchGestureSourceId =
                    AutoSettings.CURRENT_GESTURE_SOURCE;
            settings.save(this);
            dialog.dismiss();
            refreshCatchGestureInfo();
            toast("已保存全自動操作設定");
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

    private void showModelCenterDialog() {
        AutoSettings settings = AutoSettings.load(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(10), dp(22), dp(18));
        scroll.addView(content);

        content.addView(infoBox(
                ModelManager.describe(this) + "\n" +
                        "待批改事件：" +
                        ModelEventStore.pendingCount(this) +
                        "　已用空間：" +
                        String.format(
                                Locale.TAIWAN,
                                "%.1f MB",
                                ModelEventStore.storageBytes(this) /
                                        1024f / 1024f
                        ),
                0xFFE8EAF6,
                0xFF283593
        ));

        TextView modeTitle = text("地圖候選辨識模式", 17f, Color.BLACK);
        modeTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        modeTitle.setPadding(0, dp(16), 0, 0);
        content.addView(modeTitle);
        RadioGroup detectorMode = new RadioGroup(this);
        RadioButton legacy = radio(
                "既有規則：可實際點擊並冷啟動收集第一批資料",
                AutoSettings.DETECTOR_LEGACY
        );
        RadioButton verify = radio(
                "模型驗證收集：實際點擊，再保存成功或誤判",
                AutoSettings.DETECTOR_MODEL_VERIFY
        );
        detectorMode.addView(legacy);
        detectorMode.addView(verify);
        detectorMode.check(
                10_000 + (
                        settings.detectorMode ==
                                AutoSettings.DETECTOR_MODEL_VERIFY
                                ? AutoSettings.DETECTOR_MODEL_VERIFY
                                : AutoSettings.DETECTOR_LEGACY
                )
        );
        content.addView(detectorMode);

        EditText confidence = addNumberField(
                content,
                "最低信任值（百分比）",
                settings.modelConfidenceThreshold * 100f
        );
        addFieldHelp(
                content,
                "模型對每個候選輸出 0～100% 的相對分數，但不是保證正確的機率。" +
                        "門檻低會找到更多目標但誤點較多；門檻高會減少誤點但容易漏怪。" +
                        "第一版建議從 45% 開始，再依批改結果調整。"
        );
        EditText maxResults = addIntegerField(
                content,
                "每次最多保留候選數量",
                settings.modelMaxResults
        );
        addFieldHelp(
                content,
                "模型一次可能找到多個物件。程式只會從其中的 pokemon 類別，" +
                        "選擇信任值與玩家距離綜合分數最高的一個。"
        );
        EditText threads = addIntegerField(
                content,
                "模型 CPU 執行緒（1～8）",
                settings.modelThreads
        );
        addFieldHelp(
                content,
                "執行緒越多通常越快，但更耗電、容易發熱。一般先用 4。"
        );

        CheckBox collectEvents = new CheckBox(this);
        collectEvents.setText("保存規則／模型實際點擊後的驗證事件");
        collectEvents.setChecked(settings.collectModelEvents);
        content.addView(collectEvents);
        addFieldHelp(
                content,
                "保存固定 ROI、模型框、信任值與點擊後畫面。" +
                        "自動結果只是候選標籤，必須由你批改後才能匯出訓練。"
        );
        EditText dataLimit = addIntegerField(
                content,
                "資料容量上限（MB）",
                settings.dataLimitMb
        );
        addFieldHelp(
                content,
                "達到上限後停止新增事件，不會自動刪除尚未批改的資料。"
        );
        EditText positivePercent = addIntegerField(
                content,
                "成功捕捉事件保留比例（0～100%）",
                settings.positiveSamplePercent
        );
        addFieldHelp(
                content,
                "誤判全部保留；成功案例只抽樣保存，避免大量相似正確圖片" +
                        "壓過真正需要修正的錯誤。建議 20%。"
        );

        Button importModel = button("匯入並驗證 .tflite 模型");
        importModel.setOnClickListener(v -> chooseModel());
        content.addView(importModel);
        Button restore = button("回復上一版模型");
        restore.setEnabled(ModelManager.hasPrevious(this));
        restore.setOnClickListener(v -> {
            try {
                if (ModelManager.restorePrevious(this)) {
                    toast("已交換為上一版模型");
                    refreshModelStatus();
                }
            } catch (Exception error) {
                toast("回復失敗：" + error.getMessage());
            }
        });
        content.addView(restore);
        Button review = button(
                "批改事件（" + ModelEventStore.pendingCount(this) + "）"
        );
        review.setOnClickListener(v ->
                startActivity(new Intent(this, ModelReviewActivity.class)));
        content.addView(review);
        Button remove = button("停用並刪除目前模型");
        remove.setEnabled(ModelManager.hasActive(this));
        remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("刪除目前模型？上一版模型仍會保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("刪除", (dialog, which) -> {
                    ModelManager.removeActive(this);
                    AutoSettings updated = AutoSettings.load(this);
                    updated.detectorMode = AutoSettings.DETECTOR_LEGACY;
                    updated.save(this);
                    refreshModelStatus();
                    toast("目前模型已刪除，已切回既有規則");
                })
                .show());
        content.addView(remove);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = compactButton("取消");
        Button save = compactButton("保存設定");
        actions.addView(cancel);
        actions.addView(save);
        content.addView(actions);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("模型與錯誤資料中心")
                .setView(scroll)
                .create();
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            int selected =
                    detectorMode.getCheckedRadioButtonId() - 10_000;
            if (selected != AutoSettings.DETECTOR_LEGACY &&
                    !ModelManager.hasActive(this)) {
                toast("請先匯入相容的 TFLite 模型");
                return;
            }
            settings.detectorMode = selected;
            settings.modelConfidenceThreshold = Math.max(
                    0.05f,
                    Math.min(
                            0.95f,
                            numberValue(
                                    confidence,
                                    settings.modelConfidenceThreshold * 100f
                            ) / 100f
                    )
            );
            settings.modelMaxResults = integerValue(
                    maxResults,
                    settings.modelMaxResults,
                    1,
                    20
            );
            settings.modelThreads = integerValue(
                    threads,
                    settings.modelThreads,
                    1,
                    8
            );
            settings.collectModelEvents = collectEvents.isChecked();
            settings.dataLimitMb = integerValue(
                    dataLimit,
                    settings.dataLimitMb,
                    50,
                    5000
            );
            settings.positiveSamplePercent = integerValue(
                    positivePercent,
                    settings.positiveSamplePercent,
                    0,
                    100
            );
            settings.save(this);
            dialog.dismiss();
            refreshModelStatus();
            toast("模型與資料設定已保存");
        });
        dialog.show();
    }

    private RadioButton radio(String value, int id) {
        RadioButton button = new RadioButton(this);
        button.setText(value);
        button.setId(10_000 + id);
        return button;
    }

    private void chooseModel() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_IMPORT_MODEL);
    }

    private void importModel(Uri uri) {
        try {
            String summary = ModelManager.importModel(this, uri);
            refreshModelStatus();
            toast("模型已匯入：" + summary);
        } catch (Exception error) {
            toast(error.getMessage());
        }
    }

    private void refreshModelStatus() {
        if (modelStatusInfo == null) {
            return;
        }
        AutoSettings settings = AutoSettings.load(this);
        String mode;
        switch (settings.detectorMode) {
            case AutoSettings.DETECTOR_MODEL_VERIFY:
                mode = "模型驗證收集，會實際點擊";
                break;
            case AutoSettings.DETECTOR_LEGACY:
            default:
                mode = "既有手寫規則";
                break;
        }
        modelStatusInfo.setText(
                ModelManager.describe(this) +
                        "\n目前模式：" + mode +
                        "\n待批改：" +
                        ModelEventStore.pendingCount(this) +
                        "，資料 " +
                        String.format(
                                Locale.TAIWAN,
                                "%.1f MB",
                                ModelEventStore.storageBytes(this) /
                                        1024f / 1024f
                        )
        );
    }

    private void refreshCatchGestureInfo() {
        if (catchGestureInfo == null) {
            return;
        }
        List<GestureLayer> currentLayers = GestureStore.load(this);
        String currentFingerprint =
                GestureIdentity.fingerprint(currentLayers);
        if (currentLayers.isEmpty()) {
            catchGestureInfo.setText(
                    "全自動捕捉手勢：目前沒有可播放軌跡。\n" +
                            "請先錄製手勢或套用一個保存版本。"
            );
            return;
        }
        long totalDuration = GestureIdentity.totalDuration(currentLayers);
        catchGestureInfo.setText(
                "全自動會直接播放目前手勢：" + currentLayers.size() +
                        " 條，總長 " + formatSeconds(totalDuration) +
                        " 秒，指紋 " + currentFingerprint + "。\n" +
                        "修改或套用保存版本後，不必再建立捕捉快照。"
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
