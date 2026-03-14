from __future__ import annotations

from pathlib import Path
import sys

import pytest

PROMPT_LAB_DIR = Path(__file__).resolve().parents[1]
if str(PROMPT_LAB_DIR) not in sys.path:
    sys.path.insert(0, str(PROMPT_LAB_DIR))

from tool_runtime import execute_tool, parse_dataset  # noqa: E402


def sample_tools_payload() -> dict:
    return {
        "spendingByCategory": [{"category": "A", "totalAmount": 10, "transactionCount": 1}],
        "monthlyDelta": [{"category": "A", "deltaAmount": 1}],
        "topMerchants": [
            {"merchantName": "M1", "totalAmount": 100, "transactionCount": 2},
            {"merchantName": "M2", "totalAmount": 50, "transactionCount": 1},
        ],
        "spikes": [{"category": "A", "date": "2026-01-01", "spikeAmount": 30, "baselineAmount": 10}],
    }


def test_parse_dataset_accepts_tools_root_shape() -> None:
    dataset = parse_dataset({"tools": sample_tools_payload()})
    assert len(dataset.spending_by_category) == 1
    assert len(dataset.monthly_delta) == 1
    assert len(dataset.top_merchants) == 2
    assert len(dataset.spikes) == 1


def test_parse_dataset_accepts_advice_data_shape() -> None:
    dataset = parse_dataset({"adviceData": {"tools": sample_tools_payload()}})
    assert dataset.top_merchants[0]["merchantName"] == "M1"


def test_parse_dataset_rejects_missing_required_arrays() -> None:
    payload = sample_tools_payload()
    del payload["spikes"]
    with pytest.raises(ValueError, match="missing 'spikes'"):
        parse_dataset({"tools": payload})


def test_execute_tool_applies_limit_for_top_merchants() -> None:
    dataset = parse_dataset({"tools": sample_tools_payload()})
    result = execute_tool(
        "getTopMerchants",
        {"userId": "u", "periodDays": 30, "limit": 1},
        dataset,
    )
    assert isinstance(result, list)
    assert len(result) == 1
    assert result[0]["merchantName"] == "M1"


def test_execute_tool_rejects_unknown_tool_name() -> None:
    dataset = parse_dataset({"tools": sample_tools_payload()})
    with pytest.raises(ValueError, match="Unknown tool"):
        execute_tool("unknownTool", {}, dataset)
