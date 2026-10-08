import json

import httpx
import pytest

from strix import providers, repo
from strix.config import ModelRef, parse_config
from strix.router import Router

from .conftest import CFG


def test_model_spec_keeps_free_suffix():
    r = ModelRef.parse("openrouter:qwen/qwen3-coder:free")
    assert (r.provider, r.model) == ("openrouter", "qwen/qwen3-coder:free")


def test_config_requires_coder():
    with pytest.raises(ValueError):
        parse_config({"providers": {}, "roles": {}})


def test_default_config_parses():
    from strix.config import load_config
    cfg = load_config()
    assert cfg.chain("coder") and cfg.chain("planner") and cfg.source


async def test_provider_parses_tool_calls_and_text_calls(monkeypatch):
    cfg = parse_config(CFG)
    payloads = [
        {"choices": [{"message": {"content": None, "tool_calls": [{"id": "1", "function": {"name": "bash", "arguments": "{\"command\": \"ls\"}"}}]}}], "usage": {"prompt_tokens": 7, "completion_tokens": 3}},
        {"choices": [{"message": {"content": "ok <tool_call>{\"name\": \"glob\", \"arguments\": {\"pattern\": \"*.py\"}}</tool_call>"}}]},
        {"error": {"message": "upstream down"}},
    ]
    real = httpx.AsyncClient

    def handler(request):
        return httpx.Response(200, json=payloads.pop(0))

    monkeypatch.setattr(providers.httpx, "AsyncClient", lambda **kw: real(transport=httpx.MockTransport(handler), **kw))
    p = cfg.providers["p"]
    r1 = await providers.chat(p, "m", [{"role": "user", "content": "hi"}], [])
    assert r1.tool_calls[0].name == "bash" and r1.prompt_tokens == 7
    r2 = await providers.chat(p, "m", [])
    assert r2.tool_calls[0].name == "glob" and json.loads(r2.tool_calls[0].arguments) == {"pattern": "*.py"} and r2.content == "ok"
    with pytest.raises(providers.ProviderError) as e:
        await providers.chat(p, "m", [])
    assert e.value.retryable


def test_git_flow(tmp_path):
    repo.ensure_repo(tmp_path)
    (tmp_path / "a.txt").write_text("hi\n")
    assert repo.changes(tmp_path)
    import asyncio
    msg = asyncio.run(repo.commit(Router(parse_config(CFG)), tmp_path, "Add a"))
    assert msg == "Add a" and not repo.changes(tmp_path)
    repo.create_branch(tmp_path, "strix/my feature!")
    assert repo.branch(tmp_path) == "strix/my-feature"
    (tmp_path / "a.txt").write_text("hi\nmore\n")
    assert repo.diff_stat(tmp_path)[:3] == (1, 1, 0)


def test_token_never_appears_in_error(tmp_path, monkeypatch):
    monkeypatch.setenv("GITHUB_TOKEN", "ghp_supersecret")
    repo.ensure_repo(tmp_path)
    with pytest.raises(repo.GitError) as e:
        repo.git(tmp_path, "push", "-u", "origin", "main", auth=True)
    assert "ghp_supersecret" not in str(e.value)
