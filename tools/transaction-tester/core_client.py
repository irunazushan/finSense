from __future__ import annotations

from collections import Counter
import time
from typing import Dict, Iterable, List, Set

import requests

from models import VerificationSummary


def poll_generated_transactions(
    core_base_url: str,
    user_ids: Iterable[str],
    expected_transaction_ids: Iterable[str],
    timeout_seconds: int,
    poll_interval_seconds: int,
) -> VerificationSummary:
    expected_ids: Set[str] = set(expected_transaction_ids)
    if not expected_ids:
        return VerificationSummary(
            expected_count=0,
            found_count=0,
            missing_count=0,
            status_counts={},
            category_counts={},
            missing_transaction_ids=[],
        )

    deadline = time.monotonic() + timeout_seconds
    matched_records: Dict[str, dict] = {}

    while time.monotonic() <= deadline:
        matched_records = _fetch_matching_transactions(core_base_url, user_ids, expected_ids)
        if len(matched_records) >= len(expected_ids):
            break
        time.sleep(max(1, poll_interval_seconds))

    status_counts: Counter[str] = Counter()
    category_counts: Counter[str] = Counter()

    for record in matched_records.values():
        status = str(record.get("status") or "UNKNOWN")
        category = str(record.get("category") or "UNDEFINED")
        status_counts[status] += 1
        category_counts[category] += 1

    missing_ids = sorted(expected_ids - set(matched_records.keys()))
    return VerificationSummary(
        expected_count=len(expected_ids),
        found_count=len(matched_records),
        missing_count=len(missing_ids),
        status_counts=dict(status_counts),
        category_counts=dict(category_counts),
        missing_transaction_ids=missing_ids,
    )


def _fetch_matching_transactions(
    core_base_url: str,
    user_ids: Iterable[str],
    expected_ids: Set[str],
) -> Dict[str, dict]:
    session = requests.Session()
    matched: Dict[str, dict] = {}

    for user_id in user_ids:
        page = 0
        size = 200
        while True:
            response = session.get(
                f"{core_base_url.rstrip('/')}/api/v1/users/{user_id}/transactions",
                params={"page": page, "size": size},
                timeout=10,
            )
            response.raise_for_status()
            items = response.json()
            if not isinstance(items, list):
                raise RuntimeError("Unexpected core-service response: expected a JSON array")

            for item in items:
                tx_id = str(item.get("transactionId", ""))
                if tx_id in expected_ids:
                    matched[tx_id] = item

            if len(items) < size:
                break
            page += 1

    return matched

