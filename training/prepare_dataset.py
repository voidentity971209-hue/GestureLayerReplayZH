#!/usr/bin/env python3
"""Convert a reviewed App export ZIP into grouped Pascal VOC data."""

from __future__ import annotations

import argparse
import csv
import hashlib
import re
import shutil
import tempfile
import zipfile
from pathlib import Path
from xml.etree.ElementTree import Element, SubElement, ElementTree


LABELS = ("pokemon", "pokestop", "gym", "power_spot")
GROUP_WINDOW_MS = 30_000


def resolve_export_zip(requested: Path) -> Path:
    """Accept Windows' common accidental .zip.zip filename."""
    candidates = [requested]
    if requested.suffix.lower() == ".zip":
        candidates.append(requested.with_name(requested.name + ".zip"))
    for candidate in candidates:
        if candidate.is_file():
            return candidate.resolve()
    checked = "、".join(str(item) for item in candidates)
    raise SystemExit(f"找不到訓練 ZIP。已檢查：{checked}")


def capture_group(filename: str) -> str:
    """Keep adjacent captures together to prevent train/test leakage."""
    match = re.match(r"(\d{12,})", Path(filename).name)
    if not match:
        return Path(filename).stem
    timestamp_ms = int(match.group(1))
    return f"capture-{timestamp_ms // GROUP_WINDOW_MS}"


def split_for(filename: str) -> str:
    group = capture_group(filename)
    value = int(hashlib.sha256(group.encode("utf-8")).hexdigest()[:8], 16) % 100
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
    filename = Path(row["filename"]).name
    root = Element("annotation")
    SubElement(root, "filename").text = filename
    size = SubElement(root, "size")
    SubElement(size, "width").text = str(width)
    SubElement(size, "height").text = str(height)
    SubElement(size, "depth").text = "3"

    label = (row.get("class") or "").strip()
    if label:
        if label not in LABELS:
            raise ValueError(f"不支援的類別 {label!r}（檔案：{filename}）")
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


def find_export_root(extracted: Path) -> tuple[Path, Path]:
    matches = sorted(extracted.rglob("annotations.csv"))
    for csv_path in matches:
        images_path = csv_path.parent / "images"
        if images_path.is_dir():
            return csv_path, images_path
    raise SystemExit("ZIP 內找不到同一資料夾中的 annotations.csv 與 images/")


def prepare_output(output: Path, clean: bool) -> None:
    if output.exists() and any(output.iterdir()):
        if not clean:
            raise SystemExit(
                f"輸出資料夾已有內容：{output}\n"
                "請改用新的 --output，或加上 --clean 重新產生。"
            )
        shutil.rmtree(output)
    output.mkdir(parents=True, exist_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("export_zip", type=Path)
    parser.add_argument("--output", type=Path, default=Path("dataset"))
    parser.add_argument("--clean", action="store_true")
    args = parser.parse_args()

    export_zip = resolve_export_zip(args.export_zip)
    output = args.output.resolve()
    prepare_output(output, args.clean)
    try:
        with tempfile.TemporaryDirectory(prefix="gesture-model-") as temporary:
            extracted = Path(temporary)
            with zipfile.ZipFile(export_zip) as archive:
                archive.extractall(extracted)
            csv_path, images_path = find_export_root(extracted)

            counts = {name: 0 for name in ("train", "validation", "test")}
            class_counts = {label: 0 for label in LABELS}
            class_counts["background"] = 0
            with csv_path.open("r", encoding="utf-8-sig", newline="") as source:
                rows = list(csv.DictReader(source))
            if not rows:
                raise SystemExit("annotations.csv 沒有任何資料")

            for row in rows:
                filename = Path(row.get("filename") or "").name
                if not filename:
                    raise ValueError("annotations.csv 中有一列缺少 filename")
                source_image = images_path / filename
                if not source_image.is_file():
                    raise FileNotFoundError(f"ZIP 內缺少圖片：{filename}")
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
    except zipfile.BadZipFile as error:
        raise SystemExit(f"ZIP 已損壞或不是有效 ZIP：{export_zip}") from error

    print("訓練 ZIP：", export_zip)
    print("資料集：", output)
    print("分組數量：", counts)
    print("類別數量：", class_counts)
    if counts["train"] < 50:
        print("警告：訓練資料偏少，建議至少累積 300 張並包含大量背景誤判。")
    if counts["validation"] == 0 or counts["test"] == 0:
        print("警告：驗證或測試集為空；請再收集更多不同時間的資料。")


if __name__ == "__main__":
    main()
