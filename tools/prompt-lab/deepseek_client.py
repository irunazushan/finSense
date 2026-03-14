from __future__ import annotations

import copy
import json
import time
from dataclasses import asdict
from typing import Any, Callable, Dict, List

import requests

from models import LLMSettings, RunResult, StepTrace, ToolCallTrace, ToolDataset
from tool_runtime import OPENAI_TOOL_DEFS, execute_tool


TransportFunc = Callable[[Dict[str, Any]], Dict[str, Any]]


class DeepSeekClient:
    def __init__(self, settings: LLMSettings, transport: TransportFunc | None = None):
        self.settings = settings
        self._transport = transport

    def run(
        self,
        system_prompt: str,
        user_prompt: str,
        enable_tool_calling: bool,
        dataset: ToolDataset | None = None,
    ) -> RunResult:
        started = time.perf_counter()
        steps: List[StepTrace] = []
        usage_totals = {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0}

        messages: List[Dict[str, Any]] = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ]

        try:
            if not enable_tool_calling:
                request_payload = self._build_payload(messages=messages, with_tools=False)
                response = self._call_api(request_payload)
                usage_totals = _accumulate_usage(usage_totals, response.get("usage"))
                assistant_message = _extract_assistant_message(response)
                steps.append(
                    StepTrace(
                        index=1,
                        request_payload=copy.deepcopy(request_payload),
                        response_payload=copy.deepcopy(response),
                        assistant_content=_message_text(assistant_message),
                        tool_calls=[],
                    )
                )
                return _build_result(
                    status="success",
                    mode="without_tools",
                    response=response,
                    steps=steps,
                    usage_totals=usage_totals,
                    started=started,
                )

            if dataset is None:
                raise ValueError("Tool calling is enabled but dataset is missing")

            final_response: Dict[str, Any] | None = None
            for idx in range(1, self.settings.max_tool_iterations + 1):
                request_payload = self._build_payload(messages=messages, with_tools=True)
                response = self._call_api(request_payload)
                usage_totals = _accumulate_usage(usage_totals, response.get("usage"))

                assistant_message = _extract_assistant_message(response)
                tool_calls = assistant_message.get("tool_calls") or []
                tool_call_traces: List[ToolCallTrace] = []
                assistant_content = _message_text(assistant_message)

                if not tool_calls:
                    steps.append(
                        StepTrace(
                            index=idx,
                            request_payload=request_payload,
                            response_payload=response,
                            assistant_content=assistant_content,
                            tool_calls=[],
                        )
                    )
                    final_response = response
                    break

                messages.append(
                    {
                        "role": "assistant",
                        "content": assistant_message.get("content"),
                        "tool_calls": tool_calls,
                    }
                )

                for call in tool_calls:
                    tool_call_id = str(call.get("id") or "")
                    function_data = call.get("function") or {}
                    tool_name = str(function_data.get("name") or "")
                    arguments = _parse_tool_arguments(function_data.get("arguments"))

                    try:
                        tool_result = execute_tool(tool_name, arguments, dataset)
                        tool_call_traces.append(
                            ToolCallTrace(
                                id=tool_call_id,
                                name=tool_name,
                                arguments=arguments,
                                result=tool_result,
                            )
                        )
                        messages.append(
                            {
                                "role": "tool",
                                "tool_call_id": tool_call_id,
                                "name": tool_name,
                                "content": json.dumps(tool_result, ensure_ascii=False),
                            }
                        )
                    except Exception as exc:  # noqa: BLE001
                        tool_call_traces.append(
                            ToolCallTrace(
                                id=tool_call_id,
                                name=tool_name,
                                arguments=arguments,
                                error=str(exc),
                            )
                        )
                        messages.append(
                            {
                                "role": "tool",
                                "tool_call_id": tool_call_id,
                                "name": tool_name,
                                "content": json.dumps({"error": str(exc)}, ensure_ascii=False),
                            }
                        )

                steps.append(
                    StepTrace(
                        index=idx,
                        request_payload=copy.deepcopy(request_payload),
                        response_payload=copy.deepcopy(response),
                        assistant_content=assistant_content,
                        tool_calls=tool_call_traces,
                    )
                )

            if final_response is None:
                raise RuntimeError(
                    f"Reached max tool iterations ({self.settings.max_tool_iterations}) without final response"
                )

            return _build_result(
                status="success",
                mode="with_tools",
                response=final_response,
                steps=steps,
                usage_totals=usage_totals,
                started=started,
            )
        except Exception as exc:  # noqa: BLE001
            return RunResult(
                status="error",
                mode="with_tools" if enable_tool_calling else "without_tools",
                final_text="",
                used_model=self.settings.model,
                latency_ms=int((time.perf_counter() - started) * 1000),
                total_tokens=usage_totals["total_tokens"],
                prompt_tokens=usage_totals["prompt_tokens"],
                completion_tokens=usage_totals["completion_tokens"],
                steps=steps,
                error=str(exc),
            )

    def _build_payload(self, messages: List[Dict[str, Any]], with_tools: bool) -> Dict[str, Any]:
        payload: Dict[str, Any] = {
            "model": self.settings.model,
            "messages": messages,
            "temperature": self.settings.temperature,
            "top_p": self.settings.top_p,
            "max_tokens": self.settings.max_tokens,
        }
        if with_tools:
            payload["tools"] = OPENAI_TOOL_DEFS
            payload["tool_choice"] = "auto"
        return payload

    def _call_api(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        if self._transport is not None:
            return self._transport(copy.deepcopy(payload))

        url = self.settings.base_url.rstrip("/") + "/chat/completions"
        headers = {
            "Authorization": f"Bearer {self.settings.api_key}",
            "Content-Type": "application/json",
        }

        response = requests.post(
            url,
            headers=headers,
            json=payload,
            timeout=self.settings.timeout_seconds,
        )
        try:
            response.raise_for_status()
        except requests.HTTPError as exc:
            text = response.text[:500] if response.text else "<empty>"
            raise RuntimeError(f"DeepSeek API HTTP error: {text}") from exc

        try:
            data = response.json()
        except ValueError as exc:
            snippet = response.text[:500] if response.text else "<empty>"
            raise RuntimeError(f"DeepSeek API returned non-JSON response: {snippet}") from exc

        if not isinstance(data, dict):
            raise RuntimeError("DeepSeek API returned invalid JSON payload")
        return data


def render_prompt_template(template: str, values: Dict[str, Any]) -> str:
    rendered = template
    for key, value in values.items():
        rendered = rendered.replace(f"{{{{{key}}}}}", str(value))
    return rendered


def result_to_dict(result: RunResult) -> Dict[str, Any]:
    return asdict(result)


def _extract_assistant_message(response: Dict[str, Any]) -> Dict[str, Any]:
    choices = response.get("choices")
    if not isinstance(choices, list) or not choices:
        raise RuntimeError("DeepSeek API response does not contain choices")
    first = choices[0]
    if not isinstance(first, dict):
        raise RuntimeError("DeepSeek API response choice is invalid")
    message = first.get("message")
    if not isinstance(message, dict):
        raise RuntimeError("DeepSeek API response choice has no message")
    return message


def _message_text(message: Dict[str, Any]) -> str:
    content = message.get("content")
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts: List[str] = []
        for item in content:
            if isinstance(item, dict):
                text = item.get("text")
                if isinstance(text, str):
                    parts.append(text)
        return "\n".join(parts).strip()
    return ""


def _parse_tool_arguments(raw: Any) -> Dict[str, Any]:
    if raw is None:
        return {}
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str):
        text = raw.strip()
        if not text:
            return {}
        try:
            parsed = json.loads(text)
        except Exception as exc:  # noqa: BLE001
            raise RuntimeError(f"Tool arguments are not valid JSON: {text}") from exc
        if not isinstance(parsed, dict):
            raise RuntimeError("Tool arguments must decode to an object")
        return parsed
    raise RuntimeError("Tool arguments have unsupported type")


def _accumulate_usage(base: Dict[str, int], usage: Any) -> Dict[str, int]:
    if not isinstance(usage, dict):
        return base
    return {
        "prompt_tokens": base["prompt_tokens"] + int(usage.get("prompt_tokens") or 0),
        "completion_tokens": base["completion_tokens"] + int(usage.get("completion_tokens") or 0),
        "total_tokens": base["total_tokens"] + int(usage.get("total_tokens") or 0),
    }


def _build_result(
    status: str,
    mode: str,
    response: Dict[str, Any],
    steps: List[StepTrace],
    usage_totals: Dict[str, int],
    started: float,
) -> RunResult:
    assistant_message = _extract_assistant_message(response)
    used_model = str(response.get("model") or "")
    if not used_model:
        used_model = "unknown"
    return RunResult(
        status=status,
        mode=mode,
        final_text=_message_text(assistant_message),
        used_model=used_model,
        latency_ms=int((time.perf_counter() - started) * 1000),
        total_tokens=usage_totals["total_tokens"],
        prompt_tokens=usage_totals["prompt_tokens"],
        completion_tokens=usage_totals["completion_tokens"],
        steps=steps,
    )
