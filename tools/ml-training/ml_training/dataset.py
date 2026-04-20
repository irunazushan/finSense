from __future__ import annotations

import csv
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from typing import Dict, Iterable, List

from .realistic_generator import (
    BALANCED_PROFILE,
    REALISTIC_PROFILE,
    RealisticGenerationConfig,
    generate_realistic_rows,
    summarize_rows,
    validate_rows,
)


CSV_COLUMNS = [
    "transactionId",
    "userId",
    "amount",
    "description",
    "merchantName",
    "mccCode",
    "timestamp",
    "label",
    "intendedCategory",
    "profile",
]

SPLIT_FILENAMES = {
    "train": "train.csv",
    "validation": "validation.csv",
    "test": "test.csv",
}


@dataclass(frozen=True)
class DatasetExportConfig:
    output_dir: Path
    dataset_profile: str = BALANCED_PROFILE
    train_per_category: int = 200
    validation_per_category: int = 50
    test_per_category: int = 50
    train_size: int = 2000
    validation_size: int = 500
    test_size: int = 500
    amount_min: str = "10.00"
    amount_max: str = "5000.00"
    seed: int = 42


def export_datasets(config: DatasetExportConfig) -> Dict[str, Dict[str, object]]:
    config.output_dir.mkdir(parents=True, exist_ok=True)
    validate_export_config(config)

    summary: Dict[str, Dict[str, object]] = {}
    for split_index, split_name in enumerate(SPLIT_FILENAMES):
        rows = generate_realistic_rows(
            RealisticGenerationConfig(
                split_name=split_name,
                dataset_profile=config.dataset_profile,
                per_category_count=split_per_category_count(config, split_name),
                total_count=split_total_count(config, split_name),
                amount_min=Decimal(config.amount_min),
                amount_max=Decimal(config.amount_max),
                seed=config.seed + split_index,
            )
        )
        validate_rows(rows)
        output_path = config.output_dir / SPLIT_FILENAMES[split_name]
        write_rows(output_path, rows)
        row_summary = summarize_rows(rows)
        summary[split_name] = {
            "path": str(output_path),
            "rows": len(rows),
            "labels": row_summary["labels"],
            "profiles": row_summary["profiles"],
        }

    return summary


def validate_export_config(config: DatasetExportConfig) -> None:
    if config.dataset_profile not in {BALANCED_PROFILE, REALISTIC_PROFILE}:
        raise ValueError("dataset_profile must be 'balanced' or 'realistic'")
    if Decimal(config.amount_min) <= 0:
        raise ValueError("amount_min must be greater than 0")
    if Decimal(config.amount_max) < Decimal(config.amount_min):
        raise ValueError("amount_max must be greater than or equal to amount_min")


def split_per_category_count(config: DatasetExportConfig, split_name: str) -> int:
    return {
        "train": config.train_per_category,
        "validation": config.validation_per_category,
        "test": config.test_per_category,
    }[split_name]


def split_total_count(config: DatasetExportConfig, split_name: str) -> int:
    return {
        "train": config.train_size,
        "validation": config.validation_size,
        "test": config.test_size,
    }[split_name]


def write_rows(output_path: Path, rows: Iterable[Dict[str, object]]) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=CSV_COLUMNS)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def read_rows(input_path: Path) -> List[Dict[str, str]]:
    with input_path.open("r", newline="", encoding="utf-8") as file:
        return list(csv.DictReader(file))
