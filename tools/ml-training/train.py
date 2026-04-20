from __future__ import annotations

import argparse
from pathlib import Path

from ml_training.model import TrainingConfig, train_model


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train transaction classifier and export ONNX.")
    parser.add_argument("--data-dir", type=Path, default=Path("data"))
    parser.add_argument("--artifact-dir", type=Path, default=Path("artifacts"))
    parser.add_argument("--target-opset", type=int, default=15)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    result = train_model(
        TrainingConfig(
            data_dir=args.data_dir,
            artifact_dir=args.artifact_dir,
            target_opset=args.target_opset,
        )
    )
    print(f"sklearn model: {result['sklearn_model_path']}")
    print(f"onnx model: {result['onnx_model_path']}")
    print(f"labels: {result['labels_path']}")
    print(f"metrics: {result['metrics_path']}")


if __name__ == "__main__":
    main()
