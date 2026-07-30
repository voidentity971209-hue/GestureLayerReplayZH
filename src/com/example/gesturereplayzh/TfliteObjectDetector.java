package com.example.gesturereplayzh;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.File;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TfliteObjectDetector implements AutoCloseable {
    static final float ROI_LEFT = 0.05f;
    static final float ROI_TOP = 0.13f;
    static final float ROI_RIGHT = 0.95f;
    static final float ROI_BOTTOM = 0.84f;
    private static final String[] LABELS = {
            "pokemon", "pokestop", "gym", "power_spot"
    };

    static final class Detection {
        final String label;
        final int classIndex;
        final float score;
        final RectF roiBox;
        final RectF screenBox;

        Detection(
                String label,
                int classIndex,
                float score,
                RectF roiBox,
                RectF screenBox
        ) {
            this.label = label;
            this.classIndex = classIndex;
            this.score = score;
            this.roiBox = roiBox;
            this.screenBox = screenBox;
        }
    }

    private final Interpreter interpreter;
    private final int inputWidth;
    private final int inputHeight;
    private final DataType inputType;
    private final int threadCount;

    TfliteObjectDetector(File model, int threads) throws Exception {
        threadCount = Math.max(1, Math.min(8, threads));
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(threadCount);
        try (FileInputStream input = new FileInputStream(model);
             FileChannel channel = input.getChannel()) {
            MappedByteBuffer mapped = channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0,
                    channel.size()
            );
            interpreter = new Interpreter(mapped, options);
        }
        Tensor tensor = interpreter.getInputTensor(0);
        int[] shape = tensor.shape();
        if (shape.length != 4 || shape[0] != 1 || shape[3] != 3) {
            interpreter.close();
            throw new IllegalArgumentException(
                    "輸入必須是 [1, 高, 寬, 3]"
            );
        }
        inputHeight = shape[1];
        inputWidth = shape[2];
        inputType = tensor.dataType();
        if (inputType != DataType.UINT8 &&
                inputType != DataType.FLOAT32) {
            interpreter.close();
            throw new IllegalArgumentException(
                    "目前只支援 UINT8 或 FLOAT32 RGB 模型"
            );
        }
        validateOutputs();
    }

    String modelSummary() {
        return "輸入 " + inputWidth + "×" + inputHeight +
                " " + inputType +
                "，輸出符合物件偵測格式";
    }

    List<Detection> detect(
            Bitmap screen,
            float threshold,
            int maxResults
    ) {
        int screenWidth = screen.getWidth();
        int screenHeight = screen.getHeight();
        Rect crop = roiRect(screenWidth, screenHeight);
        Bitmap roi = Bitmap.createBitmap(
                screen,
                crop.left,
                crop.top,
                crop.width(),
                crop.height()
        );
        Bitmap scaled = Bitmap.createScaledBitmap(
                roi,
                inputWidth,
                inputHeight,
                true
        );
        if (scaled != roi) {
            roi.recycle();
        }

        ByteBuffer input = imageBuffer(scaled);
        scaled.recycle();
        OutputBundle outputs = allocateOutputs();
        interpreter.runForMultipleInputsOutputs(
                new Object[]{input},
                outputs.outputMap
        );
        outputs.resolveClassAndScoreIndices(interpreter);

        float[][][] boxes = outputs.boxes;
        float[][] scores = outputs.valuesByIndex.get(outputs.scoreIndex);
        float[][] classes = outputs.valuesByIndex.get(outputs.classIndex);
        int count = boxes[0].length;
        if (outputs.count != null && outputs.count.length > 0) {
            count = Math.min(count, Math.max(0, Math.round(outputs.count[0])));
        }

        List<Detection> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float score = scores[0][i];
            if (score < threshold) {
                continue;
            }
            int rawClassId = Math.round(classes[0][i]);
            // Model Maker / EfficientDet exports Pascal VOC labels from 1.
            // A zero is also accepted for a one-class or zero-based Pokémon model.
            int classId = rawClassId == 0 ? 0 : rawClassId - 1;
            if (classId < 0 || classId >= LABELS.length) {
                continue;
            }
            float top = clamp(boxes[0][i][0]);
            float left = clamp(boxes[0][i][1]);
            float bottom = clamp(boxes[0][i][2]);
            float right = clamp(boxes[0][i][3]);
            if (right <= left || bottom <= top) {
                continue;
            }
            RectF roiBox = new RectF(left, top, right, bottom);
            RectF screenBox = new RectF(
                    crop.left + left * crop.width(),
                    crop.top + top * crop.height(),
                    crop.left + right * crop.width(),
                    crop.top + bottom * crop.height()
            );
            result.add(new Detection(
                    LABELS[classId],
                    classId,
                    score,
                    roiBox,
                    screenBox
            ));
            if (result.size() >= Math.max(1, maxResults)) {
                break;
            }
        }
        return result;
    }

    static Bitmap cropTrainingRoi(Bitmap screen) {
        Rect crop = roiRect(screen.getWidth(), screen.getHeight());
        return Bitmap.createBitmap(
                screen,
                crop.left,
                crop.top,
                crop.width(),
                crop.height()
        );
    }

    static RectF estimatedRoiBox(
            int screenWidth,
            int screenHeight,
            float screenX,
            float screenY,
            float widthRatio,
            float heightRatio
    ) {
        Rect crop = roiRect(screenWidth, screenHeight);
        float centerX = (screenX - crop.left) / Math.max(1f, crop.width());
        float centerY = (screenY - crop.top) / Math.max(1f, crop.height());
        float halfWidth = widthRatio / 2f;
        float halfHeight = heightRatio / 2f;
        return new RectF(
                clamp(centerX - halfWidth),
                clamp(centerY - halfHeight),
                clamp(centerX + halfWidth),
                clamp(centerY + halfHeight)
        );
    }

    private ByteBuffer imageBuffer(Bitmap bitmap) {
        int bytesPerValue = inputType == DataType.FLOAT32 ? 4 : 1;
        ByteBuffer buffer = ByteBuffer.allocateDirect(
                inputWidth * inputHeight * 3 * bytesPerValue
        );
        buffer.order(ByteOrder.nativeOrder());
        int[] pixels = new int[inputWidth * inputHeight];
        bitmap.getPixels(
                pixels,
                0,
                inputWidth,
                0,
                0,
                inputWidth,
                inputHeight
        );
        for (int pixel : pixels) {
            int red = (pixel >> 16) & 0xff;
            int green = (pixel >> 8) & 0xff;
            int blue = pixel & 0xff;
            if (inputType == DataType.FLOAT32) {
                buffer.putFloat(red / 255f);
                buffer.putFloat(green / 255f);
                buffer.putFloat(blue / 255f);
            } else {
                buffer.put((byte) red);
                buffer.put((byte) green);
                buffer.put((byte) blue);
            }
        }
        buffer.rewind();
        return buffer;
    }

    private OutputBundle allocateOutputs() {
        OutputBundle bundle = new OutputBundle();
        for (int index = 0; index < interpreter.getOutputTensorCount(); index++) {
            Tensor tensor = interpreter.getOutputTensor(index);
            int[] shape = tensor.shape();
            if (tensor.dataType() != DataType.FLOAT32) {
                throw new IllegalArgumentException("偵測輸出必須是 FLOAT32");
            }
            if (shape.length == 3 && shape[0] == 1 && shape[2] == 4) {
                bundle.boxIndex = index;
                bundle.boxes = new float[1][shape[1]][4];
                bundle.outputMap.put(index, bundle.boxes);
            } else if (shape.length == 2 && shape[0] == 1) {
                float[][] values = new float[1][shape[1]];
                bundle.valuesByIndex.put(index, values);
                bundle.outputMap.put(index, values);
            } else if (shape.length == 1 && shape[0] >= 1) {
                bundle.count = new float[shape[0]];
                bundle.outputMap.put(index, bundle.count);
            } else {
                throw new IllegalArgumentException(
                        "不支援的輸出形狀：" + tensor.name()
                );
            }
        }
        if (bundle.boxes == null || bundle.valuesByIndex.size() < 2) {
            throw new IllegalArgumentException("缺少 boxes、classes 或 scores");
        }
        return bundle;
    }

    private void validateOutputs() {
        OutputBundle bundle = allocateOutputs();
        if (bundle.outputMap.size() < 3) {
            throw new IllegalArgumentException("模型輸出數量不足");
        }
    }

    private static Rect roiRect(int width, int height) {
        int left = Math.max(0, Math.round(width * ROI_LEFT));
        int top = Math.max(0, Math.round(height * ROI_TOP));
        int right = Math.min(width, Math.round(width * ROI_RIGHT));
        int bottom = Math.min(height, Math.round(height * ROI_BOTTOM));
        return new Rect(left, top, right, bottom);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    public void close() {
        interpreter.close();
    }

    private static final class OutputBundle {
        final Map<Integer, Object> outputMap = new HashMap<>();
        final Map<Integer, float[][]> valuesByIndex = new HashMap<>();
        float[][][] boxes;
        float[] count;
        int boxIndex = -1;
        int scoreIndex = -1;
        int classIndex = -1;

        void resolveClassAndScoreIndices(Interpreter interpreter) {
            for (Integer index : valuesByIndex.keySet()) {
                String name = interpreter.getOutputTensor(index)
                        .name()
                        .toLowerCase(Locale.US);
                if (name.contains("score")) {
                    scoreIndex = index;
                } else if (name.contains("class")) {
                    classIndex = index;
                }
            }
            if (scoreIndex >= 0 && classIndex >= 0) {
                return;
            }
            // DetectionPostProcess convention is boxes, classes, scores, count.
            // Model Maker may preserve only generic tensor names, so prefer the
            // standard adjacent output positions before inspecting values.
            if (valuesByIndex.containsKey(boxIndex + 1) &&
                    valuesByIndex.containsKey(boxIndex + 2)) {
                classIndex = boxIndex + 1;
                scoreIndex = boxIndex + 2;
                return;
            }
            float bestIntegerRatio = -1f;
            int likelyClasses = -1;
            for (Map.Entry<Integer, float[][]> entry :
                    valuesByIndex.entrySet()) {
                float[][] values = entry.getValue();
                int checked = Math.min(20, values[0].length);
                int integerLike = 0;
                for (int i = 0; i < checked; i++) {
                    float value = values[0][i];
                    if (Math.abs(value - Math.round(value)) < 0.001f &&
                            value >= 0f &&
                            value <= 4f) {
                        integerLike++;
                    }
                }
                float ratio = checked == 0 ? 0f : integerLike / (float) checked;
                if (ratio > bestIntegerRatio) {
                    bestIntegerRatio = ratio;
                    likelyClasses = entry.getKey();
                }
            }
            classIndex = likelyClasses;
            for (Integer index : valuesByIndex.keySet()) {
                if (index != classIndex) {
                    scoreIndex = index;
                    break;
                }
            }
            if (classIndex < 0 || scoreIndex < 0) {
                throw new IllegalArgumentException("無法辨認 classes 與 scores");
            }
        }
    }
}
