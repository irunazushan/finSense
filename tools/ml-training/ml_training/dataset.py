from __future__ import annotations

import csv
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
import sys
import uuid
from typing import Dict, Iterable, List

from .paths import ENUM_PATH, RULES_PATH, TRANSACTION_TESTER_DIR


if str(TRANSACTION_TESTER_DIR) not in sys.path:
    sys.path.insert(0, str(TRANSACTION_TESTER_DIR))

from generator import generate_transactions, load_category_templates  # noqa: E402
from models import GeneratorConfig  # noqa: E402


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
    train_per_category: int = 200
    validation_per_category: int = 50
    test_per_category: int = 50
    ambiguous_ratio: float = 0.10
    low_confidence_ratio: float = 0.20
    amount_min: str = "10.00"
    amount_max: str = "5000.00"
    seed: int = 42


def export_datasets(config: DatasetExportConfig) -> Dict[str, Dict[str, object]]:
    templates, allowed_categories = load_category_templates(RULES_PATH, ENUM_PATH)
    categories = [category for category in allowed_categories if category != "UNDEFINED"]
    if not categories:
        raise ValueError("No categories available for dataset generation")

    config.output_dir.mkdir(parents=True, exist_ok=True)
    split_sizes = {
        "train": config.train_per_category,
        "validation": config.validation_per_category,
        "test": config.test_per_category,
    }

    summary: Dict[str, Dict[str, object]] = {}
    for split_index, (split_name, per_category_count) in enumerate(split_sizes.items()):
        rows = build_split_rows(
            split_name=split_name,
            per_category_count=per_category_count,
            categories=categories,
            templates=templates,
            seed=config.seed + split_index,
            ambiguous_ratio=config.ambiguous_ratio,
            low_confidence_ratio=config.low_confidence_ratio,
            amount_min=Decimal(config.amount_min),
            amount_max=Decimal(config.amount_max),
        )
        output_path = config.output_dir / SPLIT_FILENAMES[split_name]
        write_rows(output_path, rows)
        summary[split_name] = {
            "path": str(output_path),
            "rows": len(rows),
        }

    return summary


def build_split_rows(
    split_name: str,
    per_category_count: int,
    categories: List[str],
    templates: Dict[str, object],
    seed: int,
    ambiguous_ratio: float,
    low_confidence_ratio: float,
    amount_min: Decimal,
    amount_max: Decimal,
) -> List[Dict[str, object]]:
    if per_category_count <= 0:
        raise ValueError("per_category_count must be greater than 0")

    total = per_category_count * len(categories)
    target_user_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"finsense:{split_name}:user:{seed}"))
    category_counts = {category: per_category_count for category in categories}
    result = generate_transactions(
        GeneratorConfig(
            bootstrap_servers="localhost:29092",
            core_base_url="http://localhost:8080",
            users_count=1,
            tx_per_user=total,
            target_user_id=target_user_id,
            amount_min=amount_min,
            amount_max=amount_max,
            start_datetime=datetime(2026, 1, 1, tzinfo=timezone.utc),
            end_datetime=datetime(2026, 12, 31, 23, 59, 59, tzinfo=timezone.utc),
            category_counts=category_counts,
            random_fill_enabled=False,
            ambiguous_ratio=ambiguous_ratio,
            low_confidence_ratio=low_confidence_ratio,
            send_interval_ms=0,
            seed=seed,
            verify_after_send=False,
        ),
        templates,
        categories,
    )

    rows: List[Dict[str, object]] = []
    for index, transaction in enumerate(result.transactions):
        payload = transaction.payload
        profile = transaction.profile
        label = "UNDEFINED" if profile == "ambiguous" else transaction.category
        rows.append(
            {
                "transactionId": str(
                    uuid.uuid5(
                        uuid.NAMESPACE_URL,
                        f"finsense:{split_name}:tx:{seed}:{index}",
                    )
                ),
                "userId": payload["userId"],
                "amount": payload["amount"],
                "description": payload.get("description") or "",
                "merchantName": payload.get("merchantName") or "",
                "mccCode": payload.get("mccCode") or "",
                "timestamp": payload["timestamp"],
                "label": label,
                "intendedCategory": transaction.category,
                "profile": profile,
            }
        )
    return rows


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
