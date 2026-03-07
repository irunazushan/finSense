from __future__ import annotations

from collections import Counter
from decimal import Decimal
import time
from typing import Dict, Iterable, List, Optional, Set

import requests

from models import (
    ClientTransactionFilters,
    ServerTransactionFilters,
    TransactionRecord,
    VerificationSummary,
)


def build_transaction_query_params(filters: ServerTransactionFilters) -> Dict[str, object]:
    params: Dict[str, object] = {
        "page": max(0, filters.page),
        "size": max(1, min(200, filters.size)),
    }
    if filters.category:
        params["category"] = filters.category
    if filters.status:
        params["status"] = filters.status
    if filters.from_datetime:
        params["from"] = filters.from_datetime.isoformat().replace("+00:00", "Z")
    if filters.to_datetime:
        params["to"] = filters.to_datetime.isoformat().replace("+00:00", "Z")
    return params


def fetch_user_transactions_page(
    core_base_url: str,
    user_id: str,
    filters: ServerTransactionFilters,
    session: Optional[requests.Session] = None,
    timeout_seconds: int = 10,
) -> List[TransactionRecord]:
    api = _get_session(session)
    response = api.get(
        f"{core_base_url.rstrip('/')}/api/v1/users/{user_id}/transactions",
        params=build_transaction_query_params(filters),
        timeout=timeout_seconds,
    )
    try:
        response.raise_for_status()
    except requests.HTTPError as exc:
        raise RuntimeError(
            f"Core API error HTTP {response.status_code}: {_response_snippet(response)}"
        ) from exc

    response_text = (response.text or "").strip()
    if not response_text:
        raise RuntimeError(f"Core API returned empty response (HTTP {response.status_code})")

    try:
        items = response.json()
    except ValueError as exc:
        raise RuntimeError(
            f"Core API returned non-JSON response (HTTP {response.status_code}): "
            f"{_response_snippet(response)}"
        ) from exc

    if not isinstance(items, list):
        raise RuntimeError("Unexpected core-service response: expected a JSON array")
    return [_to_transaction_record(item) for item in items]


def fetch_user_transactions_all(
    core_base_url: str,
    user_id: str,
    filters: ServerTransactionFilters,
    session: Optional[requests.Session] = None,
    timeout_seconds: int = 10,
    max_pages: int = 2000,
) -> List[TransactionRecord]:
    page = max(0, filters.page)
    size = max(1, min(200, filters.size))
    all_records: List[TransactionRecord] = []
    api = _get_session(session)

    for _ in range(max_pages):
        page_filters = ServerTransactionFilters(
            category=filters.category,
            status=filters.status,
            from_datetime=filters.from_datetime,
            to_datetime=filters.to_datetime,
            page=page,
            size=size,
        )
        records = fetch_user_transactions_page(
            core_base_url=core_base_url,
            user_id=user_id,
            filters=page_filters,
            session=api,
            timeout_seconds=timeout_seconds,
        )
        all_records.extend(records)
        if len(records) < size:
            break
        page += 1

    return all_records


def apply_client_filters(
    records: Iterable[TransactionRecord],
    filters: ClientTransactionFilters,
) -> List[TransactionRecord]:
    filtered: List[TransactionRecord] = []
    merchant_substr = (filters.merchant_contains or "").strip().lower()
    description_substr = (filters.description_contains or "").strip().lower()
    mcc_code = (filters.mcc_code or "").strip()

    for record in records:
        if filters.amount_min is not None and record.amount < filters.amount_min:
            continue
        if filters.amount_max is not None and record.amount > filters.amount_max:
            continue
        if merchant_substr and merchant_substr not in (record.merchant_name or "").lower():
            continue
        if description_substr and description_substr not in (record.description or "").lower():
            continue
        if mcc_code and (record.mcc_code or "") != mcc_code:
            continue
        filtered.append(record)

    return filtered


def aggregate_transactions(
    records: Iterable[TransactionRecord],
) -> tuple[Dict[str, int], Dict[str, int]]:
    status_counts: Counter[str] = Counter()
    category_counts: Counter[str] = Counter()

    for record in records:
        status_counts[record.status or "UNKNOWN"] += 1
        category_counts[record.category or "UNDEFINED"] += 1

    return dict(status_counts), dict(category_counts)


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
    matched_records: Dict[str, TransactionRecord] = {}

    while time.monotonic() <= deadline:
        matched_records = _fetch_matching_transactions(core_base_url, user_ids, expected_ids)
        if len(matched_records) >= len(expected_ids):
            break
        time.sleep(max(1, poll_interval_seconds))

    status_counts, category_counts = aggregate_transactions(matched_records.values())
    missing_ids = sorted(expected_ids - set(matched_records.keys()))

    return VerificationSummary(
        expected_count=len(expected_ids),
        found_count=len(matched_records),
        missing_count=len(missing_ids),
        status_counts=status_counts,
        category_counts=category_counts,
        missing_transaction_ids=missing_ids,
    )


def _fetch_matching_transactions(
    core_base_url: str,
    user_ids: Iterable[str],
    expected_ids: Set[str],
) -> Dict[str, TransactionRecord]:
    matched: Dict[str, TransactionRecord] = {}
    session = requests.Session()

    for user_id in user_ids:
        records = fetch_user_transactions_all(
            core_base_url=core_base_url,
            user_id=user_id,
            filters=ServerTransactionFilters(page=0, size=200),
            session=session,
        )
        for record in records:
            if record.transaction_id in expected_ids:
                matched[record.transaction_id] = record

    return matched


def _to_transaction_record(item: object) -> TransactionRecord:
    if not isinstance(item, dict):
        raise RuntimeError("Unexpected transaction payload: expected object item")

    required = ("transactionId", "userId", "accountId", "amount", "transactionDate")
    for key in required:
        if item.get(key) is None or str(item.get(key)).strip() == "":
            raise RuntimeError(f"Unexpected transaction payload: missing '{key}'")

    try:
        _ = Decimal(str(item.get("amount")))
    except Exception as exc:  # noqa: BLE001
        raise RuntimeError("Unexpected transaction payload: invalid 'amount'") from exc

    return TransactionRecord.from_api_dict(item)


def _get_session(session: Optional[requests.Session]) -> requests.Session:
    return session if session is not None else requests.Session()


def _response_snippet(response: requests.Response, max_length: int = 200) -> str:
    text = (response.text or "").strip()
    if not text:
        return "<empty body>"
    if len(text) > max_length:
        return text[:max_length] + "..."
    return text
