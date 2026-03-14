from __future__ import annotations

from pathlib import Path
import sys

PROMPT_LAB_DIR = Path(__file__).resolve().parents[1]
if str(PROMPT_LAB_DIR) not in sys.path:
    sys.path.insert(0, str(PROMPT_LAB_DIR))

from deepseek_client import DeepSeekClient, render_prompt_template  # noqa: E402
from models import LLMSettings  # noqa: E402
from tool_runtime import parse_dataset  # noqa: E402


def make_settings(max_tool_iterations: int = 8) -> LLMSettings:
    return LLMSettings(
        api_key="test-key",
        base_url="https://api.deepseek.com",
        model="deepseek-chat",
        temperature=0.1,
        top_p=1.0,
        max_tokens=500,
        timeout_seconds=30,
        max_tool_iterations=max_tool_iterations,
    )


def sample_dataset():
    return parse_dataset(
        {
            "tools": {
                "spendingByCategory": [{"category": "A", "totalAmount": 10, "transactionCount": 1}],
                "monthlyDelta": [{"category": "A", "deltaAmount": 1}],
                "topMerchants": [
                    {"merchantName": "M1", "totalAmount": 100, "transactionCount": 2},
                    {"merchantName": "M2", "totalAmount": 50, "transactionCount": 1},
                ],
                "spikes": [{"category": "A", "date": "2026-01-01", "spikeAmount": 30, "baselineAmount": 10}],
            }
        }
    )


def test_render_prompt_template_substitutes_context_fields() -> None:
    rendered = render_prompt_template(
        "requestId={{requestId}}, userId={{userId}}, periodDays={{periodDays}}, msg={{userMessage}}",
        {
            "requestId": "r1",
            "userId": "u1",
            "periodDays": 30,
            "userMessage": "hello",
        },
    )
    assert rendered == "requestId=r1, userId=u1, periodDays=30, msg=hello"


def test_run_without_tools_single_turn_success() -> None:
    def transport(payload):
        assert "tools" not in payload
        return {
            "model": "deepseek-chat",
            "choices": [{"message": {"role": "assistant", "content": "final answer"}}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
        }

    client = DeepSeekClient(settings=make_settings(), transport=transport)
    result = client.run("sys", "usr", enable_tool_calling=False)

    assert result.status == "success"
    assert result.mode == "without_tools"
    assert result.final_text == "final answer"
    assert result.total_tokens == 15
    assert len(result.steps) == 1


def test_run_with_tools_executes_one_tool_then_returns_final() -> None:
    calls = {"count": 0}

    def transport(payload):
        calls["count"] += 1
        if calls["count"] == 1:
            assert "tools" in payload
            return {
                "model": "deepseek-chat",
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": "",
                            "tool_calls": [
                                {
                                    "id": "call_1",
                                    "type": "function",
                                    "function": {
                                        "name": "getTopMerchants",
                                        "arguments": "{\"userId\":\"u1\",\"periodDays\":30,\"limit\":1}",
                                    },
                                }
                            ],
                        }
                    }
                ],
                "usage": {"prompt_tokens": 12, "completion_tokens": 6, "total_tokens": 18},
            }
        assert payload["messages"][-1]["role"] == "tool"
        return {
            "model": "deepseek-chat",
            "choices": [{"message": {"role": "assistant", "content": "done after tools"}}],
            "usage": {"prompt_tokens": 8, "completion_tokens": 4, "total_tokens": 12},
        }

    client = DeepSeekClient(settings=make_settings(), transport=transport)
    result = client.run("sys", "usr", enable_tool_calling=True, dataset=sample_dataset())

    assert result.status == "success"
    assert result.mode == "with_tools"
    assert result.final_text == "done after tools"
    assert result.total_tokens == 30
    assert len(result.steps) == 2
    assert len(result.steps[0].tool_calls) == 1
    assert result.steps[0].tool_calls[0].name == "getTopMerchants"
    assert isinstance(result.steps[0].tool_calls[0].result, list)
    assert len(result.steps[0].tool_calls[0].result) == 1


def test_run_with_tools_handles_multi_tool_chain() -> None:
    calls = {"count": 0}

    def transport(_payload):
        calls["count"] += 1
        if calls["count"] == 1:
            return {
                "model": "deepseek-chat",
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": "",
                            "tool_calls": [
                                {
                                    "id": "call_1",
                                    "type": "function",
                                    "function": {
                                        "name": "getSpendingByCategory",
                                        "arguments": "{\"userId\":\"u1\",\"periodDays\":30}",
                                    },
                                }
                            ],
                        }
                    }
                ],
                "usage": {"prompt_tokens": 2, "completion_tokens": 1, "total_tokens": 3},
            }
        if calls["count"] == 2:
            return {
                "model": "deepseek-chat",
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": "",
                            "tool_calls": [
                                {
                                    "id": "call_2",
                                    "type": "function",
                                    "function": {
                                        "name": "detectSpikes",
                                        "arguments": "{\"userId\":\"u1\",\"periodDays\":30}",
                                    },
                                }
                            ],
                        }
                    }
                ],
                "usage": {"prompt_tokens": 2, "completion_tokens": 1, "total_tokens": 3},
            }
        return {
            "model": "deepseek-chat",
            "choices": [{"message": {"role": "assistant", "content": "final"}}],
            "usage": {"prompt_tokens": 2, "completion_tokens": 1, "total_tokens": 3},
        }

    client = DeepSeekClient(settings=make_settings(), transport=transport)
    result = client.run("sys", "usr", enable_tool_calling=True, dataset=sample_dataset())

    assert result.status == "success"
    assert len(result.steps) == 3
    assert result.steps[0].tool_calls[0].name == "getSpendingByCategory"
    assert result.steps[1].tool_calls[0].name == "detectSpikes"
    assert result.final_text == "final"


def test_run_with_tools_stops_on_max_iterations() -> None:
    def transport(_payload):
        return {
            "model": "deepseek-chat",
            "choices": [
                {
                    "message": {
                        "role": "assistant",
                        "content": "",
                        "tool_calls": [
                            {
                                "id": "loop_call",
                                "type": "function",
                                "function": {
                                    "name": "getSpendingByCategory",
                                    "arguments": "{\"userId\":\"u1\",\"periodDays\":30}",
                                },
                            }
                        ],
                    }
                }
            ],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        }

    client = DeepSeekClient(settings=make_settings(max_tool_iterations=2), transport=transport)
    result = client.run("sys", "usr", enable_tool_calling=True, dataset=sample_dataset())

    assert result.status == "error"
    assert "Reached max tool iterations" in (result.error or "")


def test_run_propagates_api_errors_into_result_error() -> None:
    def transport(_payload):
        raise RuntimeError("network down")

    client = DeepSeekClient(settings=make_settings(), transport=transport)
    result = client.run("sys", "usr", enable_tool_calling=False)

    assert result.status == "error"
    assert "network down" in (result.error or "")
