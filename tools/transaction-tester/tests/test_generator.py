from __future__ import annotations

from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal
import json
from pathlib import Path
import sys
from uuid import UUID

import pytest

TESTER_DIR = Path(__file__).resolve().parents[1]
if str(TESTER_DIR) not in sys.path:
    sys.path.insert(0, str(TESTER_DIR))

from generator import calculate_profile_targets, generate_transactions, load_category_templates  # noqa: E402
from generator import AMBIGUOUS_DESCRIPTIONS, AMBIGUOUS_MERCHANTS, UNKNOWN_MCC_CODES  # noqa: E402
from models import CategoryTemplate, GeneratorConfig  # noqa: E402


REPO_ROOT = Path(__file__).resolve().parents[3]
RULES_PATH = REPO_ROOT / "classifier-service" / "classifier-rules.yaml"
ENUM_PATH = (
    REPO_ROOT
    / "classifier-service"
    / "src"
    / "main"
    / "java"
    / "com"
    / "finsense"
    / "classifier"
    / "model"
    / "TransactionCategory.java"
)


def make_config(
    category_counts: dict[str, int],
    users_count: int = 2,
    tx_per_user: int = 3,
    random_fill_enabled: bool = True,
    ambiguous_ratio: float = 0.0,
    low_confidence_ratio: float = 0.0,
    target_user_id: str | None = None,
) -> GeneratorConfig:
    return GeneratorConfig(
        bootstrap_servers="localhost:29092",
        core_base_url="http://localhost:8080",
        users_count=users_count,
        tx_per_user=tx_per_user,
        target_user_id=target_user_id,
        amount_min=Decimal("10.00"),
        amount_max=Decimal("100.00"),
        start_datetime=datetime(2026, 1, 1, 0, 0, tzinfo=timezone.utc),
        end_datetime=datetime(2026, 1, 31, 23, 59, tzinfo=timezone.utc),
        category_counts=category_counts,
        random_fill_enabled=random_fill_enabled,
        ambiguous_ratio=ambiguous_ratio,
        low_confidence_ratio=low_confidence_ratio,
        send_interval_ms=0,
        seed=7,
        verify_after_send=False,
    )


def test_load_category_templates_excludes_undefined_and_uses_enum() -> None:
    templates, categories = load_category_templates(RULES_PATH, ENUM_PATH)

    assert "UNDEFINED" not in categories
    assert "FOOD_AND_DRINKS" in categories
    assert "RETAIL_SHOPPING" in categories
    assert "BANKING_AND_FEES" in categories
    assert len(templates["FOOD_AND_DRINKS"].mcc_codes) > 0


def test_generated_payload_schema_and_json_serialization() -> None:
    templates, categories = load_category_templates(RULES_PATH, ENUM_PATH)
    config = make_config(
        category_counts={"FOOD_AND_DRINKS": 1},
        users_count=1,
        tx_per_user=1,
        random_fill_enabled=False,
    )

    result = generate_transactions(config, templates, categories)
    payload = result.transactions[0].payload
    assert set(payload.keys()) == {
        "transactionId",
        "userId",
        "amount",
        "description",
        "merchantName",
        "mccCode",
        "timestamp",
    }
    UUID(payload["transactionId"])
    UUID(payload["userId"])
    assert isinstance(payload["amount"], float)
    assert payload["timestamp"].endswith("Z")
    assert json.loads(json.dumps(payload))["transactionId"] == payload["transactionId"]


def test_category_count_math_with_random_fill() -> None:
    templates, categories = load_category_templates(RULES_PATH, ENUM_PATH)
    config = make_config(
        category_counts={"HEALTH": 2},
        users_count=2,
        tx_per_user=3,
        random_fill_enabled=True,
    )

    result = generate_transactions(config, templates, categories)
    assert len(result.transactions) == 6
    assert result.category_totals["HEALTH"] >= 2
    assert sum(result.category_totals.values()) == 6
    per_user = Counter(tx.user_id for tx in result.transactions)
    assert all(count == 3 for count in per_user.values())


def test_ambiguous_generation_creates_low_signal_events() -> None:
    templates, categories = load_category_templates(RULES_PATH, ENUM_PATH)
    config = make_config(
        category_counts={"RETAIL_SHOPPING": 20},
        users_count=1,
        tx_per_user=20,
        random_fill_enabled=False,
        ambiguous_ratio=1.0,
    )

    result = generate_transactions(config, templates, categories)
    assert result.ambiguous_count == 20
    assert result.profile_totals["ambiguous"] == 20
    assert all(tx.is_ambiguous for tx in result.transactions)

    for tx in result.transactions:
        description = (tx.payload.get("description") or "").lower()
        merchant_name = tx.payload.get("merchantName") or ""
        mcc_code = tx.payload.get("mccCode")
        assert description in {value.lower() for value in AMBIGUOUS_DESCRIPTIONS}
        assert merchant_name in set(AMBIGUOUS_MERCHANTS)
        assert mcc_code is None or mcc_code in set(UNKNOWN_MCC_CODES)


def test_profile_allocation_targets_match_counts() -> None:
    templates, categories = load_category_templates(RULES_PATH, ENUM_PATH)
    config = make_config(
        category_counts={"HEALTH": 10},
        users_count=1,
        tx_per_user=10,
        random_fill_enabled=False,
        ambiguous_ratio=0.2,
        low_confidence_ratio=0.4,
    )

    result = generate_transactions(config, templates, categories)
    expected = calculate_profile_targets(10, 0.2, 0.4)
    assert result.profile_totals == expected
    assert result.ambiguous_count == expected["ambiguous"]


def test_low_confidence_transactions_use_mcc_and_cross_category_keywords() -> None:
    templates, categories = load_category_templates(RULES_PATH, ENUM_PATH)
    config = make_config(
        category_counts={"FOOD_AND_DRINKS": 20},
        users_count=1,
        tx_per_user=20,
        random_fill_enabled=False,
        low_confidence_ratio=1.0,
    )

    result = generate_transactions(config, templates, categories)
    assert result.profile_totals["low_confidence"] == 20

    target_keywords = set(templates["FOOD_AND_DRINKS"].keywords)
    other_keywords = {
        keyword
        for category, template in templates.items()
        if category != "FOOD_AND_DRINKS"
        for keyword in template.keywords
    }

    for tx in result.transactions:
        assert tx.profile == "low_confidence"
        assert tx.payload["mccCode"] in templates["FOOD_AND_DRINKS"].mcc_codes
        text = f"{tx.payload.get('description', '')} {tx.payload.get('merchantName', '')}".lower()
        assert all(keyword not in text for keyword in target_keywords)
        assert any(keyword in text for keyword in other_keywords)


def test_low_confidence_falls_back_to_normal_without_mcc_and_warns() -> None:
    templates = {
        "BILLS_AND_GOVERNMENT": CategoryTemplate(
            category="BILLS_AND_GOVERNMENT",
            mcc_codes=[],
            keywords=["utility", "bill"],
        )
    }
    categories = ["BILLS_AND_GOVERNMENT"]
    config = make_config(
        category_counts={"BILLS_AND_GOVERNMENT": 6},
        users_count=1,
        tx_per_user=6,
        random_fill_enabled=False,
        low_confidence_ratio=1.0,
    )

    result = generate_transactions(config, templates, categories)
    assert result.profile_totals["normal"] == 6
    assert "low_confidence" not in result.profile_totals
    assert result.warnings
    assert "BILLS_AND_GOVERNMENT" in result.warnings[0]


def test_ratio_validation_rejects_sum_above_one() -> None:
    templates, categories = load_category_templates(RULES_PATH, ENUM_PATH)
    config = make_config(
        category_counts={"HEALTH": 2},
        users_count=1,
        tx_per_user=2,
        random_fill_enabled=False,
        ambiguous_ratio=0.7,
        low_confidence_ratio=0.4,
    )

    with pytest.raises(ValueError, match="ambiguous_ratio \\+ low_confidence_ratio"):
        generate_transactions(config, templates, categories)


def test_target_user_id_routes_all_transactions_to_single_user() -> None:
    templates, categories = load_category_templates(RULES_PATH, ENUM_PATH)
    target_user = "11111111-1111-1111-1111-111111111111"
    config = make_config(
        category_counts={"FOOD_AND_DRINKS": 2},
        users_count=3,
        tx_per_user=2,
        random_fill_enabled=True,
        target_user_id=target_user,
    )

    result = generate_transactions(config, templates, categories)
    assert result.user_ids == [target_user]
    assert len(result.transactions) == 6
    assert all(tx.user_id == target_user for tx in result.transactions)
