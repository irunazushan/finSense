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
    target_user_id: Optional[str]
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


@dataclass(frozen=True)
class TransactionRecord:
    transaction_id: str
    user_id: str
    account_id: str
    amount: Decimal
    description: Optional[str]
    merchant_name: Optional[str]
    mcc_code: Optional[str]
    transaction_date: str
    status: str
    category: Optional[str]
    classifier_source: Optional[str]
    classifier_confidence: Optional[float]
    classified_at: Optional[str]

    @staticmethod
    def from_api_dict(item: Dict[str, Any]) -> "TransactionRecord":
        return TransactionRecord(
            transaction_id=str(item.get("transactionId") or ""),
            user_id=str(item.get("userId") or ""),
            account_id=str(item.get("accountId") or ""),
            amount=Decimal(str(item.get("amount") or "0")),
            description=item.get("description"),
            merchant_name=item.get("merchantName"),
            mcc_code=item.get("mccCode"),
            transaction_date=str(item.get("transactionDate") or ""),
            status=str(item.get("status") or "UNKNOWN"),
            category=item.get("category"),
            classifier_source=item.get("classifierSource"),
            classifier_confidence=(
                float(item["classifierConfidence"])
                if item.get("classifierConfidence") is not None
                else None
            ),
            classified_at=item.get("classifiedAt"),
        )

    def to_row(self) -> Dict[str, Any]:
        return {
            "transactionId": self.transaction_id,
            "userId": self.user_id,
            "accountId": self.account_id,
            "amount": float(self.amount),
            "description": self.description,
            "merchantName": self.merchant_name,
            "mccCode": self.mcc_code,
            "transactionDate": self.transaction_date,
            "status": self.status,
            "category": self.category,
            "classifierSource": self.classifier_source,
            "classifierConfidence": self.classifier_confidence,
            "classifiedAt": self.classified_at,
        }


@dataclass(frozen=True)
class ServerTransactionFilters:
    category: Optional[str] = None
    status: Optional[str] = None
    from_datetime: Optional[datetime] = None
    to_datetime: Optional[datetime] = None
    page: int = 0
    size: int = 50


@dataclass(frozen=True)
class ClientTransactionFilters:
    amount_min: Optional[Decimal] = None
    amount_max: Optional[Decimal] = None
    merchant_contains: Optional[str] = None
    mcc_code: Optional[str] = None
    description_contains: Optional[str] = None
