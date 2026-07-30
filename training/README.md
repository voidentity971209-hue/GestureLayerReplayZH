# 模型資料收集與訓練

## 為何一定要點擊

只看地圖畫面，程式只能知道「模型認為這裡像寶可夢」，不能知道真相。
「模型點擊驗證」會實際點擊候選位置，再由後續固定介面判斷：

- 出現捕捉介面：暫記為 `encounter`
- 回到或仍是地圖：暫記為 `map_unchanged`
- 出現 X／設施畫面：暫記為 `facility_or_close`
- 火箭隊：暫記為 `rocket`
- 無法確定：`unknown`

這只是預先分類，不直接當正確答案。請在「模型事件批改」逐筆查看點擊前、
點擊後畫面，修正類別與框。未批改、無法確定、框錯誤的資料不會進入訓練 ZIP。

## App 操作

1. 若還沒有模型，先選「既有規則」，開啟「保存規則／模型實際點擊後的驗證事件」。
   舊規則會提供候選點與暫定框，形成第一批冷啟動資料；框不準時可在批改頁調整。
2. 累積並批改第一批資料、訓練 v001 後，匯入 `.tflite`，再改選「模型點擊驗證」。
3. 模型信任值先用 45%。太少點寶可夢就逐步降低；亂點太多就提高。
4. 讓程式操作一段時間後停止。
5. 進入「批改模型事件」：
   - 是寶可夢：選 `pokemon`，必要時移動／縮放黃色框。
   - 地面、道路、角色或其他無關物：選 `background`。
   - 補給站、道館、極巨點：選對應類別。
   - 圖框完全錯：選 `box_wrong`，它不會匯出。
6. 按「匯出已批改訓練 ZIP」。

App 使用固定中央 ROI、近似畫面去重、資料容量上限。正確寶可夢可抽樣保存，
但錯誤點擊會全部保留，讓資料集中包含足夠的「困難負樣本」。

## 轉換資料

在電腦安裝 Python 3.10，將 App 匯出的 ZIP 與本資料夾放在一起：

```bash
python prepare_dataset.py model-events.zip --output dataset
```

它會產生：

```text
dataset/
  train/images + annotations
  validation/images + annotations
  test/images + annotations
```

切分由檔名雜湊固定決定，重跑時同一張圖不會跳到另一組。`background`
會輸出成沒有 object 的 Pascal VOC XML，作為負樣本。

## 訓練

Model Maker 的相依套件較舊，建議使用獨立的 Python 3.10 環境：

```bash
python -m venv .venv
.venv\Scripts\activate
python -m pip install --upgrade pip
pip install "numpy<1.24" "tensorflow==2.12.1" "tflite-model-maker==0.4.3"
python train_model.py --dataset dataset --output trained-model --epochs 50
```

Linux/macOS 的啟用指令為 `source .venv/bin/activate`。

訓練程式會：

1. 使用 EfficientDet-Lite0。
2. 固定標籤編號：1 pokemon、2 pokestop、3 gym、4 power_spot。
3. 評估測試資料。
4. 產生 `pokemon-detector-v001.tflite`。
5. 檢查輸入與 DetectionPostProcess 輸出形狀，避免匯入不相容模型。

完成後把 `.tflite` 傳到手機，在 App 的模型中心匯入。App 會保留上一個模型，
新模型表現變差時可按「回復上一版模型」。

## Google Colab

Colab 可以執行同一套流程，但必須使用能安裝上述 Model Maker 相依套件的
Python 3.10 執行環境。上傳：

- `model-events.zip`
- `prepare_dataset.py`
- `train_model.py`

依序執行資料轉換、安裝相依套件、訓練三段指令即可。若 Colab 當時的 Python
版本不相容，請改用「連線至本機執行階段」或在電腦的 Python 3.10 虛擬環境執行；
不要任意升級 TensorFlow，否則 Model Maker 0.4.3 可能無法載入。

## 每一輪如何進化

1. 第一輪先收集至少 300 張已批改圖片，錯誤點擊不要刪掉，標成 background。
2. 訓練 v001，匯入並以 45% 信任值跑驗證模式。
3. 優先批改 v001 新產生的誤判與漏判附近樣本。
4. 把新舊已批改 ZIP 合併後重訓 v002；不可只用最新錯誤，否則會忘記原本會認的畫面。
5. 比較固定 test 集的 AP 與實際 100 次點擊成功率；兩者都較好才取代正式模型。

不要讓 App 在手機上直接用自己的預測重新訓練。未經人工確認的標籤會把錯誤
放大，形成「越訓練越會點地面」的回饋迴圈。
