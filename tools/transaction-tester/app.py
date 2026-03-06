from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation
import os
from pathlib import Path
from typing import Dict, List, Sequence, Tuple

import streamlit as st

from core_client import poll_generated_transactions
from generator import generate_transactions, load_category_templates
from models import GenerationResult, GeneratorConfig
from publisher import KafkaTransactionPublisher


PROJECT_ROOT = Path(__file__).resolve().parents[2]
RULES_PATH = PROJECT_ROOT / "classifier-service" / "classifier-rules.yaml"
ENUM_PATH = (
    PROJECT_ROOT
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

RUN_MODE_PRESETS = {
    "Host": ("localhost:29092", "http://localhost:8080"),
    "Docker": ("kafka:9092", "http://core:8080"),
    "Custom": (
        os.getenv("TESTER_BOOTSTRAP_SERVERS", "localhost:29092"),
        os.getenv("TESTER_CORE_BASE_URL", "http://localhost:8080"),
    ),
}


@st.cache_data(show_spinner=False)
def cached_templates(rules_path: str, enum_path: str) -> Tuple[Dict[str, object], List[str]]:
    return load_category_templates(Path(rules_path), Path(enum_path))


def main() -> None:
    st.set_page_config(page_title="Raw Transactions Tester", layout="wide")
    st.title("Raw Transactions Tester")
    st.caption("Generate and publish synthetic events to Kafka topic `raw-transactions`.")

    try:
        templates, allowed_categories = cached_templates(str(RULES_PATH), str(ENUM_PATH))
    except Exception as exc:  # noqa: BLE001 - displayed directly to operator
        st.error(f"Failed to load category templates: {exc}")
        return

    (
        run_mode,
        bootstrap_servers,
        core_base_url,
        topic,
        users_count,
        tx_per_user,
        amount_min,
        amount_max,
        start_datetime,
        end_datetime,
        random_fill_enabled,
        ambiguous_ratio,
        send_interval_ms,
        seed,
        verify_after_send,
        verify_timeout_seconds,
        verify_poll_interval_seconds,
    ) = render_runtime_controls()

    st.subheader("Category Distribution")
    category_counts = render_category_controls(allowed_categories)
    render_distribution_status(users_count * tx_per_user, category_counts, random_fill_enabled)

    preview_col, dry_col, run_col = st.columns(3)
    preview_clicked = preview_col.button("Preview sample", use_container_width=True)
    dry_run_clicked = dry_col.button("Run (Dry)", use_container_width=True)
    run_clicked = run_col.button("Run", type="primary", use_container_width=True)

    if not (preview_clicked or dry_run_clicked or run_clicked):
        return

    try:
        config = GeneratorConfig(
            bootstrap_servers=bootstrap_servers,
            core_base_url=core_base_url,
            users_count=users_count,
            tx_per_user=tx_per_user,
            amount_min=parse_decimal(amount_min),
            amount_max=parse_decimal(amount_max),
            start_datetime=start_datetime,
            end_datetime=end_datetime,
            category_counts=category_counts,
            random_fill_enabled=random_fill_enabled,
            ambiguous_ratio=ambiguous_ratio,
            send_interval_ms=send_interval_ms,
            seed=seed,
            verify_after_send=verify_after_send,
            topic=topic,
        )
        generation_result = generate_transactions(config, templates, allowed_categories)
    except Exception as exc:  # noqa: BLE001 - validation and runtime errors should be visible
        st.error(str(exc))
        return

    render_generation_summary(generation_result)
    if preview_clicked or dry_run_clicked:
        if dry_run_clicked:
            st.success("Dry run completed. No events were sent to Kafka.")
        return

    publish_result = run_publish(generation_result, config)
    if publish_result is None:
        return

    if verify_after_send:
        run_verification(
            generation_result=generation_result,
            core_base_url=config.core_base_url,
            timeout_seconds=verify_timeout_seconds,
            poll_interval_seconds=verify_poll_interval_seconds,
        )


def render_runtime_controls() -> Tuple[
    str,
    str,
    str,
    str,
    int,
    int,
    str,
    str,
    datetime,
    datetime,
    bool,
    float,
    int,
    int | None,
    bool,
    int,
    int,
]:
    with st.sidebar:
        st.header("Runtime")
        run_mode = st.selectbox("Run mode", options=list(RUN_MODE_PRESETS.keys()), index=0)
        preset_bootstrap, preset_core = RUN_MODE_PRESETS[run_mode]

        bootstrap_servers = st.text_input("Kafka bootstrap servers", value=preset_bootstrap)
        core_base_url = st.text_input("Core base URL", value=preset_core)
        topic = st.text_input("Kafka topic", value=os.getenv("TESTER_TOPIC", "raw-transactions"))

        st.header("Generation")
        users_count = int(st.number_input("Users count", min_value=1, max_value=10000, value=10, step=1))
        tx_per_user = int(
            st.number_input("Transactions per user", min_value=1, max_value=10000, value=100, step=1)
        )
        amount_min = st.text_input("Min amount", value="50.00")
        amount_max = st.text_input("Max amount", value="5000.00")

        default_from = date.today() - timedelta(days=30)
        default_to = date.today()
        date_range = st.date_input("Date range", value=(default_from, default_to))
        start_datetime, end_datetime = resolve_date_range(date_range)

        random_fill_enabled = st.checkbox("Fill remaining transactions randomly", value=True)
        ambiguous_enabled = st.checkbox("Inject ambiguous low-signal transactions", value=True)
        ambiguous_ratio = (
            st.slider("Ambiguous ratio", min_value=0.0, max_value=1.0, value=0.15, step=0.01)
            if ambiguous_enabled
            else 0.0
        )

        send_interval_ms = int(
            st.number_input("Send interval (ms)", min_value=0, max_value=60000, value=0, step=10)
        )

        seed_text = st.text_input("Random seed (optional)", value="")
        seed = int(seed_text) if seed_text.strip() else None

        st.header("Verification")
        verify_after_send = st.checkbox("Verify via Core API after send", value=True)
        verify_timeout_seconds = int(
            st.number_input("Verify timeout (seconds)", min_value=5, max_value=600, value=60, step=5)
        )
        verify_poll_interval_seconds = int(
            st.number_input("Verify poll interval (seconds)", min_value=1, max_value=60, value=3, step=1)
        )

    return (
        run_mode,
        bootstrap_servers,
        core_base_url,
        topic,
        users_count,
        tx_per_user,
        amount_min,
        amount_max,
        start_datetime,
        end_datetime,
        random_fill_enabled,
        ambiguous_ratio,
        send_interval_ms,
        seed,
        verify_after_send,
        verify_timeout_seconds,
        verify_poll_interval_seconds,
    )


def render_category_controls(allowed_categories: Sequence[str]) -> Dict[str, int]:
    category_counts: Dict[str, int] = {}
    cols = st.columns(3)
    for idx, category in enumerate(allowed_categories):
        col = cols[idx % 3]
        with col:
            category_counts[category] = int(
                st.number_input(
                    f"{category}",
                    min_value=0,
                    max_value=1_000_000,
                    value=0,
                    step=1,
                    key=f"category_{category}",
                )
            )
    return category_counts


def render_distribution_status(total: int, category_counts: Dict[str, int], random_fill_enabled: bool) -> None:
    selected = sum(category_counts.values())
    remaining = total - selected

    metric_cols = st.columns(3)
    metric_cols[0].metric("Total transactions", total)
    metric_cols[1].metric("Selected by category", selected)
    metric_cols[2].metric("Remaining", remaining)

    if selected > total:
        st.error("Selected category counts exceed total transactions.")
    elif remaining > 0 and not random_fill_enabled:
        st.warning("Remaining transactions will not be generated unless random fill is enabled.")
    elif remaining == 0:
        st.info("Category counts fully cover total transactions.")
    else:
        st.info("Remaining transactions will be filled randomly by allowed categories.")


def render_generation_summary(result: GenerationResult) -> None:
    st.subheader("Generation Summary")
    total = len(result.transactions)
    summary_cols = st.columns(4)
    summary_cols[0].metric("Generated events", total)
    summary_cols[1].metric("Users", len(result.user_ids))
    summary_cols[2].metric("Ambiguous events", result.ambiguous_count)
    summary_cols[3].metric("Distinct categories", len(result.category_totals))

    category_rows = [
        {"category": category, "count": count}
        for category, count in sorted(result.category_totals.items(), key=lambda item: item[0])
    ]
    st.write("Category totals:")
    st.dataframe(category_rows, use_container_width=True)

    sample_rows = []
    for tx in result.transactions[:25]:
        row = dict(tx.payload)
        row["targetCategory"] = tx.category
        row["ambiguous"] = tx.is_ambiguous
        sample_rows.append(row)

    st.write("Payload sample (first 25):")
    st.dataframe(sample_rows, use_container_width=True)

    with st.expander("Generated user IDs"):
        st.code("\n".join(result.user_ids))


def run_publish(result: GenerationResult, config: GeneratorConfig):
    st.subheader("Kafka Publish")
    progress = st.progress(0.0, text="Starting publish...")
    status_placeholder = st.empty()

    try:
        publisher = KafkaTransactionPublisher(
            bootstrap_servers=config.bootstrap_servers,
            topic=config.topic,
        )
    except Exception as exc:  # noqa: BLE001
        st.error(f"Failed to create Kafka producer: {exc}")
        return None

    def update_progress(current: int, total: int) -> None:
        ratio = 0.0 if total == 0 else current / total
        progress.progress(ratio, text=f"Publishing {current}/{total}")
        status_placeholder.text(f"Sent attempts: {current}/{total}")

    try:
        publish_result = publisher.publish(
            transactions=result.transactions,
            send_interval_ms=config.send_interval_ms,
            progress_callback=update_progress,
        )
        publisher.close()
    except Exception as exc:  # noqa: BLE001
        publisher.close()
        st.error(f"Publishing failed: {exc}")
        return None

    st.success(
        f"Publish complete: {publish_result.total_sent}/{publish_result.total_attempted} sent "
        f"({publish_result.total_failed} failed) in {publish_result.duration_seconds:.2f}s"
    )
    if publish_result.errors:
        st.error("Sample errors:")
        for message in publish_result.errors:
            st.code(message)

    return publish_result


def run_verification(
    generation_result: GenerationResult,
    core_base_url: str,
    timeout_seconds: int,
    poll_interval_seconds: int,
) -> None:
    st.subheader("Core Verification")
    tx_ids = [tx.transaction_id for tx in generation_result.transactions]

    try:
        summary = poll_generated_transactions(
            core_base_url=core_base_url,
            user_ids=generation_result.user_ids,
            expected_transaction_ids=tx_ids,
            timeout_seconds=timeout_seconds,
            poll_interval_seconds=poll_interval_seconds,
        )
    except Exception as exc:  # noqa: BLE001
        st.error(f"Verification failed: {exc}")
        return

    cols = st.columns(3)
    cols[0].metric("Expected", summary.expected_count)
    cols[1].metric("Found", summary.found_count)
    cols[2].metric("Missing", summary.missing_count)

    st.write("Status counts:")
    status_rows = [{"status": key, "count": value} for key, value in sorted(summary.status_counts.items())]
    st.dataframe(status_rows, use_container_width=True)

    st.write("Category counts:")
    category_rows = [{"category": key, "count": value} for key, value in sorted(summary.category_counts.items())]
    st.dataframe(category_rows, use_container_width=True)

    if summary.missing_transaction_ids:
        with st.expander("Missing transaction IDs"):
            st.code("\n".join(summary.missing_transaction_ids))


def parse_decimal(raw: str) -> Decimal:
    try:
        value = Decimal(raw)
    except (InvalidOperation, TypeError) as exc:
        raise ValueError(f"Invalid decimal value: {raw}") from exc
    return value


def resolve_date_range(raw_value) -> Tuple[datetime, datetime]:
    if isinstance(raw_value, tuple) and len(raw_value) == 2:
        start_date, end_date = raw_value
    else:
        start_date = raw_value
        end_date = raw_value

    if not isinstance(start_date, date) or not isinstance(end_date, date):
        raise ValueError("Date range must contain valid dates")

    start_datetime = datetime.combine(start_date, time.min, tzinfo=timezone.utc)
    end_datetime = datetime.combine(end_date, time.max, tzinfo=timezone.utc)
    return start_datetime, end_datetime


if __name__ == "__main__":
    main()

