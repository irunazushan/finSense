from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Tuple

import joblib
import numpy as np
import pandas as pd
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType, StringTensorType
from sklearn.compose import ColumnTransformer
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, confusion_matrix, f1_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler


MISSING_MCC_TOKEN = "__MISSING_MCC__"
SKLEARN_MODEL_FILENAME = "sklearn-pipeline.joblib"
ONNX_MODEL_FILENAME = "transaction-classifier.onnx"
LABELS_FILENAME = "labels.json"
METRICS_FILENAME = "metrics.json"
METADATA_FILENAME = "metadata.json"


@dataclass(frozen=True)
class TrainingConfig:
    data_dir: Path
    artifact_dir: Path
    target_opset: int = 15


@dataclass(frozen=True)
class EvaluationConfig:
    data_dir: Path
    artifact_dir: Path
    split: str = "test"


def train_model(config: TrainingConfig) -> Dict[str, str]:
    config.artifact_dir.mkdir(parents=True, exist_ok=True)

    train_df = load_dataset(config.data_dir / "train.csv")
    validation_df = load_dataset(config.data_dir / "validation.csv")
    pipeline = build_pipeline()
    pipeline.fit(feature_frame(train_df), train_df["label"])

    labels = [str(label) for label in pipeline.named_steps["classifier"].classes_]
    validation_metrics = evaluate_predictions(
        labels=labels,
        y_true=validation_df["label"].tolist(),
        y_pred=pipeline.predict(feature_frame(validation_df)).tolist(),
    )

    sklearn_model_path = config.artifact_dir / SKLEARN_MODEL_FILENAME
    onnx_model_path = config.artifact_dir / ONNX_MODEL_FILENAME
    labels_path = config.artifact_dir / LABELS_FILENAME
    metrics_path = config.artifact_dir / METRICS_FILENAME
    metadata_path = config.artifact_dir / METADATA_FILENAME

    joblib.dump(pipeline, sklearn_model_path)
    export_onnx_model(pipeline, onnx_model_path, config.target_opset)
    write_json(labels_path, {"labels": labels})
    write_json(
        metrics_path,
        {
            "validation": validation_metrics,
        },
    )
    write_json(
        metadata_path,
        {
            "model_type": "tfidf_logistic_regression",
            "inputs": ["text", "mccCode", "amount"],
            "missing_mcc_token": MISSING_MCC_TOKEN,
            "target_opset": config.target_opset,
        },
    )

    return {
        "sklearn_model_path": str(sklearn_model_path),
        "onnx_model_path": str(onnx_model_path),
        "labels_path": str(labels_path),
        "metrics_path": str(metrics_path),
        "metadata_path": str(metadata_path),
    }


def evaluate_artifacts(config: EvaluationConfig) -> Dict[str, object]:
    split_path = config.data_dir / f"{config.split}.csv"
    df = load_dataset(split_path)
    labels = load_labels(config.artifact_dir / LABELS_FILENAME)

    pipeline = joblib.load(config.artifact_dir / SKLEARN_MODEL_FILENAME)
    features = feature_frame(df)
    sklearn_pred = pipeline.predict(features).tolist()
    onnx_pred = predict_onnx(
        model_path=config.artifact_dir / ONNX_MODEL_FILENAME,
        features=features,
        labels=labels,
    )

    result = {
        "sklearn": evaluate_predictions(labels, df["label"].tolist(), sklearn_pred),
        "onnx": evaluate_predictions(labels, df["label"].tolist(), onnx_pred),
    }
    metrics_path = config.artifact_dir / f"{config.split}-evaluation.json"
    write_json(metrics_path, result)
    result["metrics_path"] = str(metrics_path)
    return result


def predict_sklearn_scores(
    model_path: Path,
    amount: float,
    description: str,
    merchant_name: str,
    mcc_code: str,
) -> List[Tuple[str, float]]:
    pipeline = joblib.load(model_path)
    features = feature_frame(
        transaction_frame(
            amount=amount,
            description=description,
            merchant_name=merchant_name,
            mcc_code=mcc_code,
        )
    )
    labels = [str(label) for label in pipeline.named_steps["classifier"].classes_]
    probabilities = pipeline.predict_proba(features)[0]
    return sort_scores(labels, probabilities)


def predict_onnx_scores(
    model_path: Path,
    labels_path: Path,
    amount: float,
    description: str,
    merchant_name: str,
    mcc_code: str,
) -> List[Tuple[str, float]]:
    labels = load_labels(labels_path)
    features = feature_frame(
        transaction_frame(
            amount=amount,
            description=description,
            merchant_name=merchant_name,
            mcc_code=mcc_code,
        )
    )
    probabilities = predict_onnx_probabilities(model_path=model_path, features=features, labels=labels)
    return sort_scores(labels, probabilities[0])


def build_pipeline() -> Pipeline:
    features = ColumnTransformer(
        transformers=[
            (
                "text",
                TfidfVectorizer(ngram_range=(1, 2), min_df=1),
                "text",
            ),
            (
                "mcc",
                OneHotEncoder(handle_unknown="ignore"),
                ["mccCode"],
            ),
            (
                "amount",
                StandardScaler(),
                ["amount"],
            ),
        ],
        sparse_threshold=0.3,
    )
    return Pipeline(
        steps=[
            ("features", features),
            (
                "classifier",
                LogisticRegression(
                    max_iter=1000,
                    class_weight="balanced",
                    random_state=42,
                ),
            ),
        ]
    )


def load_dataset(path: Path) -> pd.DataFrame:
    df = pd.read_csv(path, dtype={"mccCode": "string"})
    required = {
        "amount",
        "description",
        "merchantName",
        "mccCode",
        "label",
    }
    missing = sorted(required - set(df.columns))
    if missing:
        raise ValueError(f"Dataset is missing columns: {', '.join(missing)}")
    return df


def transaction_frame(
    amount: float,
    description: str,
    merchant_name: str,
    mcc_code: str,
) -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "amount": amount,
                "description": description,
                "merchantName": merchant_name,
                "mccCode": mcc_code,
            }
        ]
    )


def feature_frame(df: pd.DataFrame) -> pd.DataFrame:
    features = pd.DataFrame()
    description = df["description"].fillna("").astype(str)
    merchant_name = df["merchantName"].fillna("").astype(str)
    features["text"] = (description + " " + merchant_name).str.strip()
    features["mccCode"] = df["mccCode"].fillna(MISSING_MCC_TOKEN).astype(str)
    features["mccCode"] = features["mccCode"].replace({"": MISSING_MCC_TOKEN})
    features["amount"] = pd.to_numeric(df["amount"], errors="coerce").fillna(0.0).astype("float32")
    return features


def export_onnx_model(pipeline: Pipeline, output_path: Path, target_opset: int) -> None:
    classifier = pipeline.named_steps["classifier"]
    onnx_model = convert_sklearn(
        pipeline,
        initial_types=[
            ("text", StringTensorType([None, 1])),
            ("mccCode", StringTensorType([None, 1])),
            ("amount", FloatTensorType([None, 1])),
        ],
        options={id(classifier): {"zipmap": False}},
        target_opset=target_opset,
    )
    output_path.write_bytes(onnx_model.SerializeToString())


def predict_onnx(model_path: Path, features: pd.DataFrame, labels: List[str]) -> List[str]:
    probabilities = predict_onnx_probabilities(model_path=model_path, features=features, labels=labels)
    indices = np.asarray(probabilities).argmax(axis=1)
    return [labels[int(index)] for index in indices]


def predict_onnx_probabilities(model_path: Path, features: pd.DataFrame, labels: List[str]) -> np.ndarray:
    import onnxruntime as ort

    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    feed = {
        "text": features["text"].astype(str).to_numpy(dtype=object).reshape((-1, 1)),
        "mccCode": features["mccCode"].astype(str).to_numpy(dtype=object).reshape((-1, 1)),
        "amount": features["amount"].to_numpy(dtype=np.float32).reshape((-1, 1)),
    }
    outputs = session.run(None, feed)
    return find_probability_output(outputs, labels)


def find_probability_output(outputs: List[object], labels: List[str]) -> np.ndarray:
    for output in outputs:
        array = np.asarray(output)
        if array.ndim == 2 and array.shape[1] == len(labels):
            return array
    for output in outputs:
        if isinstance(output, list) and output and isinstance(output[0], dict):
            return np.asarray(
                [
                    [float(row.get(label, 0.0)) for label in labels]
                    for row in output
                ],
                dtype=np.float32,
            )
    raise RuntimeError("ONNX model did not return a probability matrix")


def evaluate_predictions(labels: List[str], y_true: List[str], y_pred: List[str]) -> Dict[str, object]:
    return {
        "accuracy": float(accuracy_score(y_true, y_pred)),
        "macro_f1": float(f1_score(y_true, y_pred, labels=labels, average="macro", zero_division=0)),
        "weighted_f1": float(
            f1_score(y_true, y_pred, labels=labels, average="weighted", zero_division=0)
        ),
        "confusion_matrix": confusion_matrix(y_true, y_pred, labels=labels).tolist(),
        "labels": labels,
    }


def sort_scores(labels: List[str], probabilities: object) -> List[Tuple[str, float]]:
    return sorted(
        [(label, float(probability)) for label, probability in zip(labels, probabilities)],
        key=lambda item: item[1],
        reverse=True,
    )


def load_labels(path: Path) -> List[str]:
    with path.open("r", encoding="utf-8") as file:
        document = json.load(file)
    labels = document.get("labels")
    if not isinstance(labels, list) or not labels:
        raise ValueError(f"Invalid labels artifact: {path}")
    return [str(label) for label in labels]


def write_json(path: Path, document: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as file:
        json.dump(document, file, ensure_ascii=False, indent=2)
        file.write("\n")
