from __future__ import annotations

import argparse
from pathlib import Path

from ml_training.dataset import DatasetExportConfig, export_datasets
from ml_training.realistic_generator import (
    BALANCED_PROFILE,
    REALISTIC_PROFILE,
    SPLIT_STRATEGY_HOLDOUT,
    SPLIT_STRATEGY_MIXED,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Export behavior-driven labeled transaction datasets.")
    parser.add_argument("--output-dir", type=Path, default=Path("data"))
    parser.add_argument(
        "--profile",
        choices=(BALANCED_PROFILE, REALISTIC_PROFILE),
        default=REALISTIC_PROFILE,
        help="balanced keeps equal category counts; realistic uses production-like class distribution.",
    )
    parser.add_argument(
        "--split-strategy",
        choices=(SPLIT_STRATEGY_HOLDOUT, SPLIT_STRATEGY_MIXED),
        default=SPLIT_STRATEGY_HOLDOUT,
        help="holdout_merchants keeps validation/test on unseen merchant groups; mixed reuses the full pool.",
    )
    parser.add_argument("--train-per-category", type=int, default=200)
    parser.add_argument("--validation-per-category", type=int, default=50)
    parser.add_argument("--test-per-category", type=int, default=50)
    parser.add_argument("--train-size", type=int, default=20000)
    parser.add_argument("--validation-size", type=int, default=5000)
    parser.add_argument("--test-size", type=int, default=5000)
    parser.add_argument("--amount-min", default="10.00")
    parser.add_argument("--amount-max", default="50000.00")
    parser.add_argument("--users-per-split", type=int, default=64)
    parser.add_argument("--holdout-ratio", type=float, default=0.18)
    parser.add_argument("--seed", type=int, default=42)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    summary = export_datasets(
        DatasetExportConfig(
            output_dir=args.output_dir,
            dataset_profile=args.profile,
            split_strategy=args.split_strategy,
            train_per_category=args.train_per_category,
            validation_per_category=args.validation_per_category,
            test_per_category=args.test_per_category,
            train_size=args.train_size,
            validation_size=args.validation_size,
            test_size=args.test_size,
            amount_min=args.amount_min,
            amount_max=args.amount_max,
            users_per_split=args.users_per_split,
            holdout_ratio=args.holdout_ratio,
            seed=args.seed,
        )
    )

    metadata = summary.pop("metadata", {})
    for split_name, split_summary in summary.items():
        print(
            f"{split_name}: rows={split_summary['rows']} "
            f"path={split_summary['path']}"
        )
        print(f"  unique_users={split_summary['unique_users']}")
        print(f"  labels={split_summary['labels']}")
        print(f"  top_profiles={top_counts(split_summary['profiles'])}")
    if metadata:
        print(f"metadata: {metadata['path']}")
        print(
            f"  dataset_id={metadata['dataset_id']} "
            f"profile={metadata['profile']} split_strategy={metadata['split_strategy']}"
        )


def top_counts(counts: object, limit: int = 12) -> dict[str, int]:
    if not isinstance(counts, dict):
        return {}
    items = sorted(
        ((str(key), int(value)) for key, value in counts.items()),
        key=lambda item: item[1],
        reverse=True,
    )
    return dict(items[:limit])


if __name__ == "__main__":
    main()
