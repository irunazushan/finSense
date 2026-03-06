from __future__ import annotations

import json
import time
from typing import Callable, Iterable, Optional

from kafka import KafkaProducer

from models import GeneratedTransaction, PublishResult


ProgressCallback = Callable[[int, int], None]


class KafkaTransactionPublisher:
    def __init__(self, bootstrap_servers: str, topic: str) -> None:
        server_list = [server.strip() for server in bootstrap_servers.split(",") if server.strip()]
        if not server_list:
            raise ValueError("bootstrap_servers cannot be empty")

        self.topic = topic
        self.producer = KafkaProducer(
            bootstrap_servers=server_list,
            key_serializer=lambda value: value.encode("utf-8"),
            value_serializer=lambda value: json.dumps(value, ensure_ascii=False).encode("utf-8"),
            acks="all",
            retries=3,
            linger_ms=5,
        )

    def publish(
        self,
        transactions: Iterable[GeneratedTransaction],
        send_interval_ms: int = 0,
        progress_callback: Optional[ProgressCallback] = None,
    ) -> PublishResult:
        tx_list = list(transactions)
        total = len(tx_list)
        sent = 0
        failed = 0
        errors: list[str] = []
        started_at = time.monotonic()

        for index, transaction in enumerate(tx_list, start=1):
            try:
                future = self.producer.send(
                    topic=self.topic,
                    key=transaction.transaction_id,
                    value=transaction.payload,
                )
                future.get(timeout=30)
                sent += 1
            except Exception as exc:  # noqa: BLE001 - external broker errors are runtime failures
                failed += 1
                if len(errors) < 20:
                    errors.append(str(exc))

            if progress_callback is not None:
                progress_callback(index, total)

            if send_interval_ms > 0:
                time.sleep(send_interval_ms / 1000)

        self.producer.flush()
        duration_seconds = time.monotonic() - started_at
        return PublishResult(
            total_attempted=total,
            total_sent=sent,
            total_failed=failed,
            errors=errors,
            duration_seconds=duration_seconds,
        )

    def close(self) -> None:
        self.producer.close()

