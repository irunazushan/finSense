from __future__ import annotations

from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal
import json
from pathlib import Path
import sys
from uuid import UUID


TESTER_DIR = Path(__file__).resolve().parents[1]
if str(TESTER_DIR) not in sys.path:
    sys.path.insert(0, str(TESTER_DIR))

from generator import generate_transactions, load_category_templates  # noqa: E402
from models import GeneratorConfig  # noqa: E402


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
        send_interval_ms=0,
        seed=7,
        verify_after_send=False,
    )


def test_load_category_templates_excludes_undefined_and_uses_enum() -> None:
    templates, categories = load_category_templates(RULES_PATH, ENUM_PATH)

    assert "UNDEFINED" not in categories
    assert "FOOD_AND_DRINKS" in categories
    assert "TRANSPORT" in categories
    assert "OTHER" in categories
    assert "FOOD_AND_DRINKS" in templates
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
    assert len(result.transactions) == 1

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

    encoded = json.dumps(payload)
    decoded = json.loads(encoded)
    assert decoded["transactionId"] == payload["transactionId"]


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
    assert result.category_totals["HEALTH"] == 2
    assert sum(result.category_totals.values()) == 6

    per_user = Counter(tx.user_id for tx in result.transactions)
    assert all(count == 3 for count in per_user.values())


def test_ambiguous_generation_creates_low_signal_events() -> None:
    templates, categories = load_category_templates(RULES_PATH, ENUM_PATH)
    config = make_config(
        category_counts={"SHOPPING": 20},
        users_count=1,
        tx_per_user=20,
        random_fill_enabled=False,
        ambiguous_ratio=1.0,
    )

    result = generate_transactions(config, templates, categories)
    assert result.ambiguous_count == 20
    assert all(tx.is_ambiguous for tx in result.transactions)

    known_keywords = {
        keyword
        for template in templates.values()
        for keyword in template.keywords
    }
    for tx in result.transactions:
        description = (tx.payload.get("description") or "").lower()
        assert all(keyword not in description for keyword in known_keywords)


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
