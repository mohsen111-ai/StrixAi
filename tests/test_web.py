import asyncio
import json

import httpx
import pytest

from strix.config import parse_config
from strix.web import Server, WebApp

from .conftest import CFG, call, reply, script


@pytest.fixture
async def srv(tmp_path, monkeypatch):
    monkeypatch.setenv("STRIX_HOME", str(tmp_path / "home"))
    cfg = parse_config(CFG)
    cfg.settings.mode = "auto-edit"
    holder = {}

    def make(*responses):
        app = WebApp(cfg, tmp_path / "projects", chat_fn=script(*responses))
        server = Server(app, "127.0.0.1", 0)
        holder["server"] = server
        return app, server

    yield make
    if "server" in holder and holder["server"].srv:
        holder["server"].srv.close()


async def started(server):
    await server.start()
    base = f"http://127.0.0.1:{server.port}"
    return base, httpx.AsyncClient(base_url=base, headers={"x-strix-token": server.app.token}, timeout=10)


async def wait_idle(app):
    for _ in range(100):
        await asyncio.sleep(0.03)
        if not app.busy and app.task and app.task.done():
            return
    raise AssertionError("agent still busy")


async def test_auth_required_and_cookie_flow(srv):
    app, server = srv()
    base, c = await started(server)
    async with httpx.AsyncClient(base_url=base) as anon:
        assert (await anon.get("/api/state")).status_code == 401
        r = await anon.get(f"/?t={app.token}", follow_redirects=False)
        assert r.status_code == 302 and "strix_t=" in r.headers["set-cookie"]
        page = await anon.get("/", cookies={"strix_t": app.token})
        assert page.status_code == 200 and "STRIX" in page.text
        bad = await anon.get("/api/state", headers={"host": "evil.example"})
        assert bad.status_code == 403
    await c.aclose()


async def test_send_runs_agent_and_streams_events(srv):
    app, server = srv(reply("on it", call("write_file", path="index.html", content="<h1>hi</h1>\n")), reply("Built it."))
    base, c = await started(server)
    seen = []
    async with c.stream("GET", "/api/events?after=0") as stream:
        r = await c.post("/api/send", json={"text": "make a page please"})
        assert r.status_code == 200
        async for line in stream.aiter_lines():
            if line.startswith("data: "):
                ev = json.loads(line[6:])
                seen.append(ev["kind"])
                if ev["kind"] == "done":
                    break
    assert (app.root / "index.html").exists()
    assert {"user", "model", "say", "tool_result", "state", "done"} <= set(seen)
    st = (await c.get("/api/state")).json()
    assert st["busy"] is False and st["git"]["repo"] is False and st["project"] == "my-first-project"
    await c.aclose()


async def test_permission_flow_over_http(srv):
    app, server = srv(reply("", call("bash", command="echo hi > out.txt")), reply("ok"))
    base, c = await started(server)
    app.agent.mode = "auto-edit"
    await c.post("/api/send", json={"text": "run a thing"})
    pid = None
    for _ in range(100):
        await asyncio.sleep(0.03)
        pend = [e for e in app.log if e["kind"] == "permission"]
        if pend:
            pid = pend[0]["pid"]
            break
    assert pid and "echo hi" in pend[0]["preview"]
    assert (await c.post("/api/permission", json={"pid": pid, "answer": "yes"})).status_code == 200
    await wait_idle(app)
    assert (app.root / "out.txt").exists()
    await c.aclose()


async def test_stop_denies_pending_permission(srv):
    app, server = srv(reply("", call("bash", command="touch nope")))
    base, c = await started(server)
    await c.post("/api/send", json={"text": "do it"})
    for _ in range(100):
        await asyncio.sleep(0.03)
        if app.pending:
            break
    await c.post("/api/stop", json={})
    await wait_idle(app)
    assert not (app.root / "nope").exists()
    await c.aclose()


async def test_projects_files_settings(srv, tmp_path):
    app, server = srv()
    base, c = await started(server)
    assert (await c.post("/api/project", json={"name": "My Site!"})).status_code == 200
    assert app.root.name == "My-Site"
    (app.root / "src").mkdir()
    (app.root / "src" / "a.py").write_text("print(1)\n")
    listing = (await c.get("/api/files?path=src")).json()
    assert listing["items"] == [{"name": "a.py", "dir": False}]
    assert (await c.get("/api/file?path=src/a.py")).json()["text"] == "print(1)\n"
    assert (await c.get("/api/file?path=../../etc/passwd")).status_code == 400
    names = [p["name"] for p in (await c.get("/api/projects")).json()["projects"]]
    assert "My-Site" in names
    r = await c.post("/api/settings", json={"openrouter": "sk-or-test"})
    assert r.status_code == 200
    st = (await c.get("/api/state")).json()
    assert st["keys"]["openrouter"] is True and "sk-or-test" not in json.dumps(st)
    env = (tmp_path / "home" / "env").read_text()
    assert "OPENROUTER_API_KEY=sk-or-test" in env
    import os
    os.environ.pop("OPENROUTER_API_KEY", None)
    await c.aclose()


async def test_git_commands(srv):
    app, server = srv()
    base, c = await started(server)
    (app.root / "a.txt").write_text("hi\n")
    r = await c.post("/api/command", json={"cmd": "commit", "arg": "First save"})
    assert r.status_code == 200 and "First save" in r.json()["message"]
    r = await c.post("/api/command", json={"cmd": "push"})
    assert r.status_code == 400
    st = (await c.get("/api/state")).json()
    assert st["git"]["repo"] and st["git"]["files"] == 0
    await c.aclose()
