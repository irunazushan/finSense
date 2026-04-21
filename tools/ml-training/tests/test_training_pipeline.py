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
    AMOUNT_CLIP_MAX,
    EvaluationConfig,
    TrainingConfig,
    evaluate_artifacts,
    feature_frame,
    load_labels,
    predict_onnx_scores,
    predict_sklearn_scores,
    train_model,
)


EXPECTED_LABELS = [
    "BANKING_AND_FEES",
    "BILLS_AND_GOVERNMENT",
    "ENTERTAINMENT",
    "FOOD_AND_DRINKS",
    "GROCERIES",
    "HEALTH",
    "RETAIL_SHOPPING",
    "TRANSPORT",
    "UNDEFINED",
]


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


def export_small_realistic_dataset(path: Path, seed: int) -> None:
    export_datasets(
        DatasetExportConfig(
            output_dir=path,
            dataset_profile="realistic",
            split_strategy="holdout_merchants",
            train_size=320,
            validation_size=90,
            test_size=90,
            users_per_split=14,
            holdout_ratio=0.34,
            seed=seed,
        )
    )


def test_training_exports_loadable_onnx_labels_and_metadata(workspace_tmp: Path) -> None:
    data_dir = workspace_tmp / "data"
    artifact_dir = workspace_tmp / "artifacts"
    export_small_realistic_dataset(data_dir, seed=21)

    result = train_model(TrainingConfig(data_dir=data_dir, artifact_dir=artifact_dir))

    assert Path(result["sklearn_model_path"]).exists()
    assert Path(result["onnx_model_path"]).exists()
    assert Path(result["labels_path"]).exists()
    assert Path(result["metrics_path"]).exists()
    assert Path(result["metadata_path"]).exists()

    labels = load_labels(Path(result["labels_path"]))
    assert labels == EXPECTED_LABELS
    metrics = json.loads(Path(result["metrics_path"]).read_text(encoding="utf-8"))
    assert metrics["dataset"]["profile"] == "realistic"
    assert "accuracy" in metrics["validation"]
    assert "per_category" in metrics["validation"]
    assert "confidence" in metrics["validation"]

    model_metadata = json.loads(Path(result["metadata_path"]).read_text(encoding="utf-8"))
    assert model_metadata["training_data"]["split_strategy"] == "holdout_merchants"
    assert model_metadata["training_data"]["labels"] == EXPECTED_LABELS
    assert model_metadata["amount_preprocessing"]["transform"] == "signed_log1p"
    assert model_metadata["amount_preprocessing"]["clip_max"] == AMOUNT_CLIP_MAX


def test_feature_frame_clips_and_log_scales_amount_outliers() -> None:
    features = feature_frame(
        pd.DataFrame(
            [
                {"amount": 50, "description": "", "merchantName": "", "mccCode": ""},
                {"amount": 353303, "description": "", "merchantName": "", "mccCode": ""},
                {"amount": -353303, "description": "", "merchantName": "", "mccCode": ""},
            ]
        )
    )

    assert features["amount"].iloc[0] == pytest.approx(3.9318256, abs=1e-6)
    assert features["amount"].iloc[1] == pytest.approx(10.819798, abs=1e-6)
    assert features["amount"].iloc[2] == pytest.approx(-10.819798, abs=1e-6)


def test_high_amount_outlier_does_not_override_obvious_transport_signal(workspace_tmp: Path) -> None:
    data_dir = workspace_tmp / "data"
    artifact_dir = workspace_tmp / "artifacts"
    export_datasets(
        DatasetExportConfig(
            output_dir=data_dir,
            dataset_profile="realistic",
            split_strategy="holdout_merchants",
            train_size=2400,
            validation_size=500,
            test_size=500,
            users_per_split=20,
            holdout_ratio=0.25,
            seed=52,
        )
    )

    train_model(TrainingConfig(data_dir=data_dir, artifact_dir=artifact_dir))

    sklearn_scores = predict_sklearn_scores(
        model_path=artifact_dir / "sklearn-pipeline.joblib",
        amount=353303,
        description="Taxi ride",
        merchant_name="Yandex taxi",
        mcc_code="4121",
    )
    onnx_scores = predict_onnx_scores(
        model_path=artifact_dir / "transaction-classifier.onnx",
        labels_path=artifact_dir / "labels.json",
        amount=353303,
        description="Taxi ride",
        merchant_name="Yandex taxi",
        mcc_code="4121",
    )

    assert sklearn_scores[0][0] == "TRANSPORT"
    assert sklearn_scores[0][1] > 0.50
    assert onnx_scores[0][0] == "TRANSPORT"
    assert onnx_scores[0][1] > 0.50


def test_evaluate_artifacts_records_unique_dataset_specific_reports(workspace_tmp: Path) -> None:
    train_dir = workspace_tmp / "train-data"
    realistic_eval_dir = workspace_tmp / "realistic-eval"
    balanced_eval_dir = workspace_tmp / "balanced-eval"
    artifact_dir = workspace_tmp / "artifacts"

    export_small_realistic_dataset(train_dir, seed=31)
    export_small_realistic_dataset(realistic_eval_dir, seed=32)
    export_datasets(
        DatasetExportConfig(
            output_dir=balanced_eval_dir,
            dataset_profile="balanced",
            train_per_category=10,
            validation_per_category=4,
            test_per_category=4,
            users_per_split=10,
            split_strategy="mixed",
            seed=33,
        )
    )

    train_model(TrainingConfig(data_dir=train_dir, artifact_dir=artifact_dir))
    realistic_eval = evaluate_artifacts(
        EvaluationConfig(data_dir=realistic_eval_dir, artifact_dir=artifact_dir, split="test")
    )
    balanced_eval = evaluate_artifacts(
        EvaluationConfig(data_dir=balanced_eval_dir, artifact_dir=artifact_dir, split="test")
    )

    assert realistic_eval["metrics_path"] != balanced_eval["metrics_path"]
    assert Path(realistic_eval["metrics_path"]).exists()
    assert Path(balanced_eval["metrics_path"]).exists()
    assert realistic_eval["dataset"]["profile"] == "realistic"
    assert balanced_eval["dataset"]["profile"] == "balanced"

    onnx_gap = abs(realistic_eval["sklearn"]["accuracy"] - realistic_eval["onnx"]["accuracy"])
    assert onnx_gap <= 0.08


def test_training_and_evaluate_cli_smoke(workspace_tmp: Path) -> None:
    data_dir = workspace_tmp / "data"
    artifact_dir = workspace_tmp / "artifacts"
    export_small_realistic_dataset(data_dir, seed=41)

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
    test_reports = list(artifact_dir.glob("test-evaluation-*.json"))
    assert test_reports

    predict_result = subprocess.run(
        [
            sys.executable,
            str(ML_TRAINING_DIR / "predict.py"),
            "--artifact-dir",
            str(artifact_dir),
            "--amount",
            "350",
            "--description",
            "grocery delivery samokat",
            "--merchant-name",
            "Samokat",
            "--mcc-code",
            "5411",
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    assert "category:" in predict_result.stdout
    assert "confidence:" in predict_result.stdout
