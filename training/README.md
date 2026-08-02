# 「刷機」模型訓練

## 最簡單的 Windows 方法

1. 在 App 的「模型與錯誤資料」中批改資料並匯出 ZIP。
2. 把匯出的 `model-events.zip` 放到本資料夾。若 Windows 將名稱變成
   `model-events.zip.zip` 也可直接使用，不必改名。
3. 安裝 64 位元 Python 3.10，安裝時勾選 `Add Python to PATH`。
4. 雙擊 `train_windows.cmd`。
5. 第一次會下載 TensorFlow 等訓練套件，完成後模型位於：

```text
trained-model/pokemon-detector-v001.tflite
```

6. 回到 App 的模型中心，匯入這個 `.tflite` 檔案。

批次檔會固定使用自己的 `.venv`，不需要手動啟用虛擬環境。它也會把
pip 固定在能安裝舊版 Model Maker 相依套件的版本，避免新版 pip 顯示紅字。

## 資料類別

- `pokemon`：地圖上可點擊的寶可夢，框住完整個體。
- `pokestop`：補給站本體。
- `gym`：道館整體與其懸浮寶可夢。
- `power_spot`：極巨／能量點整體。
- 空白類別：地面、道路、海洋、樹木、玩家、選單等背景誤判。

框選規則必須一致：不要只框顏色碎片、白圈或影子；寶可夢需包含完整
身體，設施需包含足以看出整體種類的結構。錯點地板的案例應改成空白類別，
它們是降低再次誤點的重要負樣本。

## 資料分割邏輯

`prepare_dataset.py` 會把批改 ZIP 轉為 Pascal VOC：

```text
dataset/
  train/images + annotations
  validation/images + annotations
  test/images + annotations
```

連續 30 秒內的截圖視為同一組，整組只會進入 train、validation 或 test
其中一個資料夾，避免近乎相同的畫面同時出現在訓練與測試集，造成看似很高、
實際卻無法泛化的準確率。

## 手動指令（進階）

```powershell
py -3.10 -m venv .venv
.venv\Scripts\python.exe -m pip install --upgrade "pip==23.3.2"
.venv\Scripts\python.exe -m pip install -r requirements-windows.txt
.venv\Scripts\python.exe prepare_dataset.py model-events.zip --output dataset --clean
.venv\Scripts\python.exe train_model.py --dataset dataset --output trained-model --epochs 50
```

訓練程式輸出 EfficientDet-Lite0，類別順序固定為 pokemon、pokestop、gym、
power_spot，並在結束前檢查 TFLite 輸入與 DetectionPostProcess 輸出格式是否能
被 Android App 讀取。

## 持續改善流程

1. 使用新模型實際辨識。
2. 將誤點地面標為背景，把漏標或錯框修正為正確類別與完整外框。
3. 匯出下一批 ZIP，重新執行 `train_windows.cmd`。
4. 匯入新模型並保留上一版；若表現變差可在 App 中回復。

不要只收集模型已經容易辨識的畫面。不同天候、時間、地貌、手機尺寸、密集
設施以及寶可夢互相重疊的失敗案例，才是後續提升泛化能力的主要資料。
