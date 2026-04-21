from __future__ import annotations

import csv
import json
from dataclasses import asdict, dataclass
from decimal import Decimal
from pathlib import Path
from typing import Dict, Iterable, List

from .realistic_generator import (
    BALANCED_PROFILE,
    REALISTIC_PROFILE,
    SPLIT_STRATEGY_HOLDOUT,
    SPLIT_STRATEGY_MIXED,
    RealisticGenerationConfig,
    generate_realistic_rows,
    load_catalog,
    summarize_rows,
    validate_rows,
)
from .paths import TRANSACTION_CATALOG_PATH


CSV_COLUMNS = [
    "transactionId",
    "userId",
    "amount",
    "description",
    "merchantName",
    "mccCode",
    "timestamp",
    "label",
]

SPLIT_FILENAMES = {
    "train": "train.csv",
    "validation": "validation.csv",
    "test": "test.csv",
}

EXPORT_METADATA_FILENAME = "export-metadata.json"


@dataclass(frozen=True)
class DatasetExportConfig:
    output_dir: Path
    dataset_profile: str = REALISTIC_PROFILE
    split_strategy: str = SPLIT_STRATEGY_HOLDOUT
    train_per_category: int = 200
    validation_per_category: int = 50
    test_per_category: int = 50
    train_size: int = 20000
    validation_size: int = 5000
    test_size: int = 5000
    amount_min: str = "10.00"
    amount_max: str = "50000.00"
    users_per_split: int = 64
    holdout_ratio: float = 0.18
    seed: int = 42


def export_datasets(config: DatasetExportConfig) -> Dict[str, Dict[str, object]]:
    config.output_dir.mkdir(parents=True, exist_ok=True)
    validate_export_config(config)

    catalog = load_catalog(TRANSACTION_CATALOG_PATH)
    summary: Dict[str, Dict[str, object]] = {}
    for split_name in SPLIT_FILENAMES:
        rows = generate_realistic_rows(
            RealisticGenerationConfig(
                split_name=split_name,
                dataset_profile=config.dataset_profile,
                per_category_count=split_per_category_count(config, split_name),
                total_count=split_total_count(config, split_name),
                amount_min=Decimal(config.amount_min),
                amount_max=Decimal(config.amount_max),
                seed=config.seed,
                split_strategy=config.split_strategy,
                users_per_split=config.users_per_split,
                holdout_ratio=config.holdout_ratio,
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
            "unique_users": len({str(row.get("userId")) for row in rows}),
        }

    metadata = build_export_metadata(config, catalog)
    metadata_path = config.output_dir / EXPORT_METADATA_FILENAME
    write_metadata(metadata_path, metadata)
    summary["metadata"] = metadata
    summary["metadata"]["path"] = str(metadata_path)
    return summary


def build_export_metadata(config: DatasetExportConfig, catalog: Dict[str, object]) -> Dict[str, object]:
    categories = list((catalog.get("categories") or {}).keys())
    labels = sorted(categories + (["UNDEFINED"] if "UNDEFINED" not in categories else []))
    dataset_id = (
        f"{config.dataset_profile}-"
        f"{config.split_strategy}-"
        f"s{config.seed}-"
        f"u{config.users_per_split}-"
        f"h{str(config.holdout_ratio).replace('.', '_')}"
    )
    return {
        "dataset_id": dataset_id,
        "profile": config.dataset_profile,
        "split_strategy": config.split_strategy,
        "seed": config.seed,
        "users_per_split": config.users_per_split,
        "holdout_ratio": config.holdout_ratio,
        "labels": labels,
        "sizes": {
            "train": split_total_count(config, "train"),
            "validation": split_total_count(config, "validation"),
            "test": split_total_count(config, "test"),
        },
        "balanced_counts": {
            "train": split_per_category_count(config, "train"),
            "validation": split_per_category_count(config, "validation"),
            "test": split_per_category_count(config, "test"),
        },
        "config": {
            key: (str(value) if isinstance(value, Path) else value)
            for key, value in asdict(config).items()
            if key != "output_dir"
        },
    }


def write_metadata(path: Path, metadata: Dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as file:
        json.dump(metadata, file, ensure_ascii=False, indent=2)
        file.write("\n")


def load_export_metadata(output_dir: Path) -> Dict[str, object]:
    path = output_dir / EXPORT_METADATA_FILENAME
    if not path.exists():
        return {}
    with path.open("r", encoding="utf-8") as file:
        return json.load(file)


def validate_export_config(config: DatasetExportConfig) -> None:
    if config.dataset_profile not in {BALANCED_PROFILE, REALISTIC_PROFILE}:
        raise ValueError("dataset_profile must be 'balanced' or 'realistic'")
    if config.split_strategy not in {SPLIT_STRATEGY_HOLDOUT, SPLIT_STRATEGY_MIXED}:
        raise ValueError("split_strategy must be 'holdout_merchants' or 'mixed'")
    if Decimal(config.amount_min) <= 0:
        raise ValueError("amount_min must be greater than 0")
    if Decimal(config.amount_max) < Decimal(config.amount_min):
        raise ValueError("amount_max must be greater than or equal to amount_min")
    if config.users_per_split <= 0:
        raise ValueError("users_per_split must be greater than 0")
    if config.holdout_ratio < 0 or config.holdout_ratio >= 1:
        raise ValueError("holdout_ratio must be in range [0.0, 1.0)")


def split_per_category_count(config: DatasetExportConfig, split_name: str) -> int:
    return {
        "train": config.train_per_category,
        "validation": config.validation_per_category,
        "test": config.test_per_category,
    }[split_name]


def split_total_count(config: DatasetExportConfig, split_name: str) -> int:
    if config.dataset_profile == BALANCED_PROFILE:
        label_count = len(load_export_metadata_labels())
        return split_per_category_count(config, split_name) * label_count
    return {
        "train": config.train_size,
        "validation": config.validation_size,
        "test": config.test_size,
    }[split_name]


def load_export_metadata_labels() -> List[str]:
    catalog = load_catalog(TRANSACTION_CATALOG_PATH)
    categories = list((catalog.get("categories") or {}).keys())
    if "UNDEFINED" not in categories:
        categories.append("UNDEFINED")
    return categories


def write_rows(output_path: Path, rows: Iterable[Dict[str, object]]) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=CSV_COLUMNS)
        writer.writeheader()
        for row in rows:
            writer.writerow({column: row.get(column, "") for column in CSV_COLUMNS})


def read_rows(input_path: Path) -> List[Dict[str, str]]:
    with input_path.open("r", newline="", encoding="utf-8") as file:
        return list(csv.DictReader(file))
