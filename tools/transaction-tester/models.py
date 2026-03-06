from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal
from typing import Any, Dict, List, Optional


@dataclass(frozen=True)
class CategoryTemplate:
    category: str
    mcc_codes: List[str] = field(default_factory=list)
    keywords: List[str] = field(default_factory=list)


@dataclass(frozen=True)
class GeneratorConfig:
    bootstrap_servers: str
    core_base_url: str
    users_count: int
    tx_per_user: int
    amount_min: Decimal
    amount_max: Decimal
    start_datetime: datetime
    end_datetime: datetime
    category_counts: Dict[str, int]
    random_fill_enabled: bool
    ambiguous_ratio: float
    send_interval_ms: int
    seed: Optional[int]
    verify_after_send: bool
    topic: str = "raw-transactions"


@dataclass(frozen=True)
class GeneratedTransaction:
    transaction_id: str
    user_id: str
    category: str
    is_ambiguous: bool
    payload: Dict[str, Any]


@dataclass(frozen=True)
class GenerationResult:
    user_ids: List[str]
    transactions: List[GeneratedTransaction]
    category_totals: Dict[str, int]
    ambiguous_count: int


@dataclass(frozen=True)
class PublishResult:
    total_attempted: int
    total_sent: int
    total_failed: int
    errors: List[str]
    duration_seconds: float


@dataclass(frozen=True)
class VerificationSummary:
    expected_count: int
    found_count: int
    missing_count: int
    status_counts: Dict[str, int]
    category_counts: Dict[str, int]
    missing_transaction_ids: List[str]

