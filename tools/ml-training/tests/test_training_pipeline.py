from __future__ import annotations

import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest


pd = pytest.importorskip("pandas")
pytest.importorskip("sklearn")
pytest.importorskip("skl2onnx")
pytest.importorskip("onnxruntime")


ML_TRAINING_DIR = Path(__file__).resolve().parents[1]
if str(ML_TRAINING_DIR) not in sys.path:
    sys.path.insert(0, str(ML_TRAINING_DIR))

from ml_training.dataset import DatasetExportConfig, export_datasets  # noqa: E402
from ml_training.model import (  # noqa: E402
    EvaluationConfig,
    TrainingConfig,
    evaluate_artifacts,
    load_labels,
    train_model,
)


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


def test_training_exports_loadable_onnx_and_matching_labels(workspace_tmp: Path) -> None:
    data_dir = workspace_tmp / "data"
    artifact_dir = workspace_tmp / "artifacts"
    export_datasets(
        DatasetExportConfig(
            output_dir=data_dir,
            train_per_category=4,
            validation_per_category=2,
            test_per_category=2,
            ambiguous_ratio=0.10,
            low_confidence_ratio=0.10,
            seed=21,
        )
    )

    result = train_model(TrainingConfig(data_dir=data_dir, artifact_dir=artifact_dir))

    assert Path(result["sklearn_model_path"]).exists()
    assert Path(result["onnx_model_path"]).exists()
    assert Path(result["labels_path"]).exists()
    assert Path(result["metrics_path"]).exists()

    labels = load_labels(Path(result["labels_path"]))
    assert "UNDEFINED" in labels
    metrics = json.loads(Path(result["metrics_path"]).read_text(encoding="utf-8"))
    assert "accuracy" in metrics["validation"]

    evaluation = evaluate_artifacts(
        EvaluationConfig(data_dir=data_dir, artifact_dir=artifact_dir, split="test")
    )
    assert evaluation["sklearn"]["labels"] == evaluation["onnx"]["labels"]
    assert len(evaluation["onnx"]["confusion_matrix"]) == len(labels)


def test_training_and_evaluate_cli_smoke(workspace_tmp: Path) -> None:
    data_dir = workspace_tmp / "data"
    artifact_dir = workspace_tmp / "artifacts"
    export_datasets(
        DatasetExportConfig(
            output_dir=data_dir,
            train_per_category=4,
            validation_per_category=2,
            test_per_category=2,
            ambiguous_ratio=0.10,
            low_confidence_ratio=0.10,
            seed=31,
        )
    )

    train_result = subprocess.run(
        [
            sys.executable,
            str(ML_TRAINING_DIR / "train.py"),
            "--data-dir",
            str(data_dir),
            "--artifact-dir",
            str(artifact_dir),
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    assert "onnx model:" in train_result.stdout
    assert (artifact_dir / "transaction-classifier.onnx").exists()

    evaluate_result = subprocess.run(
        [
            sys.executable,
            str(ML_TRAINING_DIR / "evaluate.py"),
            "--data-dir",
            str(data_dir),
            "--artifact-dir",
            str(artifact_dir),
            "--split",
            "test",
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    assert "onnx accuracy:" in evaluate_result.stdout
    assert (artifact_dir / "test-evaluation.json").exists()
