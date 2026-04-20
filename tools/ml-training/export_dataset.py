from __future__ import annotations

import argparse
from pathlib import Path

from ml_training.dataset import DatasetExportConfig, export_datasets
from ml_training.realistic_generator import BALANCED_PROFILE, REALISTIC_PROFILE


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export labeled realistic transaction datasets.")
    parser.add_argument("--output-dir", type=Path, default=Path("data"))
    parser.add_argument(
        "--profile",
        choices=(BALANCED_PROFILE, REALISTIC_PROFILE),
        default=BALANCED_PROFILE,
        help="balanced keeps equal category counts; realistic uses production-like class distribution.",
    )
    parser.add_argument("--train-per-category", type=int, default=200)
    parser.add_argument("--validation-per-category", type=int, default=50)
    parser.add_argument("--test-per-category", type=int, default=50)
    parser.add_argument("--train-size", type=int, default=2000)
    parser.add_argument("--validation-size", type=int, default=500)
    parser.add_argument("--test-size", type=int, default=500)
    parser.add_argument("--amount-min", default="10.00")
    parser.add_argument("--amount-max", default="5000.00")
    parser.add_argument("--seed", type=int, default=42)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    summary = export_datasets(
        DatasetExportConfig(
            output_dir=args.output_dir,
            dataset_profile=args.profile,
            train_per_category=args.train_per_category,
            validation_per_category=args.validation_per_category,
            test_per_category=args.test_per_category,
            train_size=args.train_size,
            validation_size=args.validation_size,
            test_size=args.test_size,
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
        print(f"  labels={split_summary['labels']}")
        print(f"  profiles={split_summary['profiles']}")


if __name__ == "__main__":
    main()
