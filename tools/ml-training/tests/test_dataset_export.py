from __future__ import annotations

import subprocess
import sys
import shutil
from pathlib import Path

import pytest


ML_TRAINING_DIR = Path(__file__).resolve().parents[1]
if str(ML_TRAINING_DIR) not in sys.path:
    sys.path.insert(0, str(ML_TRAINING_DIR))

from ml_training.dataset import (  # noqa: E402
    CSV_COLUMNS,
    EXPORT_METADATA_FILENAME,
    DatasetExportConfig,
    export_datasets,
    read_rows,
)


EXPECTED_LABELS = {
    "FOOD_AND_DRINKS",
    "TRANSPORT",
    "GROCERIES",
    "RETAIL_SHOPPING",
    "ENTERTAINMENT",
    "HEALTH",
    "BANKING_AND_FEES",
    "BILLS_AND_GOVERNMENT",
    "UNDEFINED",
}


@pytest.fixture()
def workspace_tmp(request) -> Path:
    root = ML_TRAINING_DIR / ".test-output" / request.node.name
    if root.exists():
        shutil.rmtree(root)
    root.mkdir(parents=True)
    try:
        yield root
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_export_dataset_schema_sizes_users_and_new_labels(workspace_tmp: Path) -> None:
    summary = export_datasets(
        DatasetExportConfig(
            output_dir=workspace_tmp,
            dataset_profile="balanced",
            train_per_category=3,
            validation_per_category=2,
            test_per_category=2,
            users_per_split=5,
            seed=11,
        )
    )

    assert summary["train"]["rows"] == 27
    assert summary["validation"]["rows"] == 18
    assert summary["test"]["rows"] == 18
    assert (workspace_tmp / EXPORT_METADATA_FILENAME).exists()

    train_rows = read_rows(workspace_tmp / "train.csv")
    assert list(train_rows[0].keys()) == CSV_COLUMNS
    assert "intendedCategory" not in train_rows[0]
    assert "profile" not in train_rows[0]
    assert {row["label"] for row in train_rows} == EXPECTED_LABELS
    assert "SHOPPING" not in {row["label"] for row in train_rows}
    assert "OTHER" not in {row["label"] for row in train_rows}
    assert len({row["userId"] for row in train_rows}) == 5


def test_export_dataset_is_deterministic_for_fixed_seed(workspace_tmp: Path) -> None:
    first_dir = workspace_tmp / "first"
    second_dir = workspace_tmp / "second"
    config = {
        "dataset_profile": "balanced",
        "train_per_category": 2,
        "validation_per_category": 1,
        "test_per_category": 1,
        "users_per_split": 4,
        "seed": 99,
    }

    export_datasets(DatasetExportConfig(output_dir=first_dir, **config))
    export_datasets(DatasetExportConfig(output_dir=second_dir, **config))

    assert (first_dir / "train.csv").read_text(encoding="utf-8") == (
        second_dir / "train.csv"
    ).read_text(encoding="utf-8")
    assert (first_dir / EXPORT_METADATA_FILENAME).read_text(encoding="utf-8") == (
        second_dir / EXPORT_METADATA_FILENAME
    ).read_text(encoding="utf-8")


def test_export_realistic_holdout_has_zero_merchant_overlap_between_train_and_test(workspace_tmp: Path) -> None:
    export_datasets(
        DatasetExportConfig(
            output_dir=workspace_tmp,
            dataset_profile="realistic",
            split_strategy="holdout_merchants",
            train_size=240,
            validation_size=90,
            test_size=90,
            users_per_split=12,
            holdout_ratio=0.34,
            seed=17,
        )
    )

    train_rows = read_rows(workspace_tmp / "train.csv")
    test_rows = read_rows(workspace_tmp / "test.csv")
    train_merchants = {
        row["merchantName"]
        for row in train_rows
        if row["merchantName"] and row["label"] != "UNDEFINED"
    }
    test_merchants = {
        row["merchantName"]
        for row in test_rows
        if row["merchantName"] and row["label"] != "UNDEFINED"
    }
    assert not (train_merchants & test_merchants)


def test_export_realistic_profile_uses_total_split_sizes_and_tracks_diverse_profiles(workspace_tmp: Path) -> None:
    summary = export_datasets(
        DatasetExportConfig(
            output_dir=workspace_tmp,
            dataset_profile="realistic",
            train_size=180,
            validation_size=60,
            test_size=40,
            users_per_split=9,
            seed=23,
        )
    )

    assert summary["train"]["rows"] == 180
    assert summary["validation"]["rows"] == 60
    assert summary["test"]["rows"] == 40
    train_rows = read_rows(workspace_tmp / "train.csv")
    assert len(summary["train"]["profiles"]) > 12
    assert len({row["userId"] for row in train_rows}) == 9
    assert sum(1 for row in train_rows if row["label"] == "RETAIL_SHOPPING") > sum(
        1 for row in train_rows if row["label"] == "UNDEFINED"
    )


def test_export_dataset_cli_smoke_writes_metadata(workspace_tmp: Path) -> None:
    result = subprocess.run(
        [
            sys.executable,
            str(ML_TRAINING_DIR / "export_dataset.py"),
            "--output-dir",
            str(workspace_tmp),
            "--profile",
            "realistic",
            "--train-size",
            "120",
            "--validation-size",
            "40",
            "--test-size",
            "40",
            "--users-per-split",
            "10",
            "--seed",
            "5",
        ],
        check=True,
        capture_output=True,
        text=True,
    )

    assert "metadata:" in result.stdout
    assert (workspace_tmp / "train.csv").exists()
    assert (workspace_tmp / EXPORT_METADATA_FILENAME).exists()
