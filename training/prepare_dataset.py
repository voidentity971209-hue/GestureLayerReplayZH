#!/usr/bin/env python3
"""Convert an exported ModelEventStore ZIP into split Pascal VOC data."""

from __future__ import annotations

import argparse
import csv
import hashlib
import shutil
import tempfile
import zipfile
from pathlib import Path
from xml.etree.ElementTree import Element, SubElement, ElementTree


LABELS = ("pokemon", "pokestop", "gym", "power_spot")


def split_for(filename: str) -> str:
    value = int(hashlib.sha256(filename.encode("utf-8")).hexdigest()[:8], 16) % 100
    if value < 75:
        return "train"
    if value < 90:
        return "validation"
    return "test"


def integer(row: dict[str, str], key: str, default: int = 0) -> int:
    value = (row.get(key) or "").strip()
    return int(float(value)) if value else default


def write_voc(row: dict[str, str], destination: Path) -> None:
    width = integer(row, "width", 1)
    height = integer(row, "height", 1)
    filename = row["filename"]
    root = Element("annotation")
    SubElement(root, "filename").text = filename
    size = SubElement(root, "size")
    SubElement(size, "width").text = str(width)
    SubElement(size, "height").text = str(height)
    SubElement(size, "depth").text = "3"

    label = (row.get("class") or "").strip()
    if label:
        if label not in LABELS:
            raise ValueError(f"Unsupported class {label!r} in {filename}")
        xmin = max(0, min(width - 1, integer(row, "xmin")))
        ymin = max(0, min(height - 1, integer(row, "ymin")))
        xmax = max(xmin + 1, min(width, integer(row, "xmax")))
        ymax = max(ymin + 1, min(height, integer(row, "ymax")))
        obj = SubElement(root, "object")
        SubElement(obj, "name").text = label
        SubElement(obj, "pose").text = "Unspecified"
        SubElement(obj, "truncated").text = "0"
        SubElement(obj, "difficult").text = "0"
        box = SubElement(obj, "bndbox")
        SubElement(box, "xmin").text = str(xmin)
        SubElement(box, "ymin").text = str(ymin)
        SubElement(box, "xmax").text = str(xmax)
        SubElement(box, "ymax").text = str(ymax)

    ElementTree(root).write(destination, encoding="utf-8", xml_declaration=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("export_zip", type=Path)
    parser.add_argument("--output", type=Path, default=Path("dataset"))
    args = parser.parse_args()

    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="gesture-model-") as temporary:
        extracted = Path(temporary)
        with zipfile.ZipFile(args.export_zip) as archive:
            archive.extractall(extracted)
        csv_path = extracted / "annotations.csv"
        images_path = extracted / "images"
        if not csv_path.is_file() or not images_path.is_dir():
            raise SystemExit("ZIP 缺少 annotations.csv 或 images/")

        counts = {name: 0 for name in ("train", "validation", "test")}
        class_counts = {label: 0 for label in LABELS}
        class_counts["background"] = 0
        with csv_path.open("r", encoding="utf-8-sig", newline="") as source:
            for row in csv.DictReader(source):
                filename = Path(row["filename"]).name
                source_image = images_path / filename
                if not source_image.is_file():
                    raise FileNotFoundError(source_image)
                split = split_for(filename)
                image_dir = output / split / "images"
                annotation_dir = output / split / "annotations"
                image_dir.mkdir(parents=True, exist_ok=True)
                annotation_dir.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source_image, image_dir / filename)
                write_voc(row, annotation_dir / f"{Path(filename).stem}.xml")
                counts[split] += 1
                label = (row.get("class") or "").strip() or "background"
                class_counts[label] += 1

    print("Dataset:", output)
    print("Split counts:", counts)
    print("Class counts:", class_counts)
    if counts["train"] < 50:
        print("WARNING: 訓練集太小；建議先批改至少 300 張，並持續加入困難誤判。")
    if counts["validation"] == 0 or counts["test"] == 0:
        print("WARNING: 驗證集或測試集為空，請累積更多資料。")


if __name__ == "__main__":
    main()
