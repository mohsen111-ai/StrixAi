import pytest

from strix.agent import Agent, Event
from strix.providers import ProviderError
from strix.router import AllModelsFailed, Router
from strix.tools import Toolbox

from .conftest import call, reply, script


def make(cfg, tmp_path, fn, mode="yolo", ask=None):
    cfg.settings.mode = mode
    events = []
    router = Router(cfg, chat_fn=fn, sleep=lambda s: _noop())
    agent = Agent(cfg, router, Toolbox(tmp_path), events.append, ask)
    return agent, router, events


async def _noop():
    return None


async def test_full_loop_writes_file_and_runs_tests(cfg, tmp_path):
    fn = script(
        reply("creating", call("write_file", path="m.py", content="def f():\n    return 2\n")),
        reply("", call("bash", command="python -c 'import m; assert m.f()==2'")),
        reply("Done: added m.f and verified it."),
    )
    agent, _, events = make(cfg, tmp_path, fn)
    final = await agent.run("add a function f that returns 2")
    assert final.startswith("Done")
    assert (tmp_path / "m.py").exists()
    kinds = [e.kind for e in events]
    assert kinds.count("tool_result") == 2 and kinds[-1] == "done"
    assert [m["role"] for m in agent.messages].count("tool") == 2


async def test_router_falls_back_on_rate_limit(cfg, tmp_path):
    fn = script(ProviderError("429", 429, True), reply("hello from b"))
    agent, router, _ = make(cfg, tmp_path, fn)
    assert await agent.run("say hi") == "hello from b"
    assert [m for m, _ in fn.seen] == ["a", "b"]
    assert str(router.current("coder")) == "p:b"


async def test_all_models_failed_is_reported_not_raised(cfg, tmp_path):
    fn = script(*[ProviderError("500", 500, True)] * 6)
    agent, _, events = make(cfg, tmp_path, fn)
    await agent.run("anything")
    assert any(e.kind == "error" for e in events)


async def test_invalid_tool_args_are_returned_to_model(cfg, tmp_path):
    from strix.providers import ToolCall
    fn = script(reply("", ToolCall("1", "read_file", "{{broken")), reply("recovered"))
    agent, _, _ = make(cfg, tmp_path, fn)
    assert await agent.run("x") == "recovered"
    tool_msgs = [m for m in agent.messages if m["role"] == "tool"]
    assert tool_msgs and tool_msgs[0]["content"].startswith("ERROR")


async def test_switches_model_after_repeated_bad_calls(cfg, tmp_path):
    cfg.settings.bad_calls_before_switch = 2
    bad = lambda: reply("", call("edit_file", path="nope.txt", old="a", new="b"))
    fn = script(bad(), reply("", call("edit_file", path="nope.txt", old="a", new="c")), reply("giving up"))
    agent, _, _ = make(cfg, tmp_path, fn)
    await agent.run("x")
    assert [m for m, _ in fn.seen] == ["a", "a", "b"]


async def test_permission_denied_blocks_write(cfg, tmp_path):
    async def deny(name, args, preview):
        assert "+hello" in preview
        return "no"
    fn = script(reply("", call("write_file", path="x.txt", content="hello\n")), reply("ok, not written"))
    agent, _, _ = make(cfg, tmp_path, fn, mode="ask", ask=deny)
    await agent.run("write x")
    assert not (tmp_path / "x.txt").exists()


async def test_auto_edit_allows_writes_but_asks_for_exec(cfg, tmp_path):
    asked = []

    async def ask(name, args, preview):
        asked.append(name)
        return "yes"
    fn = script(reply("", call("write_file", path="y.txt", content="1\n"), call("bash", command="echo hi"), call("bash", command="pytest --version")),
                reply("done"))
    agent, _, _ = make(cfg, tmp_path, fn, mode="auto-edit", ask=ask)
    await agent.run("go")
    assert (tmp_path / "y.txt").exists() and asked == ["bash"]  # pytest is on the safe list


async def test_always_remembers_for_session(cfg, tmp_path):
    n = []

    async def ask(name, args, preview):
        n.append(1)
        return "always"
    fn = script(reply("", call("bash", command="touch a")), reply("", call("bash", command="touch b")), reply("ok"))
    agent, _, _ = make(cfg, tmp_path, fn, mode="auto-edit", ask=ask)
    await agent.run("go")
    assert len(n) == 1 and (tmp_path / "b").exists()


async def test_planner_runs_for_long_tasks(cfg, tmp_path):
    cfg.settings.planning = True
    fn = script(reply("1. read\n2. edit"), reply("finished"))
    agent, _, events = make(cfg, tmp_path, fn)
    agent.planning = True
    await agent.run("please implement a rate limiter for the api layer and add tests")
    assert [m for m, _ in fn.seen] == ["plan", "a"]
    assert any(e.kind == "plan" for e in events)
    assert "<plan from planner>" in [m for m in agent.messages if m["role"] == "user"][0]["content"]


async def test_compaction_trims_and_keeps_pairing(cfg, tmp_path):
    cfg.settings.context_tokens = 2000
    fn = script(reply("summary text"), reply("done"))
    agent, _, events = make(cfg, tmp_path, fn)
    for i in range(10):
        agent.messages.append({"role": "assistant", "content": None, "tool_calls": [{"id": f"t{i}", "type": "function", "function": {"name": "bash", "arguments": "{}"}}]})
        agent.messages.append({"role": "tool", "tool_call_id": f"t{i}", "content": "x" * 3000})
    await agent.run("continue")
    assert any(e.kind == "compact" for e in events)
    roles = [m["role"] for m in agent.messages]
    assert roles[0] == "system" and roles[1] == "user" and "Summary" in agent.messages[1]["content"]
    for i, m in enumerate(agent.messages):  # every tool message must follow an assistant tool_call
        if m["role"] == "tool":
            assert any(c["id"] == m["tool_call_id"] for a in agent.messages[:i] if a.get("tool_calls") for c in a["tool_calls"])
