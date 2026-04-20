from __future__ import annotations

import argparse
import json
from pathlib import Path

from ml_training.model import (
    LABELS_FILENAME,
    ONNX_MODEL_FILENAME,
    SKLEARN_MODEL_FILENAME,
    predict_onnx_scores,
    predict_sklearn_scores,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Predict one transaction category manually.")
    parser.add_argument("--artifact-dir", type=Path, default=Path("artifacts"))
    parser.add_argument("--runtime", choices=("onnx", "sklearn"), default="onnx")
    parser.add_argument("--amount", type=float, required=True)
    parser.add_argument("--description", required=True)
    parser.add_argument("--merchant-name", default="")
    parser.add_argument("--mcc-code", default="")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--json", action="store_true", help="Print machine-readable JSON.")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.runtime == "onnx":
        scores = predict_onnx_scores(
            model_path=args.artifact_dir / ONNX_MODEL_FILENAME,
            labels_path=args.artifact_dir / LABELS_FILENAME,
            amount=args.amount,
            description=args.description,
            merchant_name=args.merchant_name,
            mcc_code=args.mcc_code,
        )
    else:
        scores = predict_sklearn_scores(
            model_path=args.artifact_dir / SKLEARN_MODEL_FILENAME,
            amount=args.amount,
            description=args.description,
            merchant_name=args.merchant_name,
            mcc_code=args.mcc_code,
        )

    top_k = max(1, args.top_k)
    category, confidence = scores[0]
    document = {
        "runtime": args.runtime,
        "category": category,
        "confidence": confidence,
        "top": [
            {"category": label, "confidence": probability}
            for label, probability in scores[:top_k]
        ],
    }

    if args.json:
        print(json.dumps(document, ensure_ascii=False, indent=2))
        return

    print(f"runtime: {document['runtime']}")
    print(f"category: {document['category']}")
    print(f"confidence: {document['confidence']:.4f}")
    print("top:")
    for item in document["top"]:
        print(f"  {item['category']}: {item['confidence']:.4f}")


if __name__ == "__main__":
    main()
