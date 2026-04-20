from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
import random
import re
import uuid
from typing import Dict, Iterable, List, Mapping, Sequence

import yaml

from .paths import TRANSACTION_CATALOG_PATH


BALANCED_PROFILE = "balanced"
REALISTIC_PROFILE = "realistic"

BASE_PROFILES = (
    "clean",
    "noisy_text",
    "missing_mcc",
    "wrong_mcc",
    "payment_provider",
    "mixed_language",
    "short_description",
    "merchant_only",
    "description_only",
    "hard_negative",
)

ROW_COLUMNS = [
    "transactionId",
    "userId",
    "amount",
    "description",
    "merchantName",
    "mccCode",
    "timestamp",
    "label",
    "intendedCategory",
    "profile",
]


@dataclass(frozen=True)
class RealisticGenerationConfig:
    split_name: str
    dataset_profile: str
    per_category_count: int
    total_count: int
    amount_min: Decimal
    amount_max: Decimal
    seed: int


def generate_realistic_rows(config: RealisticGenerationConfig) -> List[Dict[str, object]]:
    catalog = load_catalog(TRANSACTION_CATALOG_PATH)
    categories = list(catalog["categories"].keys())
    if "UNDEFINED" not in categories:
        categories.append("UNDEFINED")
    rng = random.Random(config.seed)

    category_plan = build_category_plan(
        categories=categories,
        catalog=catalog,
        dataset_profile=config.dataset_profile,
        per_category_count=config.per_category_count,
        total_count=config.total_count,
        rng=rng,
    )
    rng.shuffle(category_plan)

    all_mcc_codes = collect_mcc_codes(catalog)
    user_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"finsense:{config.split_name}:realistic:user:{config.seed}"))
    rows: List[Dict[str, object]] = []
    for index, intended_category in enumerate(category_plan):
        profile = pick_profile(intended_category, catalog, rng)
        row = build_transaction_row(
            split_name=config.split_name,
            index=index,
            user_id=user_id,
            intended_category=intended_category,
            profile=profile,
            catalog=catalog,
            all_mcc_codes=all_mcc_codes,
            amount_min=config.amount_min,
            amount_max=config.amount_max,
            seed=config.seed,
            rng=rng,
        )
        rows.append(row)

    return rows


def load_catalog(path: Path) -> Dict[str, object]:
    with path.open("r", encoding="utf-8") as file:
        document = yaml.safe_load(file) or {}
    if not isinstance(document.get("categories"), dict):
        raise ValueError("transaction catalog must define categories")
    return document


def build_category_plan(
    categories: Sequence[str],
    catalog: Mapping[str, object],
    dataset_profile: str,
    per_category_count: int,
    total_count: int,
    rng: random.Random,
) -> List[str]:
    if dataset_profile == BALANCED_PROFILE:
        if per_category_count <= 0:
            raise ValueError("per_category_count must be greater than 0")
        return [
            category
            for category in categories
            for _ in range(per_category_count)
        ]

    if dataset_profile != REALISTIC_PROFILE:
        raise ValueError("dataset_profile must be 'balanced' or 'realistic'")
    if total_count <= 0:
        raise ValueError("total_count must be greater than 0")

    distributions = catalog.get("distributions") or {}
    realistic_distribution = distributions.get(REALISTIC_PROFILE) if isinstance(distributions, dict) else None
    if not isinstance(realistic_distribution, dict):
        raise ValueError("transaction catalog must define distributions.realistic")

    plan: List[str] = []
    remainders: List[tuple[str, float]] = []
    for category in categories:
        raw_count = float(realistic_distribution.get(category, 0.0)) * total_count
        count = int(raw_count)
        plan.extend([category] * count)
        remainders.append((category, raw_count - count))

    remaining = total_count - len(plan)
    for category, _ in sorted(remainders, key=lambda item: item[1], reverse=True)[:remaining]:
        plan.append(category)

    while len(plan) < total_count:
        plan.append(rng.choice(list(categories)))
    return plan[:total_count]


def pick_profile(intended_category: str, catalog: Mapping[str, object], rng: random.Random) -> str:
    if intended_category == "UNDEFINED":
        return "ambiguous"

    weights = catalog.get("profileWeights") or {}
    if not isinstance(weights, dict):
        return "clean"

    profiles = [profile for profile in BASE_PROFILES if float(weights.get(profile, 0.0)) > 0]
    if not profiles:
        return "clean"
    profile_weights = [float(weights[profile]) for profile in profiles]
    return rng.choices(profiles, weights=profile_weights, k=1)[0]


def build_transaction_row(
    split_name: str,
    index: int,
    user_id: str,
    intended_category: str,
    profile: str,
    catalog: Mapping[str, object],
    all_mcc_codes: Mapping[str, Sequence[str]],
    amount_min: Decimal,
    amount_max: Decimal,
    seed: int,
    rng: random.Random,
) -> Dict[str, object]:
    label = intended_category
    category_config = get_category_config(catalog, intended_category)

    if intended_category == "UNDEFINED" or profile == "ambiguous":
        description, merchant_name, mcc_code = build_undefined_transaction(catalog, rng)
        label = "UNDEFINED"
    else:
        merchant = pick_merchant(category_config, rng)
        description = build_description(category_config, merchant, profile, rng)
        merchant_name = build_merchant_name(category_config, merchant, profile, rng)
        mcc_code = build_mcc_code(
            intended_category=intended_category,
            category_config=category_config,
            all_mcc_codes=all_mcc_codes,
            profile=profile,
            rng=rng,
        )
        description, merchant_name = apply_noise(
            description=description,
            merchant_name=merchant_name,
            merchant=merchant,
            profile=profile,
            catalog=catalog,
            rng=rng,
        )

    amount = random_amount(
        category_config=category_config,
        global_min=amount_min,
        global_max=amount_max,
        rng=rng,
    )
    timestamp = random_timestamp(rng)

    return {
        "transactionId": str(uuid.uuid5(uuid.NAMESPACE_URL, f"finsense:{split_name}:realistic:tx:{seed}:{index}")),
        "userId": user_id,
        "amount": amount,
        "description": description,
        "merchantName": merchant_name,
        "mccCode": mcc_code,
        "timestamp": timestamp,
        "label": label,
        "intendedCategory": intended_category,
        "profile": profile,
    }


def get_category_config(catalog: Mapping[str, object], category: str) -> Mapping[str, object]:
    categories = catalog.get("categories") or {}
    if not isinstance(categories, dict) or category not in categories:
        return {}
    value = categories[category]
    if not isinstance(value, dict):
        return {}
    return value


def pick_merchant(category_config: Mapping[str, object], rng: random.Random) -> str:
    merchants = list(category_config.get("merchants") or [])
    aliases = list(category_config.get("aliases") or [])
    candidates = merchants + aliases
    if not candidates:
        return "Generic Merchant"
    return str(rng.choice(candidates))


def build_description(
    category_config: Mapping[str, object],
    merchant: str,
    profile: str,
    rng: random.Random,
) -> str:
    if profile == "mixed_language":
        templates = list(category_config.get("mixedTemplates") or [])
    elif profile == "hard_negative":
        templates = list(category_config.get("hardNegatives") or [])
    elif profile == "short_description":
        templates = ["POS {merchant}", "{merchant}", "PAY {merchant}"]
    elif profile == "merchant_only":
        templates = ["Card purchase", "POS payment", "Online payment"]
    else:
        templates = list(category_config.get("templates") or [])

    if not templates:
        templates = ["Card purchase {merchant}"]
    return str(rng.choice(templates)).format(merchant=merchant)


def build_merchant_name(
    category_config: Mapping[str, object],
    merchant: str,
    profile: str,
    rng: random.Random,
) -> str:
    if profile == "description_only":
        return ""
    if profile == "noisy_text" and rng.random() < 0.5:
        aliases = list(category_config.get("aliases") or [])
        if aliases:
            return str(rng.choice(aliases))
    return merchant


def build_mcc_code(
    intended_category: str,
    category_config: Mapping[str, object],
    all_mcc_codes: Mapping[str, Sequence[str]],
    profile: str,
    rng: random.Random,
) -> str:
    if profile == "missing_mcc":
        return ""

    own_codes = [str(code) for code in category_config.get("mccCodes") or []]
    if profile == "wrong_mcc":
        other_codes = [
            code
            for category, codes in all_mcc_codes.items()
            if category != intended_category
            for code in codes
        ]
        return str(rng.choice(other_codes)) if other_codes else ""

    if not own_codes:
        return ""
    return str(rng.choice(own_codes)) if rng.random() < 0.82 else ""


def apply_noise(
    description: str,
    merchant_name: str,
    merchant: str,
    profile: str,
    catalog: Mapping[str, object],
    rng: random.Random,
) -> tuple[str, str]:
    if profile == "payment_provider":
        providers = list(catalog.get("paymentProviders") or ["PAYMENT"])
        provider = str(rng.choice(providers))
        description = rng.choice(
            [
                f"{provider} * {merchant}",
                f"{provider}/{merchant}",
                f"{provider} online payment {merchant}",
            ]
        )

    if profile == "noisy_text":
        cities = list(catalog.get("cities") or ["ONLINE"])
        city = str(rng.choice(cities))
        terminal = rng.randint(100000, 999999)
        description = f"{description} {city} TERM{terminal}"
        if rng.random() < 0.4:
            description = introduce_typo(description, rng)

    if profile == "merchant_only":
        merchant_name = f"{merchant} {rng.randint(100, 999)}"

    if rng.random() < 0.18:
        description = description.upper()
    elif rng.random() < 0.18:
        description = description.lower()

    return normalize_spaces(description), normalize_spaces(merchant_name)


def build_undefined_transaction(catalog: Mapping[str, object], rng: random.Random) -> tuple[str, str, str]:
    undefined = catalog.get("undefined") or {}
    descriptions = list(undefined.get("descriptions") or ["transaction"]) if isinstance(undefined, dict) else ["transaction"]
    merchants = list(undefined.get("merchants") or ["Unknown Merchant"]) if isinstance(undefined, dict) else ["Unknown Merchant"]
    unknown_mcc_codes = list(catalog.get("unknownMccCodes") or ["0000"])

    description = str(rng.choice(descriptions))
    merchant_name = str(rng.choice(merchants))
    if rng.random() < 0.35:
        description = f"{description} {rng.randint(1000, 999999)}"
    mcc_code = str(rng.choice(unknown_mcc_codes)) if rng.random() < 0.35 else ""
    return description, merchant_name, mcc_code


def collect_mcc_codes(catalog: Mapping[str, object]) -> Dict[str, List[str]]:
    categories = catalog.get("categories") or {}
    result: Dict[str, List[str]] = {}
    if not isinstance(categories, dict):
        return result
    for category, config in categories.items():
        if isinstance(config, dict):
            result[str(category)] = [str(code) for code in config.get("mccCodes") or []]
    return result


def random_amount(
    category_config: Mapping[str, object],
    global_min: Decimal,
    global_max: Decimal,
    rng: random.Random,
) -> float:
    raw_range = category_config.get("amountRange") or [global_min, global_max]
    category_min = Decimal(str(raw_range[0]))
    category_max = Decimal(str(raw_range[1]))
    min_value = max(global_min, category_min)
    max_value = min(global_max, category_max)
    if max_value < min_value:
        min_value, max_value = global_min, global_max

    min_cents = int((min_value * Decimal("100")).to_integral_value(rounding=ROUND_HALF_UP))
    max_cents = int((max_value * Decimal("100")).to_integral_value(rounding=ROUND_HALF_UP))
    cents = rng.randint(min_cents, max_cents)
    return float(Decimal(cents) / Decimal("100"))


def random_timestamp(rng: random.Random) -> str:
    start = datetime(2026, 1, 1, tzinfo=timezone.utc)
    end = datetime(2026, 12, 31, 23, 59, 59, tzinfo=timezone.utc)
    delta_seconds = int((end - start).total_seconds())
    result = start + timedelta(seconds=rng.randint(0, delta_seconds))
    return result.isoformat().replace("+00:00", "Z")


def introduce_typo(value: str, rng: random.Random) -> str:
    latin_positions = [index for index, char in enumerate(value) if char.isascii() and char.isalpha()]
    if not latin_positions:
        return value
    index = rng.choice(latin_positions)
    replacement = rng.choice("abcdefghijklmnopqrstuvwxyz")
    return value[:index] + replacement + value[index + 1 :]


def normalize_spaces(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def summarize_rows(rows: Iterable[Mapping[str, object]]) -> Dict[str, Dict[str, int]]:
    category_counts: Dict[str, int] = {}
    profile_counts: Dict[str, int] = {}
    for row in rows:
        label = str(row.get("label") or "UNDEFINED")
        profile = str(row.get("profile") or "unknown")
        category_counts[label] = category_counts.get(label, 0) + 1
        profile_counts[profile] = profile_counts.get(profile, 0) + 1
    return {
        "labels": dict(sorted(category_counts.items())),
        "profiles": dict(sorted(profile_counts.items())),
    }


def validate_rows(rows: Sequence[Mapping[str, object]]) -> None:
    for row in rows:
        missing = [column for column in ROW_COLUMNS if column not in row]
        if missing:
            raise ValueError(f"Generated row is missing columns: {', '.join(missing)}")
