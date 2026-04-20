from __future__ import annotations

import argparse
from pathlib import Path

from ml_training.dataset import DatasetExportConfig, export_datasets


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export labeled synthetic transaction datasets.")
    parser.add_argument("--output-dir", type=Path, default=Path("data"))
    parser.add_argument("--train-per-category", type=int, default=200)
    parser.add_argument("--validation-per-category", type=int, default=50)
    parser.add_argument("--test-per-category", type=int, default=50)
    parser.add_argument("--ambiguous-ratio", type=float, default=0.10)
    parser.add_argument("--low-confidence-ratio", type=float, default=0.20)
    parser.add_argument("--amount-min", default="10.00")
    parser.add_argument("--amount-max", default="5000.00")
    parser.add_argument("--seed", type=int, default=42)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    summary = export_datasets(
        DatasetExportConfig(
            output_dir=args.output_dir,
            train_per_category=args.train_per_category,
            validation_per_category=args.validation_per_category,
            test_per_category=args.test_per_category,
            ambiguous_ratio=args.ambiguous_ratio,
            low_confidence_ratio=args.low_confidence_ratio,
            amount_min=args.amount_min,
            amount_max=args.amount_max,
            seed=args.seed,
        )
    )

    for split_name, split_summary in summary.items():
        print(
            f"{split_name}: rows={split_summary['rows']} "
            f"path={split_summary['path']}"
        )


if __name__ == "__main__":
    main()
