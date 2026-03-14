from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass(frozen=True)
class LLMSettings:
    api_key: str
    base_url: str
    model: str
    temperature: float
    top_p: float
    max_tokens: int
    timeout_seconds: int
    max_tool_iterations: int = 8


@dataclass(frozen=True)
class PromptContext:
    request_id: str
    user_id: str
    period_days: int
    user_message: str


@dataclass(frozen=True)
class ToolDataset:
    spending_by_category: List[Dict[str, Any]]
    monthly_delta: List[Dict[str, Any]]
    top_merchants: List[Dict[str, Any]]
    spikes: List[Dict[str, Any]]


@dataclass(frozen=True)
class ToolCallTrace:
    id: str
    name: str
    arguments: Dict[str, Any]
    result: Optional[Any] = None
    error: Optional[str] = None


@dataclass(frozen=True)
class StepTrace:
    index: int
    request_payload: Dict[str, Any]
    response_payload: Dict[str, Any]
    assistant_content: str
    tool_calls: List[ToolCallTrace] = field(default_factory=list)


@dataclass(frozen=True)
class RunResult:
    status: str
    mode: str
    final_text: str
    used_model: str
    latency_ms: int
    total_tokens: int
    prompt_tokens: int
    completion_tokens: int
    steps: List[StepTrace]
    error: Optional[str] = None
