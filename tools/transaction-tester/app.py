from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation
import os
from pathlib import Path
from typing import Dict, List, Sequence, Tuple
from uuid import UUID

import streamlit as st

from core_client import (
    aggregate_transactions,
    apply_client_filters,
    fetch_user_transactions_all,
    fetch_user_transactions_page,
    poll_generated_transactions,
)
from generator import generate_transactions, load_category_templates
from models import (
    ClientTransactionFilters,
    GenerationResult,
    GeneratorConfig,
    ServerTransactionFilters,
    TransactionRecord,
)
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

TRANSACTION_STATUSES = [
    "NEW",
    "ML_CLASSIFYING",
    "LLM_CLASSIFYING",
    "CLASSIFIED",
    "RETRYING",
    "FAILED",
]


@st.cache_data(show_spinner=False)
def cached_templates(rules_path: str, enum_path: str) -> Tuple[Dict[str, object], List[str]]:
    return load_category_templates(Path(rules_path), Path(enum_path))


def main() -> None:
    st.set_page_config(page_title="Raw Transactions Tester", layout="wide")
    st.title("Raw Transactions Tester")
    st.caption("Generate raw transactions and browse Core transactions with rich read-only filters.")

    _init_session_state()

    try:
        templates, allowed_categories = cached_templates(str(RULES_PATH), str(ENUM_PATH))
    except Exception as exc:  # noqa: BLE001
        st.error(f"Failed to load category templates: {exc}")
        return

    _, bootstrap_servers, core_base_url, topic = render_sidebar_runtime_controls()

    generator_tab, explorer_tab = st.tabs(["Generator", "Transactions Explorer"])
    with generator_tab:
        render_generator_tab(
            templates=templates,
            allowed_categories=allowed_categories,
            bootstrap_servers=bootstrap_servers,
            core_base_url=core_base_url,
            topic=topic,
        )
    with explorer_tab:
        render_explorer_tab(
            core_base_url=core_base_url,
            allowed_categories=allowed_categories,
        )


def render_sidebar_runtime_controls() -> Tuple[str, str, str, str]:
    with st.sidebar:
        st.header("Runtime")
        run_mode = st.selectbox("Run mode", options=list(RUN_MODE_PRESETS.keys()), index=0)
        preset_bootstrap, preset_core = RUN_MODE_PRESETS[run_mode]

        bootstrap_servers = st.text_input("Kafka bootstrap servers", value=preset_bootstrap)
        core_base_url = st.text_input("Core base URL", value=preset_core)
        topic = st.text_input("Kafka topic", value=os.getenv("TESTER_TOPIC", "raw-transactions"))

    return run_mode, bootstrap_servers, core_base_url, topic


def render_generator_tab(
    templates: Dict[str, object],
    allowed_categories: Sequence[str],
    bootstrap_servers: str,
    core_base_url: str,
    topic: str,
) -> None:
    st.subheader("Event Generator")
    users_count, tx_per_user, target_user_id, amount_min, amount_max, start_datetime, end_datetime, random_fill_enabled, ambiguous_ratio, send_interval_ms, seed, verify_after_send, verify_timeout_seconds, verify_poll_interval_seconds = render_generator_controls()

    st.subheader("Category Distribution")
    category_counts = render_category_controls(allowed_categories)
    render_distribution_status(users_count * tx_per_user, category_counts, random_fill_enabled)
    if target_user_id:
        st.info("All generated transactions will be sent to the target user UUID.")

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
            target_user_id=(target_user_id or None),
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
    except Exception as exc:  # noqa: BLE001
        st.error(str(exc))
        return

    remember_generated_user_ids(generation_result.user_ids)
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


def render_generator_controls() -> Tuple[
    int,
    int,
    str,
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
    settings_col, verify_col = st.columns(2)

    with settings_col:
        users_count = int(st.number_input("Users count", min_value=1, max_value=10000, value=10, step=1))
        tx_per_user = int(st.number_input("Transactions per user", min_value=1, max_value=10000, value=100, step=1))
        target_user_id = st.text_input("Target user UUID (optional)", value="")
        amount_min = st.text_input("Min amount", value="50.00")
        amount_max = st.text_input("Max amount", value="5000.00")
        default_from = date.today() - timedelta(days=30)
        default_to = date.today()
        date_range = st.date_input("Date range", value=(default_from, default_to), key="generator_date_range")
        start_datetime, end_datetime = resolve_date_range(date_range)

        random_fill_enabled = st.checkbox("Fill remaining transactions randomly", value=True)
        ambiguous_enabled = st.checkbox("Inject ambiguous low-signal transactions", value=True)
        ambiguous_ratio = (
            st.slider("Ambiguous ratio", min_value=0.0, max_value=1.0, value=0.15, step=0.01)
            if ambiguous_enabled
            else 0.0
        )
        send_interval_ms = int(st.number_input("Send interval (ms)", min_value=0, max_value=60000, value=0, step=10))
        seed_text = st.text_input("Random seed (optional)", value="")
        seed = int(seed_text) if seed_text.strip() else None

    with verify_col:
        st.markdown("**Post-Send Verification**")
        verify_after_send = st.checkbox("Verify via Core API after send", value=True)
        verify_timeout_seconds = int(st.number_input("Verify timeout (seconds)", min_value=5, max_value=600, value=60, step=5))
        verify_poll_interval_seconds = int(st.number_input("Verify poll interval (seconds)", min_value=1, max_value=60, value=3, step=1))

    return (
        users_count,
        tx_per_user,
        target_user_id.strip(),
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


def render_explorer_tab(core_base_url: str, allowed_categories: Sequence[str]) -> None:
    st.subheader("Transactions Explorer")
    generated_users = st.session_state.get("generated_user_ids", [])

    user_col, manual_col = st.columns(2)
    with user_col:
        selected_generated_user = st.selectbox(
            "Generated users from this session",
            options=[""] + generated_users,
            index=0,
            key="explorer_generated_user",
        )
    with manual_col:
        manual_user_input = st.text_input("Manual userId (UUID)", value="", key="explorer_manual_user")

    target_user_id = (manual_user_input.strip() or selected_generated_user).strip()
    if not target_user_id:
        st.info("Select a generated user or paste a userId to browse transactions.")

    st.markdown("**Server-side filters (Core API)**")
    server_col_1, server_col_2, server_col_3 = st.columns(3)

    with server_col_1:
        category_choice = st.selectbox("Category", options=["Any"] + list(allowed_categories))
        status_choice = st.selectbox("Status", options=["Any"] + TRANSACTION_STATUSES)
    with server_col_2:
        apply_date_range = st.checkbox("Apply date range", value=False)
        default_from = date.today() - timedelta(days=30)
        default_to = date.today()
        explorer_date_range = st.date_input(
            "Transaction date range",
            value=(default_from, default_to),
            key="explorer_date_range",
        )
    with server_col_3:
        load_all_pages = st.checkbox("Load all pages", value=False)
        page = int(st.number_input("Page", min_value=0, max_value=100000, value=0, step=1))
        size = int(st.number_input("Page size", min_value=1, max_value=200, value=50, step=1))

    st.markdown("**Client-side filters (applied after fetch)**")
    client_col_1, client_col_2 = st.columns(2)
    with client_col_1:
        amount_min_raw = st.text_input("Min amount (optional)", value="")
        amount_max_raw = st.text_input("Max amount (optional)", value="")
        mcc_code = st.text_input("MCC exact (optional)", value="")
    with client_col_2:
        merchant_contains = st.text_input("Merchant contains (optional)", value="")
        description_contains = st.text_input("Description contains (optional)", value="")

    if st.button("Load transactions", type="primary", use_container_width=False):
        if not target_user_id:
            st.error("User ID is required.")
            return
        if not is_valid_uuid(target_user_id):
            st.error("Invalid userId format. Expected UUID.")
            return

        try:
            from_datetime, to_datetime = (
                resolve_date_range(explorer_date_range) if apply_date_range else (None, None)
            )
            server_filters = ServerTransactionFilters(
                category=None if category_choice == "Any" else category_choice,
                status=None if status_choice == "Any" else status_choice,
                from_datetime=from_datetime,
                to_datetime=to_datetime,
                page=page,
                size=size,
            )

            if load_all_pages:
                records = fetch_user_transactions_all(
                    core_base_url=core_base_url,
                    user_id=target_user_id,
                    filters=server_filters,
                )
            else:
                records = fetch_user_transactions_page(
                    core_base_url=core_base_url,
                    user_id=target_user_id,
                    filters=server_filters,
                )

            st.session_state["explorer_records"] = records
            st.session_state["explorer_last_user"] = target_user_id
        except Exception as exc:  # noqa: BLE001
            st.error(f"Failed to load transactions: {exc}")
            return

    records: List[TransactionRecord] = st.session_state.get("explorer_records", [])
    if not records:
        st.info("No transactions loaded yet.")
        return

    try:
        client_filters = ClientTransactionFilters(
            amount_min=parse_decimal_optional(amount_min_raw),
            amount_max=parse_decimal_optional(amount_max_raw),
            merchant_contains=merchant_contains or None,
            mcc_code=mcc_code or None,
            description_contains=description_contains or None,
        )
    except ValueError as exc:
        st.error(str(exc))
        return

    filtered_records = apply_client_filters(records, client_filters)
    status_counts, category_counts = aggregate_transactions(filtered_records)

    metrics = st.columns(3)
    metrics[0].metric("Fetched", len(records))
    metrics[1].metric("After client filters", len(filtered_records))
    metrics[2].metric("Current user", st.session_state.get("explorer_last_user", "-"))

    status_rows = [{"status": status, "count": count} for status, count in sorted(status_counts.items())]
    category_rows = [{"category": category, "count": count} for category, count in sorted(category_counts.items())]

    agg_col_1, agg_col_2 = st.columns(2)
    with agg_col_1:
        st.write("Status aggregates")
        st.dataframe(status_rows, use_container_width=True)
    with agg_col_2:
        st.write("Category aggregates")
        st.dataframe(category_rows, use_container_width=True)

    st.write("Filtered transactions")
    rows = [record.to_row() for record in filtered_records]
    st.dataframe(rows, use_container_width=True)


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

    category_rows = [{"category": category, "count": count} for category, count in sorted(result.category_totals.items())]
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
    except Exception as exc:  # noqa: BLE001
        st.error(f"Publishing failed: {exc}")
        return None
    finally:
        publisher.close()

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


def parse_decimal_optional(raw: str) -> Decimal | None:
    raw_value = (raw or "").strip()
    if not raw_value:
        return None
    return parse_decimal(raw_value)


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


def remember_generated_user_ids(user_ids: Sequence[str]) -> None:
    existing = st.session_state.get("generated_user_ids", [])
    combined = existing + list(user_ids)
    unique: List[str] = []
    seen: set[str] = set()
    for user_id in combined:
        if user_id not in seen:
            unique.append(user_id)
            seen.add(user_id)
    st.session_state["generated_user_ids"] = unique


def is_valid_uuid(value: str) -> bool:
    try:
        UUID(value)
        return True
    except Exception:  # noqa: BLE001
        return False


def _init_session_state() -> None:
    if "generated_user_ids" not in st.session_state:
        st.session_state["generated_user_ids"] = []
    if "explorer_records" not in st.session_state:
        st.session_state["explorer_records"] = []
    if "explorer_last_user" not in st.session_state:
        st.session_state["explorer_last_user"] = "-"


if __name__ == "__main__":
    main()
