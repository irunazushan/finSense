from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
import json
from pathlib import Path
import sys

import pytest
import requests


TESTER_DIR = Path(__file__).resolve().parents[1]
if str(TESTER_DIR) not in sys.path:
    sys.path.insert(0, str(TESTER_DIR))

from core_client import (  # noqa: E402
    apply_client_filters,
    build_transaction_query_params,
    fetch_user_transactions_all,
    fetch_user_transactions_page,
)
from models import ClientTransactionFilters, ServerTransactionFilters, TransactionRecord  # noqa: E402


class FakeResponse:
    def __init__(self, payload, status_error=None, text=None, json_error=None):
        self.payload = payload
        self.status_error = status_error
        self._json_error = json_error
        self.text = json.dumps(payload) if text is None else text
        self.status_code = 200

    def raise_for_status(self):
        if self.status_error is not None:
            raise self.status_error

    def json(self):
        if self._json_error is not None:
            raise self._json_error
        return self.payload


class FakeSession:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def get(self, url, params=None, timeout=10):
        self.calls.append((url, params, timeout))
        if not self.responses:
            raise RuntimeError("No fake response left")
        return self.responses.pop(0)


def make_record(
    tx_id: str,
    amount: str,
    merchant: str,
    mcc: str,
    description: str,
) -> dict:
    return {
        "transactionId": tx_id,
        "userId": "11111111-1111-1111-1111-111111111111",
        "accountId": "22222222-2222-2222-2222-222222222222",
        "amount": amount,
        "description": description,
        "merchantName": merchant,
        "mccCode": mcc,
        "transactionDate": "2026-03-01T10:00:00Z",
        "status": "CLASSIFIED",
        "category": "SHOPPING",
        "classifierSource": "ML",
        "classifierConfidence": 0.95,
        "classifiedAt": "2026-03-01T10:00:01Z",
    }


def test_build_transaction_query_params_includes_filters() -> None:
    filters = ServerTransactionFilters(
        category="SHOPPING",
        status="CLASSIFIED",
        from_datetime=datetime(2026, 3, 1, 0, 0, tzinfo=timezone.utc),
        to_datetime=datetime(2026, 3, 6, 23, 59, tzinfo=timezone.utc),
        page=2,
        size=75,
    )

    params = build_transaction_query_params(filters)
    assert params["category"] == "SHOPPING"
    assert params["status"] == "CLASSIFIED"
    assert params["from"] == "2026-03-01T00:00:00Z"
    assert params["to"] == "2026-03-06T23:59:00Z"
    assert params["page"] == 2
    assert params["size"] == 75


def test_apply_client_filters_uses_amount_and_text_criteria() -> None:
    records = [
        TransactionRecord.from_api_dict(make_record("t1", "49.99", "Starbucks", "5812", "coffee payment")),
        TransactionRecord.from_api_dict(make_record("t2", "120.00", "Amazon", "5311", "online order")),
        TransactionRecord.from_api_dict(make_record("t3", "340.00", "Metro", "4111", "monthly pass")),
    ]

    filters = ClientTransactionFilters(
        amount_min=Decimal("100"),
        amount_max=Decimal("150"),
        merchant_contains="AMA",
        mcc_code="5311",
        description_contains="ORDER",
    )
    filtered = apply_client_filters(records, filters)

    assert len(filtered) == 1
    assert filtered[0].transaction_id == "t2"


def test_fetch_user_transactions_all_paginates_until_last_page() -> None:
    session = FakeSession(
        responses=[
            FakeResponse([make_record("t1", "10", "A", "1111", "d1"), make_record("t2", "20", "B", "2222", "d2")]),
            FakeResponse([make_record("t3", "30", "C", "3333", "d3")]),
        ]
    )

    records = fetch_user_transactions_all(
        core_base_url="http://localhost:8080",
        user_id="11111111-1111-1111-1111-111111111111",
        filters=ServerTransactionFilters(page=0, size=2),
        session=session,
    )

    assert len(records) == 3
    assert session.calls[0][1]["page"] == 0
    assert session.calls[1][1]["page"] == 1


def test_fetch_user_transactions_page_raises_on_http_error() -> None:
    session = FakeSession(
        responses=[FakeResponse([], status_error=requests.HTTPError("boom"))]
    )

    with pytest.raises(RuntimeError, match="Core API error HTTP"):
        fetch_user_transactions_page(
            core_base_url="http://localhost:8080",
            user_id="11111111-1111-1111-1111-111111111111",
            filters=ServerTransactionFilters(page=0, size=50),
            session=session,
        )


def test_fetch_user_transactions_page_raises_on_invalid_payload() -> None:
    invalid_item = {
        "userId": "11111111-1111-1111-1111-111111111111",
        "accountId": "22222222-2222-2222-2222-222222222222",
        "amount": "1.00",
        "transactionDate": "2026-03-01T10:00:00Z",
    }
    session = FakeSession(responses=[FakeResponse([invalid_item])])

    with pytest.raises(RuntimeError):
        fetch_user_transactions_page(
            core_base_url="http://localhost:8080",
            user_id="11111111-1111-1111-1111-111111111111",
            filters=ServerTransactionFilters(page=0, size=50),
            session=session,
        )


def test_fetch_user_transactions_page_raises_on_empty_body() -> None:
    session = FakeSession(responses=[FakeResponse([], text="")])

    with pytest.raises(RuntimeError, match=r"empty response.*page=0.*size=50"):
        fetch_user_transactions_page(
            core_base_url="http://localhost:8080",
            user_id="11111111-1111-1111-1111-111111111111",
            filters=ServerTransactionFilters(page=0, size=50),
            session=session,
        )


def test_fetch_user_transactions_page_raises_on_non_json_body() -> None:
    session = FakeSession(
        responses=[
            FakeResponse(
                payload=[],
                text="<html>error</html>",
                json_error=ValueError("not json"),
            )
        ]
    )

    with pytest.raises(RuntimeError, match="non-JSON response"):
        fetch_user_transactions_page(
            core_base_url="http://localhost:8080",
            user_id="11111111-1111-1111-1111-111111111111",
            filters=ServerTransactionFilters(page=0, size=50),
            session=session,
        )


def test_fetch_user_transactions_all_stops_on_empty_body_after_first_page() -> None:
    session = FakeSession(
        responses=[
            FakeResponse([make_record("t1", "10", "A", "1111", "d1"), make_record("t2", "20", "B", "2222", "d2")]),
            FakeResponse([], text=""),
        ]
    )

    records = fetch_user_transactions_all(
        core_base_url="http://localhost:8080",
        user_id="11111111-1111-1111-1111-111111111111",
        filters=ServerTransactionFilters(page=0, size=2),
        session=session,
    )

    assert len(records) == 2
    assert records[0].transaction_id == "t1"
    assert records[1].transaction_id == "t2"
