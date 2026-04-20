from __future__ import annotations

import argparse
from pathlib import Path

from ml_training.model import EvaluationConfig, evaluate_artifacts


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate sklearn and ONNX transaction classifiers.")
    parser.add_argument("--data-dir", type=Path, default=Path("data"))
    parser.add_argument("--artifact-dir", type=Path, default=Path("artifacts"))
    parser.add_argument("--split", choices=("train", "validation", "test"), default="test")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    result = evaluate_artifacts(
        EvaluationConfig(
            data_dir=args.data_dir,
            artifact_dir=args.artifact_dir,
            split=args.split,
        )
    )
    print(f"split: {args.split}")
    print(f"sklearn accuracy: {result['sklearn']['accuracy']:.4f}")
    print(f"onnx accuracy: {result['onnx']['accuracy']:.4f}")
    print(f"metrics: {result['metrics_path']}")


if __name__ == "__main__":
    main()
