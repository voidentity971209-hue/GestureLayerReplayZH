#!/usr/bin/env python3
"""Train the exact EfficientDet-Lite output format accepted by the Android app."""

from __future__ import annotations

import argparse
from pathlib import Path


LABEL_MAP = {
    1: "pokemon",
    2: "pokestop",
    3: "gym",
    4: "power_spot",
}


def load_split(root: Path, split: str, object_detector):
    directory = root / split
    return object_detector.DataLoader.from_pascal_voc(
        str(directory / "images"),
        str(directory / "annotations"),
        LABEL_MAP,
    )


def verify_model(model_path: Path, tf) -> None:
    interpreter = tf.lite.Interpreter(model_path=str(model_path))
    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()
    if len(inputs) != 1:
        raise RuntimeError(f"Expected one input, got {len(inputs)}")
    shape = list(inputs[0]["shape"])
    if len(shape) != 4 or shape[0] != 1 or shape[3] != 3:
        raise RuntimeError(f"Unsupported input shape: {shape}")
    shapes = [list(item["shape"]) for item in outputs]
    has_boxes = any(len(shape) == 3 and shape[0] == 1 and shape[2] == 4 for shape in shapes)
    vector_count = sum(1 for shape in shapes if len(shape) == 2 and shape[0] == 1)
    if not has_boxes or vector_count < 2:
        raise RuntimeError(f"Unsupported DetectionPostProcess outputs: {shapes}")
    print("Compatibility check passed")
    print("Input:", inputs[0]["shape"], inputs[0]["dtype"])
    for index, item in enumerate(outputs):
        print("Output", index, item["name"], item["shape"], item["dtype"])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=Path("dataset"))
    parser.add_argument("--output", type=Path, default=Path("trained-model"))
    parser.add_argument("--epochs", type=int, default=50)
    parser.add_argument("--batch-size", type=int, default=8)
    args = parser.parse_args()

    try:
        import tensorflow as tf
        from tflite_model_maker import model_spec, object_detector
        from tflite_model_maker.config import ExportFormat
    except (ModuleNotFoundError, ImportError) as error:
        raise SystemExit(
            "訓練套件尚未正確安裝。Windows 請直接雙擊 train_windows.cmd；"
            "它會使用 Python 3.10 建立環境並安裝相容版本。\n"
            f"原始錯誤：{error}"
        ) from error

    for split in ("train", "validation", "test"):
        split_root = args.dataset / split
        if not (split_root / "images").is_dir() or not (
                split_root / "annotations").is_dir():
            raise SystemExit(
                f"資料集缺少 {split}。請先執行 prepare_dataset.py。"
            )

    train = load_split(args.dataset, "train", object_detector)
    validation = load_split(args.dataset, "validation", object_detector)
    test = load_split(args.dataset, "test", object_detector)
    spec = model_spec.get("efficientdet_lite0")
    spec.config.num_epochs = max(1, args.epochs)
    model = object_detector.create(
        train,
        model_spec=spec,
        batch_size=max(1, args.batch_size),
        train_whole_model=True,
        validation_data=validation,
    )
    print("Keras evaluation:", model.evaluate(test))
    args.output.mkdir(parents=True, exist_ok=True)
    filename = "pokemon-detector-v001.tflite"
    model.export(
        export_dir=str(args.output),
        tflite_filename=filename,
        export_format=[ExportFormat.TFLITE, ExportFormat.LABEL],
    )
    model_path = args.output / filename
    print("TFLite evaluation:", model.evaluate_tflite(str(model_path), test))
    verify_model(model_path, tf)
    print("Ready to import into the app:", model_path.resolve())


if __name__ == "__main__":
    main()
