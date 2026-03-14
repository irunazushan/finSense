from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any, Dict, List

from models import ToolDataset


REQUIRED_TOOL_KEYS = ("spendingByCategory", "monthlyDelta", "topMerchants", "spikes")

OPENAI_TOOL_DEFS: List[Dict[str, Any]] = [
    {
        "type": "function",
        "function": {
            "name": "getSpendingByCategory",
            "description": "Returns spending totals grouped by category for a user and period in days",
            "parameters": {
                "type": "object",
                "properties": {
                    "userId": {"type": "string"},
                    "periodDays": {"type": "integer"},
                },
                "required": ["userId", "periodDays"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "getMonthlyDelta",
            "description": "Returns category deltas between current and previous same-length periods",
            "parameters": {
                "type": "object",
                "properties": {
                    "userId": {"type": "string"},
                    "periodDays": {"type": "integer"},
                },
                "required": ["userId", "periodDays"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "getTopMerchants",
            "description": "Returns top merchants by total spending for a user and period in days",
            "parameters": {
                "type": "object",
                "properties": {
                    "userId": {"type": "string"},
                    "periodDays": {"type": "integer"},
                    "limit": {"type": "integer"},
                },
                "required": ["userId", "periodDays"],
                "additionalProperties": False,
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "detectSpikes",
            "description": "Detects unusual spending spikes by category for a user and period in days",
            "parameters": {
                "type": "object",
                "properties": {
                    "userId": {"type": "string"},
                    "periodDays": {"type": "integer"},
                },
                "required": ["userId", "periodDays"],
                "additionalProperties": False,
            },
        },
    },
]


def load_dataset_from_path(path: Path) -> ToolDataset:
    try:
        raw = path.read_text(encoding="utf-8")
    except Exception as exc:  # noqa: BLE001
        raise ValueError(f"Failed to read dataset file '{path}': {exc}") from exc
    return load_dataset_from_text(raw)


def load_dataset_from_text(raw: str) -> ToolDataset:
    try:
        payload = json.loads(raw)
    except Exception as exc:  # noqa: BLE001
        raise ValueError(f"Dataset is not valid JSON: {exc}") from exc
    return parse_dataset(payload)


def parse_dataset(payload: Dict[str, Any]) -> ToolDataset:
    if not isinstance(payload, dict):
        raise ValueError("Dataset root must be a JSON object")

    tools_payload = _extract_tools_payload(payload)
    _validate_tools_payload(tools_payload)

    return ToolDataset(
        spending_by_category=copy.deepcopy(tools_payload["spendingByCategory"]),
        monthly_delta=copy.deepcopy(tools_payload["monthlyDelta"]),
        top_merchants=copy.deepcopy(tools_payload["topMerchants"]),
        spikes=copy.deepcopy(tools_payload["spikes"]),
    )


def execute_tool(name: str, arguments: Dict[str, Any], dataset: ToolDataset) -> Any:
    _ = arguments  # validated by model schema at LLM layer; args unused in dummy data mode.

    if name == "getSpendingByCategory":
        return copy.deepcopy(dataset.spending_by_category)
    if name == "getMonthlyDelta":
        return copy.deepcopy(dataset.monthly_delta)
    if name == "getTopMerchants":
        limit = _coerce_limit(arguments.get("limit"))
        items = dataset.top_merchants
        return copy.deepcopy(items if limit is None else items[:limit])
    if name == "detectSpikes":
        return copy.deepcopy(dataset.spikes)
    raise ValueError(f"Unknown tool '{name}'")


def _extract_tools_payload(payload: Dict[str, Any]) -> Dict[str, Any]:
    if "tools" in payload:
        tools_payload = payload["tools"]
        if isinstance(tools_payload, dict):
            return tools_payload

    advice_data = payload.get("adviceData")
    if isinstance(advice_data, dict) and isinstance(advice_data.get("tools"), dict):
        return advice_data["tools"]

    raise ValueError("Dataset must include either 'tools' or 'adviceData.tools' object")


def _validate_tools_payload(tools_payload: Dict[str, Any]) -> None:
    for key in REQUIRED_TOOL_KEYS:
        if key not in tools_payload:
            raise ValueError(f"Dataset tools payload is missing '{key}'")
        if not isinstance(tools_payload[key], list):
            raise ValueError(f"Dataset field '{key}' must be an array")

    _validate_items(tools_payload["spendingByCategory"], "spendingByCategory")
    _validate_items(tools_payload["monthlyDelta"], "monthlyDelta")
    _validate_items(tools_payload["topMerchants"], "topMerchants")
    _validate_items(tools_payload["spikes"], "spikes")


def _validate_items(items: List[Any], field_name: str) -> None:
    for idx, item in enumerate(items):
        if not isinstance(item, dict):
            raise ValueError(f"Dataset field '{field_name}' item #{idx} must be an object")


def _coerce_limit(raw_limit: Any) -> int | None:
    if raw_limit is None:
        return None
    try:
        value = int(raw_limit)
    except Exception:  # noqa: BLE001
        return None
    return max(0, value)
