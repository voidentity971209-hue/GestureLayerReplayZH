@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

echo [1/5] 檢查 Python 3.10...
if not exist ".venv\Scripts\python.exe" (
  py -3.10 -m venv .venv
  if errorlevel 1 goto :python_error
)

set "PYTHON=.venv\Scripts\python.exe"
"%PYTHON%" -c "import sys; raise SystemExit(0 if sys.version_info[:2] == (3, 10) else 1)"
if errorlevel 1 goto :python_error

echo [2/5] 安裝相容的訓練套件（第一次會花較久時間）...
"%PYTHON%" -m pip install --upgrade "pip==23.3.2"
if errorlevel 1 goto :package_error
"%PYTHON%" -m pip install -r requirements-windows.txt
if errorlevel 1 goto :package_error

set "EXPORT=model-events.zip"
if not exist "%EXPORT%" if exist "model-events.zip.zip" set "EXPORT=model-events.zip.zip"
if not exist "%EXPORT%" goto :zip_error

echo [3/5] 驗證並分割批改資料...
"%PYTHON%" prepare_dataset.py "%EXPORT%" --output dataset --clean
if errorlevel 1 goto :prepare_error

echo [4/5] 訓練 EfficientDet-Lite0...
"%PYTHON%" train_model.py --dataset dataset --output trained-model --epochs 50
if errorlevel 1 goto :train_error

echo [5/5] 完成。
echo 模型位於：%CD%\trained-model\pokemon-detector-v001.tflite
pause
exit /b 0

:python_error
echo.
echo 錯誤：找不到 64 位元 Python 3.10，或目前 .venv 不是 Python 3.10。
echo 請安裝 Python 3.10 並勾選 Add Python to PATH；若 .venv 版本錯誤，先將 .venv 改名後重試。
pause
exit /b 1

:package_error
echo.
echo 錯誤：訓練套件安裝失敗。請確認網路正常，並複製上方第一段紅字。
pause
exit /b 1

:zip_error
echo.
echo 錯誤：請把 App 匯出的 model-events.zip 放在此資料夾。
pause
exit /b 1

:prepare_error
echo.
echo 錯誤：ZIP 轉換失敗。程式同時支援 model-events.zip 與 model-events.zip.zip。
pause
exit /b 1

:train_error
echo.
echo 錯誤：模型訓練失敗。請保留本視窗並複製上方最後一段錯誤。
pause
exit /b 1
