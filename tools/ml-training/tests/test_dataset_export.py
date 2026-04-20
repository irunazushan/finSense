from __future__ import annotations

import subprocess
import sys
import shutil
from pathlib import Path

import pytest


ML_TRAINING_DIR = Path(__file__).resolve().parents[1]
if str(ML_TRAINING_DIR) not in sys.path:
    sys.path.insert(0, str(ML_TRAINING_DIR))

from ml_training.dataset import CSV_COLUMNS, DatasetExportConfig, export_datasets, read_rows  # noqa: E402


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


def test_export_dataset_schema_sizes_and_ambiguous_labels(workspace_tmp: Path) -> None:
    summary = export_datasets(
        DatasetExportConfig(
            output_dir=workspace_tmp,
            train_per_category=3,
            validation_per_category=2,
            test_per_category=2,
            seed=11,
        )
    )

    assert summary["train"]["rows"] == 21
    assert summary["validation"]["rows"] == 14
    assert summary["test"]["rows"] == 14

    train_rows = read_rows(workspace_tmp / "train.csv")
    assert list(train_rows[0].keys()) == CSV_COLUMNS
    ambiguous_rows = [row for row in train_rows if row["profile"] == "ambiguous"]
    assert ambiguous_rows
    assert all(row["label"] == "UNDEFINED" for row in ambiguous_rows)
    assert all(row["intendedCategory"] for row in train_rows)
    assert "OTHER" in {row["label"] for row in train_rows}
    assert "UNDEFINED" in {row["label"] for row in train_rows}


def test_export_dataset_is_deterministic_for_fixed_seed(workspace_tmp: Path) -> None:
    first_dir = workspace_tmp / "first"
    second_dir = workspace_tmp / "second"
    config = {
        "train_per_category": 2,
        "validation_per_category": 1,
        "test_per_category": 1,
        "seed": 99,
    }

    export_datasets(DatasetExportConfig(output_dir=first_dir, **config))
    export_datasets(DatasetExportConfig(output_dir=second_dir, **config))

    assert (first_dir / "train.csv").read_text(encoding="utf-8") == (
        second_dir / "train.csv"
    ).read_text(encoding="utf-8")
    assert (first_dir / "validation.csv").read_text(encoding="utf-8") == (
        second_dir / "validation.csv"
    ).read_text(encoding="utf-8")
    assert (first_dir / "test.csv").read_text(encoding="utf-8") == (
        second_dir / "test.csv"
    ).read_text(encoding="utf-8")


def test_export_dataset_cli_smoke(workspace_tmp: Path) -> None:
    result = subprocess.run(
        [
            sys.executable,
            str(ML_TRAINING_DIR / "export_dataset.py"),
            "--output-dir",
            str(workspace_tmp),
            "--train-per-category",
            "2",
            "--validation-per-category",
            "1",
            "--test-per-category",
            "1",
            "--seed",
            "5",
        ],
        check=True,
        capture_output=True,
        text=True,
    )

    assert "train: rows=" in result.stdout
    assert (workspace_tmp / "train.csv").exists()
    assert (workspace_tmp / "validation.csv").exists()
    assert (workspace_tmp / "test.csv").exists()


def test_export_realistic_profile_uses_total_split_sizes(workspace_tmp: Path) -> None:
    summary = export_datasets(
        DatasetExportConfig(
            output_dir=workspace_tmp,
            dataset_profile="realistic",
            train_size=100,
            validation_size=40,
            test_size=30,
            seed=17,
        )
    )

    assert summary["train"]["rows"] == 100
    assert summary["validation"]["rows"] == 40
    assert summary["test"]["rows"] == 30
    train_rows = read_rows(workspace_tmp / "train.csv")
    assert len({row["profile"] for row in train_rows}) > 3
    assert sum(1 for row in train_rows if row["label"] == "SHOPPING") > sum(
        1 for row in train_rows if row["label"] == "UNDEFINED"
    )
