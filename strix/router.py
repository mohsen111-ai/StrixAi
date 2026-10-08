"""Role router: each role is a fallback chain of models. Rate limits and errors move to the next model."""
from __future__ import annotations

import asyncio
import time
from typing import Awaitable, Callable

from . import providers
from .config import Config, ModelRef
from .providers import LLMResponse, ProviderError

ChatFn = Callable[..., Awaitable[LLMResponse]]


class AllModelsFailed(ProviderError):
    pass


class Router:
    def __init__(self, cfg: Config, chat_fn: ChatFn | None = None, sleep=asyncio.sleep, on_switch=None):
        self.cfg = cfg
        self.chat_fn = chat_fn or providers.chat
        self.sleep = sleep
        self.on_switch = on_switch  # callable(role, ModelRef, reason)
        self.cooldown: dict[str, float] = {}
        self.last: dict[str, ModelRef] = {}
        self.stats = {"prompt": 0, "completion": 0, "calls": 0}

    def current(self, role: str) -> ModelRef:
        return self.last.get(role) or self.cfg.chain(role)[0]

    async def complete(self, role: str, messages: list[dict], tools: list[dict] | None = None,
                       skip: set[str] | frozenset = frozenset()) -> LLMResponse:
        chain = [m for m in self.cfg.chain(role) if str(m) not in skip] or list(self.cfg.chain(role))
        now = time.monotonic()
        ready = [m for m in chain if self.cooldown.get(str(m), 0) <= now]
        order = ready + [m for m in chain if m not in ready]
        errors: list[str] = []
        for idx, ref in enumerate(order):
            provider = self.cfg.provider_for(ref)
            has_next = idx < len(order) - 1
            attempts = 1 if has_next else 3
            for attempt in range(attempts):
                try:
                    resp = await self.chat_fn(provider, ref.model, messages, tools, self.cfg.settings.temperature)
                except ProviderError as e:
                    errors.append(f"{ref}: {e}")
                    if e.status == 429:
                        self.cooldown[str(ref)] = time.monotonic() + (e.retry_after or 45)
                    elif e.retryable:
                        self.cooldown[str(ref)] = time.monotonic() + 15
                    if e.retryable and not has_next and attempt < attempts - 1:
                        await self.sleep(min(e.retry_after or 2 * (attempt + 1), 10))
                        continue
                    if has_next and self.on_switch:
                        self.on_switch(role, order[idx + 1], str(e)[:90])
                    break
                else:
                    if self.last.get(role) != ref and self.on_switch and role in self.last:
                        self.on_switch(role, ref, "recovered")
                    self.last[role] = ref
                    self.stats["prompt"] += resp.prompt_tokens
                    self.stats["completion"] += resp.completion_tokens
                    self.stats["calls"] += 1
                    return resp
        raise AllModelsFailed(f"every model for role '{role}' failed:\n  " + "\n  ".join(errors[-6:]))
