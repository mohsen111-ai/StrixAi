import pytest

from strix.config import parse_config
from strix.providers import LLMResponse, ToolCall

CFG = {
    "providers": {"p": {"base_url": "http://x", "api_key_env": None}},
    "roles": {"coder": ["p:a", "p:b"], "planner": ["p:plan"], "fast": ["p:fast"]},
    "settings": {"mode": "yolo", "planning": False, "max_steps": 12, "context_tokens": 100000},
}


@pytest.fixture
def cfg():
    return parse_config(CFG)


def call(name, **args):
    import json
    return ToolCall("c" + name, name, json.dumps(args))


def script(*responses):
    """chat_fn that returns scripted LLMResponses (or raises them) in order and records the requested models."""
    items = list(responses)
    seen = []

    async def fn(provider, model, messages, tools=None, temperature=0.2):
        seen.append((model, len(messages)))
        r = items.pop(0)
        if isinstance(r, Exception):
            raise r
        return r

    fn.seen = seen
    return fn


def reply(text="", *calls):
    return LLMResponse(text, list(calls), 10, 5, "m")
