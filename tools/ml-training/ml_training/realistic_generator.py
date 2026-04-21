from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
import hashlib
from pathlib import Path
import random
import re
import uuid
from typing import Dict, Iterable, List, Mapping, MutableMapping, Sequence

import yaml

from .paths import TRANSACTION_CATALOG_PATH


BALANCED_PROFILE = "balanced"
REALISTIC_PROFILE = "realistic"
SPLIT_STRATEGY_HOLDOUT = "holdout_merchants"
SPLIT_STRATEGY_MIXED = "mixed"

ROW_COLUMNS = [
    "transactionId",
    "userId",
    "amount",
    "description",
    "merchantName",
    "mccCode",
    "timestamp",
    "label",
]


MIXED_KEYBOARD_MAP = str.maketrans(
    {
        "a": "а",
        "e": "е",
        "k": "к",
        "m": "м",
        "h": "н",
        "o": "о",
        "p": "р",
        "c": "с",
        "t": "т",
        "x": "х",
        "y": "у",
        "A": "А",
        "B": "В",
        "C": "С",
        "E": "Е",
        "H": "Н",
        "K": "К",
        "M": "М",
        "O": "О",
        "P": "Р",
        "T": "Т",
        "X": "Х",
        "Y": "У",
    }
)


@dataclass(frozen=True)
class RealisticGenerationConfig:
    split_name: str
    dataset_profile: str
    per_category_count: int
    total_count: int
    amount_min: Decimal
    amount_max: Decimal
    seed: int
    split_strategy: str = SPLIT_STRATEGY_HOLDOUT
    users_per_split: int = 64
    holdout_ratio: float = 0.18


@dataclass
class UserProfile:
    user_id: str
    archetype: str
    city: str
    amount_multiplier: float
    category_weights: Mapping[str, float]
    channel_weights: Mapping[str, float]
    recurring_merchants: MutableMapping[str, Mapping[str, object]] = field(default_factory=dict)


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

    split_pools = build_split_pools(
        categories_config=catalog["categories"],
        split_name=config.split_name,
        split_strategy=config.split_strategy,
        holdout_ratio=config.holdout_ratio,
        seed=config.seed,
    )
    users = build_users(
        catalog=catalog,
        split_name=config.split_name,
        seed=config.seed,
        users_per_split=config.users_per_split,
        rng=rng,
    )
    all_mcc_codes = collect_mcc_codes(catalog)

    rows: List[Dict[str, object]] = []
    for index, intended_category in enumerate(category_plan):
        user = pick_user_for_category(users, intended_category, rng)
        row = build_transaction_row(
            split_name=config.split_name,
            index=index,
            intended_category=intended_category,
            user=user,
            catalog=catalog,
            split_pools=split_pools,
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
    if not isinstance(document.get("userArchetypes"), list) or not document["userArchetypes"]:
        raise ValueError("transaction catalog must define userArchetypes")
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
        return [category for category in categories for _ in range(per_category_count)]

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


def build_users(
    catalog: Mapping[str, object],
    split_name: str,
    seed: int,
    users_per_split: int,
    rng: random.Random,
) -> List[UserProfile]:
    users: List[UserProfile] = []
    archetypes = [item for item in catalog.get("userArchetypes") or [] if isinstance(item, dict)]
    if users_per_split <= 0:
        raise ValueError("users_per_split must be greater than 0")

    weights = [float(item.get("weight", 0.0)) for item in archetypes]
    if not archetypes or not any(weights):
        raise ValueError("userArchetypes must define positive weights")

    for index in range(users_per_split):
        archetype = rng.choices(archetypes, weights=weights, k=1)[0]
        name = str(archetype.get("name") or f"user_{index}")
        city = weighted_choice_map(archetype.get("cityWeights") or {}, rng, fallback="ONLINE")
        multiplier_range = archetype.get("amountMultiplier") or [0.85, 1.15]
        amount_multiplier = rng.uniform(float(multiplier_range[0]), float(multiplier_range[1]))
        users.append(
            UserProfile(
                user_id=str(uuid.uuid5(uuid.NAMESPACE_URL, f"finsense:{split_name}:user:{seed}:{index}")),
                archetype=name,
                city=city,
                amount_multiplier=amount_multiplier,
                category_weights=dict(archetype.get("preferredCategories") or {}),
                channel_weights=dict(archetype.get("channelWeights") or {}),
            )
        )
    return users


def build_split_pools(
    categories_config: Mapping[str, object],
    split_name: str,
    split_strategy: str,
    holdout_ratio: float,
    seed: int,
) -> Dict[str, Dict[str, object]]:
    if split_strategy not in {SPLIT_STRATEGY_HOLDOUT, SPLIT_STRATEGY_MIXED}:
        raise ValueError("split_strategy must be 'holdout_merchants' or 'mixed'")
    if holdout_ratio < 0 or holdout_ratio >= 1:
        raise ValueError("holdout_ratio must be in range [0.0, 1.0)")

    pools: Dict[str, Dict[str, object]] = {}
    for category, raw_config in categories_config.items():
        config = raw_config if isinstance(raw_config, dict) else {}
        groups = [group for group in config.get("merchantGroups") or [] if isinstance(group, dict)]
        ordered_groups = sorted(groups, key=lambda item: str(item.get("name") or ""))
        if split_strategy == SPLIT_STRATEGY_MIXED or len(ordered_groups) <= 1:
            pools[str(category)] = {"groups": ordered_groups, "pool_name": "mixed"}
            continue

        local_rng = random.Random(stable_seed(f"{seed}:{category}:holdout"))
        shuffled = ordered_groups[:]
        local_rng.shuffle(shuffled)
        holdout_count = min(len(shuffled) - 1, max(1, int(round(len(shuffled) * holdout_ratio))))
        eval_groups = shuffled[:holdout_count]
        train_groups = shuffled[holdout_count:]

        if split_name == "train":
            active_groups = train_groups or shuffled
            pool_name = "train_pool"
        else:
            active_groups = eval_groups or shuffled
            pool_name = "holdout_eval"
        pools[str(category)] = {"groups": active_groups, "pool_name": pool_name}
    return pools


def pick_user_for_category(users: Sequence[UserProfile], category: str, rng: random.Random) -> UserProfile:
    weights = [max(0.001, float(user.category_weights.get(category, 0.02))) for user in users]
    return rng.choices(list(users), weights=weights, k=1)[0]


def build_transaction_row(
    split_name: str,
    index: int,
    intended_category: str,
    user: UserProfile,
    catalog: Mapping[str, object],
    split_pools: Mapping[str, Mapping[str, object]],
    all_mcc_codes: Mapping[str, Sequence[str]],
    amount_min: Decimal,
    amount_max: Decimal,
    seed: int,
    rng: random.Random,
) -> Dict[str, object]:
    if intended_category == "UNDEFINED":
        description, merchant_name, mcc_code = build_undefined_transaction(catalog, user, rng)
        channel = weighted_choice_map(catalog["modifierWeights"]["channels"], rng, fallback="ecommerce")
        state = "normal"
        mcc_mode = "missing"
        pool_name = "undefined_pool"
        amount = random_amount(
            category_config={},
            global_min=amount_min,
            global_max=amount_max,
            multiplier=user.amount_multiplier,
            channel=channel,
            state=state,
            rng=rng,
        )
    else:
        category_config = get_category_config(catalog, intended_category)
        pool = split_pools.get(intended_category) or {"groups": [], "pool_name": "mixed"}
        merchant_group = pick_merchant_group(
            category=intended_category,
            user=user,
            groups=pool.get("groups") or [],
            channel_preferences=category_config.get("allowedChannels") or [],
            rng=rng,
        )
        channel = pick_channel(category_config, catalog, user, rng)
        state = pick_state(intended_category, channel, catalog, rng)
        merchant_name = pick_merchant_variant(merchant_group, rng)
        if state == "recurring":
            merchant_name = remember_recurring(user, intended_category, merchant_group, merchant_name)
        base_description = build_description(category_config, merchant_name, channel, rng)
        mcc_mode = pick_mcc_mode(catalog, channel, intended_category, rng)
        description, merchant_name = apply_noise(
            description=base_description,
            merchant_name=merchant_name,
            merchant_group=merchant_group,
            user=user,
            catalog=catalog,
            rng=rng,
        )
        description, merchant_name = apply_state(
            description=description,
            merchant_name=merchant_name,
            state=state,
            rng=rng,
        )
        mcc_code = build_mcc_code(
            intended_category=intended_category,
            category_config=category_config,
            all_mcc_codes=all_mcc_codes,
            mcc_mode=mcc_mode,
            merchant_name=merchant_name,
            description=description,
            catalog=catalog,
            rng=rng,
        )
        amount = random_amount(
            category_config=category_config,
            global_min=amount_min,
            global_max=amount_max,
            multiplier=user.amount_multiplier,
            channel=channel,
            state=state,
            rng=rng,
        )
        pool_name = str(pool.get("pool_name") or "mixed")

    timestamp = random_timestamp(
        rng=rng,
        category=intended_category,
        channel=channel,
        state=state,
    )
    profile = f"{user.archetype}|{channel}|{state}|{mcc_mode}|{pool_name}"
    return {
        "transactionId": str(uuid.uuid5(uuid.NAMESPACE_URL, f"finsense:{split_name}:tx:{seed}:{index}")),
        "userId": user.user_id,
        "amount": amount,
        "description": description,
        "merchantName": merchant_name,
        "mccCode": mcc_code,
        "timestamp": timestamp,
        "label": intended_category,
        "profile": profile,
    }


def get_category_config(catalog: Mapping[str, object], category: str) -> Mapping[str, object]:
    categories = catalog.get("categories") or {}
    if not isinstance(categories, dict):
        return {}
    value = categories.get(category) or {}
    return value if isinstance(value, dict) else {}


def pick_merchant_group(
    category: str,
    user: UserProfile,
    groups: Sequence[Mapping[str, object]],
    channel_preferences: Sequence[object],
    rng: random.Random,
) -> Mapping[str, object]:
    if category in user.recurring_merchants and rng.random() < 0.35:
        return user.recurring_merchants[category]
    if not groups:
        return {"name": "fallback", "merchants": ["Generic Merchant"], "aliases": [], "transliterations": []}
    return rng.choice(list(groups))


def remember_recurring(
    user: UserProfile,
    category: str,
    merchant_group: Mapping[str, object],
    merchant_name: str,
) -> str:
    if category not in user.recurring_merchants:
        user.recurring_merchants[category] = merchant_group
    cached = user.recurring_merchants[category]
    if cached.get("merchants"):
        return str((cached.get("merchants") or [merchant_name])[0])
    return merchant_name


def pick_channel(
    category_config: Mapping[str, object],
    catalog: Mapping[str, object],
    user: UserProfile,
    rng: random.Random,
) -> str:
    allowed = [str(value) for value in category_config.get("allowedChannels") or []]
    global_weights = dict((catalog.get("modifierWeights") or {}).get("channels") or {})
    weights: Dict[str, float] = {}
    for channel in allowed:
        weights[channel] = float(global_weights.get(channel, 0.01)) * float(user.channel_weights.get(channel, 0.05))
    return weighted_choice_map(weights, rng, fallback=allowed[0] if allowed else "pos")


def pick_state(category: str, channel: str, catalog: Mapping[str, object], rng: random.Random) -> str:
    weights = dict((catalog.get("modifierWeights") or {}).get("states") or {})
    if channel in {"subscription", "autopay", "billpay"}:
        weights["recurring"] = float(weights.get("recurring", 0.05)) * 2.3
    if category == "BILLS_AND_GOVERNMENT":
        weights["recurring"] = float(weights.get("recurring", 0.05)) * 1.7
    if category == "BANKING_AND_FEES":
        weights["refund"] = float(weights.get("refund", 0.04)) * 0.4
        weights["partial_capture"] = float(weights.get("partial_capture", 0.04)) * 0.4
    if channel == "atm":
        weights["installment"] = 0.0
        weights["partial_capture"] = 0.0
    return weighted_choice_map(weights, rng, fallback="normal")


def pick_mcc_mode(catalog: Mapping[str, object], channel: str, category: str, rng: random.Random) -> str:
    weights = dict((catalog.get("modifierWeights") or {}).get("mccModes") or {})
    if channel in {"subscription", "autopay", "billpay"}:
        weights["aggregator"] = float(weights.get("aggregator", 0.05)) * 1.25
    if category == "BANKING_AND_FEES" and channel == "atm":
        weights["correct"] = float(weights.get("correct", 0.7)) * 1.2
        weights["aggregator"] = 0.0
    return weighted_choice_map(weights, rng, fallback="correct")


def pick_merchant_variant(merchant_group: Mapping[str, object], rng: random.Random) -> str:
    merchants = [str(value) for value in merchant_group.get("merchants") or []]
    aliases = [str(value) for value in merchant_group.get("aliases") or []]
    transliterations = [str(value) for value in merchant_group.get("transliterations") or []]
    variants = merchants + aliases + transliterations
    if not variants:
        return "Generic Merchant"
    return str(rng.choice(variants))


def build_description(
    category_config: Mapping[str, object],
    merchant_name: str,
    channel: str,
    rng: random.Random,
) -> str:
    channel_templates = category_config.get("channelTemplates") or {}
    templates = channel_templates.get(channel) if isinstance(channel_templates, dict) else None
    available = [str(value) for value in templates or []]
    if not available:
        available = [f"payment {merchant_name}", f"{merchant_name} purchase"]
    return normalize_spaces(str(rng.choice(available)).format(merchant=merchant_name))


def apply_noise(
    description: str,
    merchant_name: str,
    merchant_group: Mapping[str, object],
    user: UserProfile,
    catalog: Mapping[str, object],
    rng: random.Random,
) -> tuple[str, str]:
    noise_weights = dict((catalog.get("modifierWeights") or {}).get("noise") or {})
    modifiers = choose_noise_modifiers(noise_weights, rng)
    providers = [str(item) for item in catalog.get("paymentProviders") or []]
    acquirers = [str(item) for item in catalog.get("acquirers") or []]
    aggregators = [str(item) for item in catalog.get("aggregators") or []]

    if "payment_provider" in modifiers and providers:
        provider = str(rng.choice(providers))
        description = rng.choice(
            [
                f"{provider} * {merchant_name}",
                f"{provider}/{merchant_name}",
                f"{provider} {description}",
            ]
        )
    if "acquirer_prefix" in modifiers and acquirers:
        description = f"{rng.choice(acquirers)} {description}"
    if "city_terminal" in modifiers:
        description = f"{description} {user.city} TERM{rng.randint(100000, 999999)}"
    if "transliterated_merchant" in modifiers:
        transliterations = [str(value) for value in merchant_group.get("transliterations") or []]
        if transliterations:
            merchant_name = str(rng.choice(transliterations))
    if "stale_alias" in modifiers:
        aliases = [str(value) for value in merchant_group.get("aliases") or []]
        if aliases:
            merchant_name = str(rng.choice(aliases))
    if "mixed_keyboard" in modifiers:
        description = apply_mixed_keyboard(description, rng)
    if "truncated_tokens" in modifiers:
        description = truncate_tokens(description, rng)
    if "invoice_suffix" in modifiers:
        description = f"{description} INV{rng.randint(1000, 99999)}"
    if "masked_card_tail" in modifiers:
        description = f"{description} CARD*{rng.randint(1000, 9999)}"
    if "merchant_only" in modifiers:
        description = str(rng.choice(catalog.get("lowSignalDescriptors") or ["payment"]))
    if "description_only" in modifiers:
        merchant_name = ""
    if rng.random() < 0.18:
        description = description.upper()
    elif rng.random() < 0.18:
        description = description.lower()
    if rng.random() < 0.05 and aggregators:
        description = f"{rng.choice(aggregators)} {description}"
    return normalize_spaces(description), normalize_spaces(merchant_name)


def choose_noise_modifiers(weights: Mapping[str, object], rng: random.Random) -> List[str]:
    active = []
    modifier_names = [name for name, weight in weights.items() if float(weight) > 0]
    modifier_weights = [float(weights[name]) for name in modifier_names]
    count = rng.choices([0, 1, 2, 3], weights=[0.24, 0.42, 0.24, 0.10], k=1)[0]
    available = list(zip(modifier_names, modifier_weights))
    while available and len(active) < count:
        names = [item[0] for item in available]
        values = [item[1] for item in available]
        choice = rng.choices(names, weights=values, k=1)[0]
        active.append(choice)
        available = [item for item in available if item[0] != choice]
    return active


def apply_state(description: str, merchant_name: str, state: str, rng: random.Random) -> tuple[str, str]:
    if state == "refund":
        return (f"refund {description}", merchant_name)
    if state == "reversal":
        return (f"reversal {description}", merchant_name)
    if state == "installment":
        return (f"{description} INSTALLMENT {rng.randint(2, 12)}/{rng.randint(3, 12)}", merchant_name)
    if state == "partial_capture":
        return (f"preauth {description} partial capture", merchant_name)
    if state == "recurring":
        return (f"{description} recurring", merchant_name)
    return description, merchant_name


def build_mcc_code(
    intended_category: str,
    category_config: Mapping[str, object],
    all_mcc_codes: Mapping[str, Sequence[str]],
    mcc_mode: str,
    merchant_name: str,
    description: str,
    catalog: Mapping[str, object],
    rng: random.Random,
) -> str:
    own_codes = [str(code) for code in category_config.get("mccCodes") or []]
    if mcc_mode == "missing":
        return ""
    if mcc_mode == "wrong":
        other_codes = [
            code
            for category, codes in all_mcc_codes.items()
            if category != intended_category
            for code in codes
        ]
        return str(rng.choice(other_codes)) if other_codes else ""
    if mcc_mode == "aggregator":
        provider_codes = [str(value) for value in catalog.get("unknownMccCodes") or []]
        if provider_codes and rng.random() < 0.4:
            return str(rng.choice(provider_codes))
        return ""
    if not own_codes:
        return ""
    return str(rng.choice(own_codes))


def build_undefined_transaction(
    catalog: Mapping[str, object],
    user: UserProfile,
    rng: random.Random,
) -> tuple[str, str, str]:
    undefined = catalog.get("undefined") or {}
    descriptions = [str(value) for value in undefined.get("descriptions") or ["transaction"]]
    merchants = [str(value) for value in undefined.get("merchants") or ["Unknown Merchant"]]
    description = str(rng.choice(descriptions))
    merchant_name = str(rng.choice(merchants))
    if rng.random() < 0.55:
        description = f"{description} {user.city}"
    if rng.random() < 0.35:
        description = f"{description} REF{rng.randint(1000, 999999)}"
    mcc_code = str(rng.choice(catalog.get("unknownMccCodes") or ["0000"])) if rng.random() < 0.35 else ""
    return normalize_spaces(description), normalize_spaces(merchant_name), mcc_code


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
    multiplier: float,
    channel: str,
    state: str,
    rng: random.Random,
) -> float:
    min_value = global_min
    max_value = global_max
    amount_bands = [item for item in category_config.get("amountBands") or [] if isinstance(item, dict)]
    if amount_bands:
        weights = [float(item.get("weight", 0.0)) for item in amount_bands]
        selected_band = rng.choices(amount_bands, weights=weights, k=1)[0]
        min_value = max(global_min, Decimal(str(selected_band.get("min", global_min))))
        max_value = min(global_max, Decimal(str(selected_band.get("max", global_max))))
    if max_value < min_value:
        min_value, max_value = global_min, global_max

    min_cents = int((min_value * Decimal("100")).to_integral_value(rounding=ROUND_HALF_UP))
    max_cents = int((max_value * Decimal("100")).to_integral_value(rounding=ROUND_HALF_UP))
    cents = rng.randint(min_cents, max_cents)
    amount = float(Decimal(cents) / Decimal("100"))

    if channel == "ecommerce":
        amount *= 1.12
    elif channel in {"subscription", "autopay"}:
        amount *= 0.92
    elif channel == "atm":
        amount *= 1.30

    amount *= multiplier
    if state == "partial_capture":
        amount *= 0.62
    elif state == "installment":
        amount *= 0.45
    magnitude = min(float(global_max), max(float(global_min), abs(amount)))
    amount = -magnitude if state in {"refund", "reversal"} else magnitude
    if state in {"refund", "reversal"}:
        return round(amount, 2)
    return round(amount, 2)


def random_timestamp(
    rng: random.Random,
    category: str,
    channel: str,
    state: str,
) -> str:
    start = datetime(2026, 1, 1, tzinfo=timezone.utc)
    candidate = choose_day(rng, category, channel, state)
    hour = choose_hour(rng, category, channel, state)
    minute = rng.randint(0, 59)
    second = rng.randint(0, 59)
    result = datetime(
        2026,
        candidate.month,
        candidate.day,
        hour,
        minute,
        second,
        tzinfo=timezone.utc,
    )
    if result < start:
        result = start
    return result.isoformat().replace("+00:00", "Z")


def choose_day(rng: random.Random, category: str, channel: str, state: str) -> datetime:
    for _ in range(12):
        month = rng.randint(1, 12)
        day = rng.randint(1, 28)
        candidate = datetime(2026, month, day, tzinfo=timezone.utc)
        weekday = candidate.weekday()
        is_weekend = weekday >= 5
        if state == "recurring" or channel == "autopay":
            day = rng.choice([1, 3, 5, 10, 15, 20, 25, 28])
            return datetime(2026, month, day, tzinfo=timezone.utc)
        if category == "TRANSPORT" and not is_weekend:
            return candidate
        if category in {"ENTERTAINMENT", "RETAIL_SHOPPING"} and (is_weekend or rng.random() < 0.45):
            return candidate
        if category == "HEALTH" and not is_weekend:
            return candidate
        if category == "BILLS_AND_GOVERNMENT" and day in {1, 5, 10, 15, 20, 25, 28}:
            return candidate
        if category in {"FOOD_AND_DRINKS", "GROCERIES", "BANKING_AND_FEES"}:
            return candidate
    return datetime(2026, rng.randint(1, 12), rng.randint(1, 28), tzinfo=timezone.utc)


def choose_hour(rng: random.Random, category: str, channel: str, state: str) -> int:
    if state == "recurring" or channel in {"autopay", "billpay"}:
        return rng.randint(6, 11)
    if category == "TRANSPORT":
        return rng.choice(list(range(6, 10)) + list(range(17, 22)))
    if category == "FOOD_AND_DRINKS":
        return rng.choice(list(range(8, 11)) + list(range(12, 15)) + list(range(18, 23)))
    if category == "GROCERIES":
        return rng.choice(list(range(10, 15)) + list(range(17, 23)))
    if category == "RETAIL_SHOPPING":
        return rng.choice(list(range(11, 23)))
    if category == "ENTERTAINMENT":
        return rng.choice(list(range(17, 24)))
    if category == "HEALTH":
        return rng.choice(list(range(8, 19)))
    if category == "BANKING_AND_FEES":
        return rng.choice(list(range(9, 20)))
    if category == "BILLS_AND_GOVERNMENT":
        return rng.choice(list(range(7, 16)))
    return rng.randint(0, 23)


def weighted_choice_map(weights: Mapping[str, object], rng: random.Random, fallback: str) -> str:
    items = [(str(name), float(weight)) for name, weight in weights.items() if float(weight) > 0]
    if not items:
        return fallback
    names = [item[0] for item in items]
    values = [item[1] for item in items]
    return str(rng.choices(names, weights=values, k=1)[0])


def apply_mixed_keyboard(value: str, rng: random.Random) -> str:
    if not value:
        return value
    tokens = value.split()
    index = rng.randrange(len(tokens))
    tokens[index] = tokens[index].translate(MIXED_KEYBOARD_MAP)
    return " ".join(tokens)


def truncate_tokens(value: str, rng: random.Random) -> str:
    tokens = value.split()
    if not tokens:
        return value
    updated: List[str] = []
    for token in tokens:
        if len(token) > 6 and rng.random() < 0.45:
            updated.append(token[: rng.randint(4, 6)])
        else:
            updated.append(token)
    return " ".join(updated)


def stable_seed(value: str) -> int:
    digest = hashlib.sha256(value.encode("utf-8")).hexdigest()
    return int(digest[:8], 16)


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
