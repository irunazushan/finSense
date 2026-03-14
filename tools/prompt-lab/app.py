from __future__ import annotations

from dataclasses import asdict
from datetime import datetime, timezone
import os
from pathlib import Path
from typing import Any, Dict, Optional, Tuple
from uuid import uuid4

import streamlit as st

from deepseek_client import DeepSeekClient, render_prompt_template
from models import LLMSettings, PromptContext, RunResult, ToolDataset
from tool_runtime import load_dataset_from_path, load_dataset_from_text


PROJECT_ROOT = Path(__file__).resolve().parents[2]
TOOL_ROOT = Path(__file__).resolve().parent
DEFAULT_DATASET_PATH = TOOL_ROOT / "data" / "sample-tools.json"
DEFAULT_SYSTEM_PROMPT_PATH = (
    PROJECT_ROOT / "financial-coach-agent" / "src" / "main" / "resources" / "prompts" / "coach-system-v1.txt"
)
DEFAULT_USER_PROMPT_PATH = (
    PROJECT_ROOT / "financial-coach-agent" / "src" / "main" / "resources" / "prompts" / "coach-user-v1.txt"
)


def main() -> None:
    st.set_page_config(page_title="Prompt Lab (DeepSeek)", layout="wide")
    st.title("Prompt Lab (DeepSeek)")
    st.caption("Test prompts with DeepSeek and simulate coach-style tool calling.")

    _init_session_state()
    _init_prompt_defaults()

    settings, enable_tools, dataset, dataset_error = render_sidebar()
    render_prompt_editor()

    if enable_tools:
        if dataset_error:
            st.error(dataset_error)
        elif dataset is not None:
            render_dataset_summary(dataset)

    current_result: Optional[RunResult] = None
    if st.button("Run prompt", type="primary", use_container_width=True):
        current_result = run_prompt(settings=settings, enable_tools=enable_tools, dataset=dataset)
        add_run_to_history(settings=settings, result=current_result)

    render_latest_result(current_result)
    render_history()


def render_sidebar() -> Tuple[LLMSettings, bool, Optional[ToolDataset], Optional[str]]:
    with st.sidebar:
        st.header("Runtime")
        api_key = st.text_input(
            "DeepSeek API key",
            type="password",
            value=os.getenv("DEEPSEEK_API_KEY", ""),
            help="Defaults from DEEPSEEK_API_KEY.",
        ).strip()
        base_url = st.text_input(
            "Base URL",
            value=os.getenv("LLM_API_BASE_URL", "https://api.deepseek.com"),
        ).strip()
        model = st.text_input(
            "Model",
            value=os.getenv("LLM_MODEL", "deepseek-chat"),
        ).strip()
        enable_tools = st.toggle("Enable tool calling", value=True)

        st.header("LLM Settings")
        temperature = float(st.slider("Temperature", min_value=0.0, max_value=2.0, value=0.1, step=0.1))
        top_p = float(st.slider("Top-p", min_value=0.0, max_value=1.0, value=1.0, step=0.05))
        max_tokens = int(st.number_input("Max tokens", min_value=1, max_value=8192, value=500, step=10))
        timeout_seconds = int(st.number_input("Timeout (seconds)", min_value=1, max_value=300, value=60, step=1))
        max_tool_iterations = int(
            st.number_input("Max tool iterations", min_value=1, max_value=20, value=8, step=1)
        )

        dataset, dataset_error = load_dataset_controls(enable_tools=enable_tools)

    settings = LLMSettings(
        api_key=api_key,
        base_url=base_url or "https://api.deepseek.com",
        model=model or "deepseek-chat",
        temperature=temperature,
        top_p=top_p,
        max_tokens=max_tokens,
        timeout_seconds=timeout_seconds,
        max_tool_iterations=max_tool_iterations,
    )
    return settings, enable_tools, dataset, dataset_error


def load_dataset_controls(enable_tools: bool) -> Tuple[Optional[ToolDataset], Optional[str]]:
    if not enable_tools:
        return None, None

    st.header("Tool Dataset")
    source = st.radio(
        "Dataset source",
        options=["Sample file", "Custom path", "Upload JSON"],
        index=0,
        key="dataset_source_mode",
    )

    try:
        if source == "Sample file":
            return load_dataset_from_path(DEFAULT_DATASET_PATH), None

        if source == "Custom path":
            custom_path = st.text_input("JSON file path", value=str(DEFAULT_DATASET_PATH), key="dataset_custom_path")
            if not custom_path.strip():
                return None, "Dataset path is required."
            return load_dataset_from_path(Path(custom_path.strip())), None

        uploaded = st.file_uploader("Upload dataset JSON", type=["json"], key="dataset_upload")
        if uploaded is None:
            return None, "Upload a JSON dataset file."
        raw = uploaded.getvalue().decode("utf-8")
        return load_dataset_from_text(raw), None
    except Exception as exc:  # noqa: BLE001
        return None, str(exc)


def render_prompt_editor() -> None:
    st.subheader("Prompt Inputs")
    left, right = st.columns(2)

    with left:
        st.text_input("requestId", key="request_id")
        st.text_input("userId", key="user_id")
        st.number_input("periodDays", min_value=1, max_value=365, step=1, key="period_days")

    with right:
        st.text_area("userMessage", key="user_message", height=100)

    st.subheader("Prompt Templates")
    st.text_area("System prompt", key="system_prompt", height=220)
    st.text_area("User prompt template", key="user_prompt_template", height=220)

    rendered = render_current_user_prompt()
    with st.expander("Rendered user prompt", expanded=False):
        st.code(rendered)


def run_prompt(settings: LLMSettings, enable_tools: bool, dataset: Optional[ToolDataset]) -> RunResult:
    if not settings.api_key:
        return RunResult(
            status="error",
            mode="with_tools" if enable_tools else "without_tools",
            final_text="",
            used_model=settings.model,
            latency_ms=0,
            total_tokens=0,
            prompt_tokens=0,
            completion_tokens=0,
            steps=[],
            error="DeepSeek API key is empty. Set DEEPSEEK_API_KEY or input it in sidebar.",
        )

    if enable_tools and dataset is None:
        return RunResult(
            status="error",
            mode="with_tools",
            final_text="",
            used_model=settings.model,
            latency_ms=0,
            total_tokens=0,
            prompt_tokens=0,
            completion_tokens=0,
            steps=[],
            error="Tool calling mode requires a valid dataset.",
        )

    context = PromptContext(
        request_id=str(st.session_state["request_id"]).strip(),
        user_id=str(st.session_state["user_id"]).strip(),
        period_days=int(st.session_state["period_days"]),
        user_message=str(st.session_state["user_message"]),
    )

    user_prompt = render_prompt_template(
        st.session_state["user_prompt_template"],
        {
            "requestId": context.request_id,
            "userId": context.user_id,
            "periodDays": context.period_days,
            "userMessage": context.user_message,
        },
    )

    client = DeepSeekClient(settings=settings)
    return client.run(
        system_prompt=st.session_state["system_prompt"],
        user_prompt=user_prompt,
        enable_tool_calling=enable_tools,
        dataset=dataset,
    )


def render_dataset_summary(dataset: ToolDataset) -> None:
    st.subheader("Tool Dataset Summary")
    cols = st.columns(4)
    cols[0].metric("spendingByCategory", len(dataset.spending_by_category))
    cols[1].metric("monthlyDelta", len(dataset.monthly_delta))
    cols[2].metric("topMerchants", len(dataset.top_merchants))
    cols[3].metric("spikes", len(dataset.spikes))


def render_latest_result(current_result: Optional[RunResult]) -> None:
    if current_result is None:
        return
    st.subheader("Latest Run")
    render_result(current_result)


def render_history() -> None:
    st.subheader("Run History (Session)")
    history = st.session_state.get("run_history", [])
    if not history:
        st.info("No runs yet.")
        return

    for idx, item in enumerate(history):
        label = (
            f"{item['timestamp']} | {item['status']} | {item['mode']} | "
            f"model={item['model']} | latency={item['latency_ms']}ms | tokens={item['total_tokens']}"
        )
        with st.expander(label, expanded=(idx == 0)):
            render_result(_dict_to_result(item["result"]))


def render_result(result: RunResult) -> None:
    metrics = st.columns(5)
    metrics[0].metric("Status", result.status)
    metrics[1].metric("Mode", result.mode)
    metrics[2].metric("Latency (ms)", result.latency_ms)
    metrics[3].metric("Total tokens", result.total_tokens)
    metrics[4].metric("Model", result.used_model)

    if result.error:
        st.error(result.error)

    st.markdown("**Final assistant output**")
    st.code(result.final_text or "<empty>")

    st.markdown("**Trace**")
    for step in result.steps:
        with st.expander(f"Step {step.index}", expanded=False):
            st.markdown("Assistant content:")
            st.code(step.assistant_content or "<empty>")

            if step.tool_calls:
                st.markdown("Tool calls:")
                for tool_call in step.tool_calls:
                    st.write(
                        {
                            "id": tool_call.id,
                            "name": tool_call.name,
                            "arguments": tool_call.arguments,
                            "error": tool_call.error,
                        }
                    )
                    st.json({"result": tool_call.result} if tool_call.error is None else {"error": tool_call.error})

            left, right = st.columns(2)
            with left:
                st.markdown("Request payload")
                st.json(step.request_payload)
            with right:
                st.markdown("Response payload")
                st.json(step.response_payload)


def add_run_to_history(settings: LLMSettings, result: RunResult) -> None:
    record = {
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "status": result.status,
        "mode": result.mode,
        "model": settings.model,
        "latency_ms": result.latency_ms,
        "total_tokens": result.total_tokens,
        "result": asdict(result),
    }
    history = st.session_state.get("run_history", [])
    history.insert(0, record)
    st.session_state["run_history"] = history[:30]


def render_current_user_prompt() -> str:
    return render_prompt_template(
        st.session_state["user_prompt_template"],
        {
            "requestId": st.session_state["request_id"],
            "userId": st.session_state["user_id"],
            "periodDays": st.session_state["period_days"],
            "userMessage": st.session_state["user_message"],
        },
    )


def _init_session_state() -> None:
    if "run_history" not in st.session_state:
        st.session_state["run_history"] = []
    if "request_id" not in st.session_state:
        st.session_state["request_id"] = str(uuid4())
    if "user_id" not in st.session_state:
        st.session_state["user_id"] = "11111111-1111-1111-1111-111111111111"
    if "period_days" not in st.session_state:
        st.session_state["period_days"] = 30
    if "user_message" not in st.session_state:
        st.session_state["user_message"] = "Дай рекомендации по снижению расходов."


def _init_prompt_defaults() -> None:
    if "system_prompt" not in st.session_state:
        st.session_state["system_prompt"] = _read_text_or_fallback(
            DEFAULT_SYSTEM_PROMPT_PATH,
            "You are a financial coach. Return JSON only.",
        )
    if "user_prompt_template" not in st.session_state:
        st.session_state["user_prompt_template"] = _read_text_or_fallback(
            DEFAULT_USER_PROMPT_PATH,
            "requestId: {{requestId}}\nuserId: {{userId}}\nperiodDays: {{periodDays}}\nuserMessage: {{userMessage}}",
        )


def _read_text_or_fallback(path: Path, fallback: str) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except Exception:  # noqa: BLE001
        return fallback


def _dict_to_result(item: Dict[str, Any]) -> RunResult:
    return RunResult(
        status=item.get("status", "unknown"),
        mode=item.get("mode", "unknown"),
        final_text=item.get("final_text", ""),
        used_model=item.get("used_model", "unknown"),
        latency_ms=int(item.get("latency_ms", 0)),
        total_tokens=int(item.get("total_tokens", 0)),
        prompt_tokens=int(item.get("prompt_tokens", 0)),
        completion_tokens=int(item.get("completion_tokens", 0)),
        steps=_deserialize_steps(item.get("steps") or []),
        error=item.get("error"),
    )


def _deserialize_steps(raw_steps: list[Dict[str, Any]]) -> list[Any]:
    from models import StepTrace, ToolCallTrace

    steps = []
    for raw in raw_steps:
        tool_calls = [
            ToolCallTrace(
                id=str(tc.get("id") or ""),
                name=str(tc.get("name") or ""),
                arguments=tc.get("arguments") or {},
                result=tc.get("result"),
                error=tc.get("error"),
            )
            for tc in (raw.get("tool_calls") or [])
        ]
        steps.append(
            StepTrace(
                index=int(raw.get("index") or 0),
                request_payload=raw.get("request_payload") or {},
                response_payload=raw.get("response_payload") or {},
                assistant_content=str(raw.get("assistant_content") or ""),
                tool_calls=tool_calls,
            )
        )
    return steps


if __name__ == "__main__":
    main()
