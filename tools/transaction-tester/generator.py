from __future__ import annotations

from collections import Counter
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
import random
import re
import uuid
from typing import Dict, List, Optional, Sequence, Tuple

import yaml

from models import CategoryTemplate, GeneratedTransaction, GenerationResult, GeneratorConfig


AMBIGUOUS_DESCRIPTIONS: Sequence[str] = (
    "misc card operation",
    "general account transfer",
    "service adjustment",
    "miscellaneous payment",
    "balance correction",
    "fee and transfer",
)

AMBIGUOUS_MERCHANTS: Sequence[str] = (
    "Unknown Merchant",
    "Card Terminal",
    "Online Gateway",
    "Generic Payment",
)

UNKNOWN_MCC_CODES: Sequence[str] = ("0000", "5999", "7299")


def load_category_templates(
    rules_path: Path,
    enum_path: Optional[Path] = None,
) -> Tuple[Dict[str, CategoryTemplate], List[str]]:
    if not rules_path.exists():
        raise FileNotFoundError(f"Rules file not found: {rules_path}")

    with rules_path.open("r", encoding="utf-8") as file:
        document = yaml.safe_load(file) or {}

    mcc_map = document.get("mcc") or {}
    keywords_list = document.get("keywords") or []

    mcc_by_category: Dict[str, set[str]] = {}
    keywords_by_category: Dict[str, set[str]] = {}

    for raw_mcc, raw_category in mcc_map.items():
        category = normalize_category(raw_category)
        if category == "UNDEFINED":
            continue
        mcc_by_category.setdefault(category, set()).add(str(raw_mcc).strip())

    for entry in keywords_list:
        if not isinstance(entry, dict):
            continue
        category = normalize_category(entry.get("category"))
        if category == "UNDEFINED":
            continue
        words = entry.get("words") or []
        for word in words:
            normalized_word = str(word).strip().lower()
            if normalized_word:
                keywords_by_category.setdefault(category, set()).add(normalized_word)

    enum_categories = _load_enum_categories(enum_path) if enum_path else []
    if enum_categories:
        allowed_categories = [category for category in enum_categories if category != "UNDEFINED"]
    else:
        allowed_categories = sorted(set(mcc_by_category.keys()) | set(keywords_by_category.keys()))

    templates: Dict[str, CategoryTemplate] = {}
    for category in allowed_categories:
        templates[category] = CategoryTemplate(
            category=category,
            mcc_codes=sorted(mcc_by_category.get(category, set())),
            keywords=sorted(keywords_by_category.get(category, set())),
        )

    return templates, allowed_categories


def generate_transactions(
    config: GeneratorConfig,
    templates: Dict[str, CategoryTemplate],
    allowed_categories: Sequence[str],
) -> GenerationResult:
    _validate_config(config, allowed_categories)

    rng = random.Random(config.seed)
    total_transactions = config.users_count * config.tx_per_user
    category_plan = _build_category_plan(
        total_transactions=total_transactions,
        category_counts=config.category_counts,
        random_fill_enabled=config.random_fill_enabled,
        allowed_categories=allowed_categories,
        rng=rng,
    )

    user_ids = [str(uuid.uuid4()) for _ in range(config.users_count)]

    transactions: List[GeneratedTransaction] = []
    category_totals: Counter[str] = Counter()
    ambiguous_count = 0

    category_cursor = 0
    for user_id in user_ids:
        for _ in range(config.tx_per_user):
            category = category_plan[category_cursor]
            category_cursor += 1

            is_ambiguous = config.ambiguous_ratio > 0 and rng.random() < config.ambiguous_ratio
            transaction = _build_transaction(
                user_id=user_id,
                category=category,
                template=templates.get(category, CategoryTemplate(category=category)),
                is_ambiguous=is_ambiguous,
                config=config,
                rng=rng,
            )
            transactions.append(transaction)
            category_totals[category] += 1
            if is_ambiguous:
                ambiguous_count += 1

    return GenerationResult(
        user_ids=user_ids,
        transactions=transactions,
        category_totals=dict(category_totals),
        ambiguous_count=ambiguous_count,
    )


def normalize_category(raw_value: object) -> str:
    return str(raw_value or "").strip().upper()


def _load_enum_categories(enum_path: Optional[Path]) -> List[str]:
    if enum_path is None or not enum_path.exists():
        return []

    categories: List[str] = []
    enum_member_pattern = re.compile(r"^\s*([A-Z_]+)\s*[,;]?\s*$")

    with enum_path.open("r", encoding="utf-8") as file:
        for line in file:
            match = enum_member_pattern.match(line)
            if match:
                categories.append(match.group(1))

    return categories


def _validate_config(config: GeneratorConfig, allowed_categories: Sequence[str]) -> None:
    if config.users_count <= 0:
        raise ValueError("users_count must be greater than 0")
    if config.tx_per_user <= 0:
        raise ValueError("tx_per_user must be greater than 0")
    if config.amount_min <= 0:
        raise ValueError("amount_min must be greater than 0")
    if config.amount_max < config.amount_min:
        raise ValueError("amount_max must be greater than or equal to amount_min")
    if config.start_datetime > config.end_datetime:
        raise ValueError("start_datetime must be before or equal to end_datetime")
    if config.send_interval_ms < 0:
        raise ValueError("send_interval_ms must be greater than or equal to 0")
    if config.ambiguous_ratio < 0 or config.ambiguous_ratio > 1:
        raise ValueError("ambiguous_ratio must be in range [0.0, 1.0]")
    if not allowed_categories:
        raise ValueError("No categories available from rules/enum")

    total_transactions = config.users_count * config.tx_per_user
    selected_total = 0
    for category, count in config.category_counts.items():
        if category not in allowed_categories:
            raise ValueError(f"Category '{category}' is not allowed")
        if count < 0:
            raise ValueError(f"Category count for '{category}' must be >= 0")
        selected_total += count

    if selected_total > total_transactions:
        raise ValueError(
            f"Selected category count ({selected_total}) exceeds total transactions ({total_transactions})"
        )
    if selected_total < total_transactions and not config.random_fill_enabled:
        raise ValueError(
            "Category count is below total transactions and random fill is disabled"
        )


def _build_category_plan(
    total_transactions: int,
    category_counts: Dict[str, int],
    random_fill_enabled: bool,
    allowed_categories: Sequence[str],
    rng: random.Random,
) -> List[str]:
    plan: List[str] = []
    for category, count in category_counts.items():
        if count > 0:
            plan.extend([category] * count)

    remaining = total_transactions - len(plan)
    if remaining > 0:
        if not random_fill_enabled:
            raise ValueError("Not enough category counts and random fill is disabled")
        for _ in range(remaining):
            plan.append(rng.choice(list(allowed_categories)))

    rng.shuffle(plan)
    return plan


def _build_transaction(
    user_id: str,
    category: str,
    template: CategoryTemplate,
    is_ambiguous: bool,
    config: GeneratorConfig,
    rng: random.Random,
) -> GeneratedTransaction:
    transaction_id = str(uuid.uuid4())
    timestamp = _random_timestamp(config.start_datetime, config.end_datetime, rng)
    amount = _random_amount(config.amount_min, config.amount_max, rng)

    if is_ambiguous:
        description = rng.choice(list(AMBIGUOUS_DESCRIPTIONS))
        merchant_name = rng.choice(list(AMBIGUOUS_MERCHANTS))
        mcc_code = rng.choice(list(UNKNOWN_MCC_CODES)) if rng.random() < 0.4 else None
    else:
        description = _build_description(template, category, rng)
        merchant_name = _build_merchant_name(template, category, rng)
        mcc_code = rng.choice(template.mcc_codes) if template.mcc_codes and rng.random() < 0.7 else None

    payload = {
        "transactionId": transaction_id,
        "userId": user_id,
        "amount": amount,
        "description": description,
        "merchantName": merchant_name,
        "mccCode": mcc_code,
        "timestamp": timestamp,
    }

    return GeneratedTransaction(
        transaction_id=transaction_id,
        user_id=user_id,
        category=category,
        is_ambiguous=is_ambiguous,
        payload=payload,
    )


def _build_description(template: CategoryTemplate, category: str, rng: random.Random) -> str:
    if template.keywords:
        keyword = rng.choice(template.keywords)
        return f"{keyword} payment"
    return f"{category.lower()} expense"


def _build_merchant_name(template: CategoryTemplate, category: str, rng: random.Random) -> str:
    if template.keywords:
        keyword = rng.choice(template.keywords).replace("_", " ").title()
        return f"{keyword} Store"
    return f"{category.replace('_', ' ').title()} Merchant"


def _random_amount(amount_min: Decimal, amount_max: Decimal, rng: random.Random) -> float:
    min_cents = int((amount_min * Decimal("100")).to_integral_value(rounding=ROUND_HALF_UP))
    max_cents = int((amount_max * Decimal("100")).to_integral_value(rounding=ROUND_HALF_UP))
    cents = rng.randint(min_cents, max_cents)
    value = Decimal(cents) / Decimal("100")
    return float(value)


def _random_timestamp(start_datetime: datetime, end_datetime: datetime, rng: random.Random) -> str:
    start_utc = _to_utc(start_datetime)
    end_utc = _to_utc(end_datetime)
    delta_seconds = int((end_utc - start_utc).total_seconds())

    if delta_seconds <= 0:
        result = start_utc
    else:
        result = start_utc + timedelta(seconds=rng.randint(0, delta_seconds))

    return result.isoformat().replace("+00:00", "Z")


def _to_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)

