"""One OpenAI-compatible chat client. OpenRouter, Gemini, Ollama, vLLM and LM Studio all speak this."""
from __future__ import annotations

import json
import re
import uuid
from dataclasses import dataclass, field

import httpx

from .config import Provider


class ProviderError(Exception):
    def __init__(self, message: str, status: int | None = None, retryable: bool = False, retry_after: float | None = None):
        super().__init__(message)
        self.status = status
        self.retryable = retryable
        self.retry_after = retry_after


@dataclass
class ToolCall:
    id: str
    name: str
    arguments: str  # raw JSON string as sent by the model


@dataclass
class LLMResponse:
    content: str
    tool_calls: list[ToolCall] = field(default_factory=list)
    prompt_tokens: int = 0
    completion_tokens: int = 0
    model: str = ""


_TEXT_CALL = re.compile(r"<tool_call>\s*(\{.*?\})\s*</tool_call>", re.S)


def _text_tool_calls(content: str) -> list[ToolCall]:
    """Some open models print tool calls as <tool_call>{...}</tool_call> text instead of using the API field."""
    calls = []
    for raw in _TEXT_CALL.findall(content or ""):
        try:
            obj = json.loads(raw)
            args = obj.get("arguments", obj.get("parameters", {}))
            calls.append(ToolCall("call_" + uuid.uuid4().hex[:8], obj["name"], args if isinstance(args, str) else json.dumps(args)))
        except (ValueError, KeyError):
            continue
    return calls


async def chat(provider: Provider, model: str, messages: list[dict], tools: list[dict] | None = None,
               temperature: float = 0.2, timeout: float = 180.0) -> LLMResponse:
    headers = {"Content-Type": "application/json", **provider.headers}
    if provider.api_key:
        headers["Authorization"] = f"Bearer {provider.api_key}"
    if "openrouter" in provider.base_url:
        headers.setdefault("X-Title", "Strix")
        headers.setdefault("HTTP-Referer", "https://github.com/mohsen111-ai/strixai")
    body: dict = {"model": model, "messages": messages, "temperature": temperature}
    if tools:
        body["tools"] = tools
        body["tool_choice"] = "auto"
    url = provider.base_url.rstrip("/") + "/chat/completions"
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            r = await client.post(url, json=body, headers=headers)
    except httpx.HTTPError as e:
        raise ProviderError(f"network error: {e.__class__.__name__}: {e}", retryable=True) from e

    if r.status_code >= 400:
        retry_after = None
        try:
            retry_after = float(r.headers.get("retry-after", ""))
        except ValueError:
            pass
        retryable = r.status_code in (408, 409, 425, 429) or r.status_code >= 500
        raise ProviderError(f"{r.status_code}: {r.text[:300]}", r.status_code, retryable, retry_after)
    try:
        data = r.json()
    except ValueError as e:
        raise ProviderError("provider returned non-JSON body", retryable=True) from e
    if not data.get("choices"):
        err = data.get("error") or {}
        raise ProviderError(f"empty response: {err.get('message', str(data)[:200])}", retryable=True)

    msg = data["choices"][0].get("message") or {}
    content = msg.get("content") or ""
    if isinstance(content, list):  # some providers return content parts
        content = "".join(p.get("text", "") for p in content if isinstance(p, dict))
    calls = [
        ToolCall(tc.get("id") or "call_" + uuid.uuid4().hex[:8], tc["function"]["name"], tc["function"].get("arguments") or "{}")
        for tc in (msg.get("tool_calls") or [])
        if tc.get("function", {}).get("name")
    ]
    if not calls:
        calls = _text_tool_calls(content)
        if calls:
            content = _TEXT_CALL.sub("", content).strip()
    if not content.strip() and not calls:
        raise ProviderError("model returned an empty message", retryable=True)
    usage = data.get("usage") or {}
    return LLMResponse(content, calls, usage.get("prompt_tokens", 0), usage.get("completion_tokens", 0), data.get("model", model))
