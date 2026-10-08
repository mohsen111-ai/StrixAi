"""Phone-friendly web UI: a tiny stdlib HTTP server (no extra dependencies, installs anywhere) serving a chat page
over Server-Sent Events. Same agent, router and tools as the terminal UI."""
from __future__ import annotations

import asyncio
import json
import mimetypes
import os
import re
import secrets
import uuid
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from . import __version__, repo
from .agent import Agent, Event, estimate_tokens
from .config import Config, strix_home
from .router import Router
from .tools import IGNORE_DIRS, Toolbox

UI_FILE = Path(__file__).with_name("web_ui.html")
KEY_ENV = {"openrouter": "OPENROUTER_API_KEY", "gemini": "GEMINI_API_KEY", "github": "GITHUB_TOKEN"}
MAX_LOG = 3000


def load_token() -> str:
    f = strix_home() / "web_token"
    if f.is_file() and f.read_text().strip():
        return f.read_text().strip()
    f.parent.mkdir(parents=True, exist_ok=True)
    tok = secrets.token_urlsafe(18)
    f.write_text(tok)
    os.chmod(f, 0o600)
    return tok


def save_env_key(name: str, value: str) -> None:
    f = strix_home() / "env"
    f.parent.mkdir(parents=True, exist_ok=True)
    lines = [l for l in (f.read_text().splitlines() if f.is_file() else []) if not l.startswith(name + "=")]
    if value:
        lines.append(f"{name}={value}")
        os.environ[name] = value
    else:
        os.environ.pop(name, None)
    f.write_text("\n".join(lines) + "\n")
    os.chmod(f, 0o600)


def safe_name(name: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]+", "-", name.strip()).strip("-.")[:60]


class WebApp:
    def __init__(self, cfg: Config, projects_dir: Path, chat_fn=None):
        self.cfg = cfg
        self.projects_dir = projects_dir
        self.chat_fn = chat_fn
        self.projects_dir.mkdir(parents=True, exist_ok=True)
        self.token = load_token()
        self.seq = 0
        self.log: list[dict] = []
        self.subs: set[asyncio.Queue] = set()
        self.pending: dict[str, asyncio.Future] = {}
        self.task: asyncio.Task | None = None
        self.busy = False
        self.activity = ""
        self.root: Path | None = None
        self.agent: Agent | None = None
        self.router: Router | None = None
        self.toolbox: Toolbox | None = None
        last = strix_home() / "last_project"
        name = last.read_text().strip() if last.is_file() else ""
        self.open_project(name if name and (projects_dir / name).is_dir() else "my-first-project")

    # ---- events ------------------------------------------------------
    def push(self, kind: str, **data) -> None:
        self.seq += 1
        ev = {"id": self.seq, "kind": kind, **data}
        self.log.append(ev)
        del self.log[:-MAX_LOG]
        for q in list(self.subs):
            q.put_nowait(ev)

    def push_state(self) -> None:
        self.push("state", **self.state())

    def on_agent_event(self, e: Event) -> None:
        d = dict(e.data)
        if e.kind == "tool_result":
            d["args"] = {k: (v if isinstance(v, (int, float, bool)) else str(v)[:300]) for k, v in d.get("args", {}).items()}
            d["diff"] = d.get("diff", "")[:6000]
        if e.kind == "tool_start":
            d["args"] = {k: str(v)[:120] for k, v in d.get("args", {}).items()}
            self.activity = f"{d['name']} {next(iter(d['args'].values()), '')}"[:80]
        if e.kind == "model":
            self.activity = f"{d['role']} · {d['model'].split(':', 1)[-1].split('/')[-1]}"
        if e.kind == "done":
            return  # the run wrapper announces completion
        self.push(e.kind, **d)
        self.push_state()

    async def ask(self, name: str, args: dict, preview: str) -> str:
        pid = uuid.uuid4().hex[:8]
        fut = asyncio.get_running_loop().create_future()
        self.pending[pid] = fut
        self.push("permission", pid=pid, name=name, preview=preview[:6000])
        try:
            return await fut
        finally:
            self.pending.pop(pid, None)
            self.push("permission_done", pid=pid)

    # ---- projects ----------------------------------------------------
    def open_project(self, name: str) -> None:
        name = safe_name(name) or "my-first-project"
        root = self.projects_dir / name
        root.mkdir(parents=True, exist_ok=True)
        self.root = root.resolve()
        (strix_home()).mkdir(parents=True, exist_ok=True)
        (strix_home() / "last_project").write_text(name)
        self.router = Router(self.cfg, chat_fn=self.chat_fn, on_switch=lambda role, ref, why: self.push("info", text=f"{role} switched to {ref.model.split('/')[-1]} ({why})"))
        self.toolbox = Toolbox(self.root)
        self.toolbox.on_todo = lambda items: None
        self.agent = Agent(self.cfg, self.router, self.toolbox, self.on_agent_event, self.ask)
        self.log.clear()
        self.push("project", name=name)
        self.push_state()

    def list_projects(self) -> list[dict]:
        out = []
        for p in sorted(self.projects_dir.iterdir(), key=lambda x: x.stat().st_mtime, reverse=True):
            if p.is_dir() and not p.name.startswith("."):
                out.append({"name": p.name, "git": (p / ".git").exists(), "current": p.resolve() == self.root})
        return out

    # ---- state -------------------------------------------------------
    def state(self) -> dict:
        r = self.router
        git = {"repo": False}
        if repo.is_repo(self.root):
            files, add, rem = repo.diff_stat(self.root)
            git = {"repo": True, "branch": repo.branch(self.root), "files": files, "added": add, "removed": rem,
                   "remote": repo.remote_slug(self.root)}
        return {
            "version": __version__, "project": self.root.name, "busy": self.busy, "activity": self.activity,
            "mode": self.agent.mode, "planning": self.agent.planning, "git": git,
            "todos": self.toolbox.todos,
            "roles": {role: [str(m) for m in chain] for role, chain in self.cfg.roles.items()},
            "current": {role: str(r.current(role)) for role in self.cfg.roles},
            "context_pct": min(100, estimate_tokens(self.agent.messages) * 100 // self.cfg.settings.context_tokens),
            "tokens": r.stats["prompt"] + r.stats["completion"], "calls": r.stats["calls"],
            "keys": {k: bool(os.environ.get(v)) for k, v in KEY_ENV.items()},
            "needs_key": bool(self.cfg.missing_keys()),
        }

    # ---- actions -----------------------------------------------------
    def send(self, text: str) -> None:
        if self.busy:
            raise ValueError("Strix is still working. Tap Stop first.")
        self.push("user", text=text)
        self.busy, self.activity = True, "thinking"
        self.push_state()
        self.task = asyncio.create_task(self._run(text))

    async def _run(self, text: str) -> None:
        try:
            final = await self.agent.run(text)
            self.push("done", text=final)
        except asyncio.CancelledError:
            self.push("info", text="Stopped.")
        except Exception as e:
            self.push("error", text=f"Something went wrong inside Strix: {e!r}")
        finally:
            self.busy = False
            for fut in list(self.pending.values()):
                if not fut.done():
                    fut.set_result("no")
            self.push_state()

    def stop(self) -> None:
        for fut in list(self.pending.values()):
            if not fut.done():
                fut.set_result("no")
        if self.task and not self.task.done():
            self.task.cancel()

    async def command(self, cmd: str, arg: str = "") -> str:
        try:
            if cmd == "undo":
                msg = self.toolbox.undo()
            elif cmd == "new":
                self.agent.reset(); self.toolbox.todos = []; msg = "Started a fresh conversation."
            elif cmd == "branch":
                repo.ensure_repo(self.root); repo.create_branch(self.root, arg or "strix/work"); msg = f"Now on branch {repo.branch(self.root)}."
            elif cmd == "commit":
                repo.ensure_repo(self.root)
                msg = "Saved: " + (await repo.commit(self.router, self.root, arg or None)).splitlines()[0]
            elif cmd == "push":
                msg = f"Pushed {await asyncio.to_thread(repo.push, self.root)} to GitHub."
            elif cmd == "pr":
                repo.ensure_repo(self.root)
                if repo.changes(self.root):
                    await repo.commit(self.router, self.root)
                await asyncio.to_thread(repo.push, self.root)
                title = repo.git(self.root, "log", "-1", "--pretty=%s")
                msg = "Pull request: " + await repo.open_pr(self.root, title, "Opened by Strix.")
            elif cmd == "create_repo":
                msg = "Created on GitHub: " + await repo.create_github_repo(self.root, safe_name(arg) or self.root.name)
            else:
                raise ValueError(f"unknown command {cmd}")
            self.push("info", text=msg)
            return msg
        except (repo.GitError, ValueError) as e:
            self.push("error", text=str(e))
            raise
        finally:
            self.push_state()

    # ---- files -------------------------------------------------------
    def _inside(self, rel: str) -> Path:
        p = (self.root / rel).resolve()
        if p != self.root and self.root not in p.parents:
            raise ValueError("outside the project")
        return p

    def list_files(self, rel: str) -> dict:
        d = self._inside(rel or ".")
        if not d.is_dir():
            raise ValueError("not a folder")
        items = [{"name": c.name, "dir": c.is_dir()} for c in sorted(d.iterdir(), key=lambda x: (x.is_file(), x.name.lower()))
                 if c.name not in IGNORE_DIRS]
        return {"path": str(d.relative_to(self.root)) if d != self.root else "", "items": items[:500]}

    def read_file(self, rel: str) -> dict:
        p = self._inside(rel)
        if not p.is_file():
            raise ValueError("not a file")
        data = p.read_bytes()[:200_000]
        if b"\0" in data[:2048]:
            return {"path": rel, "binary": True, "text": ""}
        return {"path": rel, "binary": False, "text": data.decode("utf-8", "replace")}


# ---------------------------------------------------------------- HTTP --
STATUS = {200: "OK", 204: "No Content", 302: "Found", 400: "Bad Request", 401: "Unauthorized", 403: "Forbidden", 404: "Not Found", 409: "Conflict", 413: "Payload Too Large", 500: "Internal Server Error"}


async def respond(writer, status: int, body: bytes = b"", ctype: str = "application/json", extra: dict | None = None) -> None:
    head = [f"HTTP/1.1 {status} {STATUS.get(status, '')}", f"Content-Type: {ctype}; charset=utf-8", f"Content-Length: {len(body)}",
            "Cache-Control: no-store", "X-Content-Type-Options: nosniff", "Referrer-Policy: no-referrer", "Connection: close"]
    head += [f"{k}: {v}" for k, v in (extra or {}).items()]
    writer.write(("\r\n".join(head) + "\r\n\r\n").encode() + body)
    await writer.drain()


def jbody(obj) -> bytes:
    return json.dumps(obj).encode()


class Server:
    def __init__(self, app: WebApp, host: str = "127.0.0.1", port: int = 8000):
        self.app, self.host, self.port = app, host, port
        self.srv: asyncio.base_events.Server | None = None

    async def start(self) -> None:
        self.srv = await asyncio.start_server(self.handle, self.host, self.port)
        self.port = self.srv.sockets[0].getsockname()[1]

    @property
    def url(self) -> str:
        return f"http://{'localhost' if self.host in ('127.0.0.1', '0.0.0.0') else self.host}:{self.port}/?t={self.app.token}"

    async def handle(self, reader, writer) -> None:
        try:
            line = await asyncio.wait_for(reader.readline(), 15)
            if not line:
                return
            method, target, _ = line.decode("latin1").split(" ", 2)
            headers: dict[str, str] = {}
            while True:
                l = await reader.readline()
                if l in (b"\r\n", b"\n", b""):
                    break
                k, _, v = l.decode("latin1").partition(":")
                headers[k.strip().lower()] = v.strip()
            n = int(headers.get("content-length") or 0)
            if n > 2_000_000:
                return await respond(writer, 413)
            body = await reader.readexactly(n) if n else b""
            await self.route(method, urlsplit(target), headers, body, writer)
        except (asyncio.IncompleteReadError, ConnectionError, asyncio.TimeoutError, ValueError):
            pass
        except Exception as e:  # keep the server alive
            try:
                await respond(writer, 500, jbody({"error": repr(e)}))
            except Exception:
                pass
        finally:
            try:
                writer.close()
            except Exception:
                pass

    def authorised(self, headers: dict, query: dict) -> bool:
        tok = self.app.token
        cookies = dict(c.strip().split("=", 1) for c in headers.get("cookie", "").split(";") if "=" in c)
        return secrets.compare_digest(cookies.get("strix_t", ""), tok) or secrets.compare_digest(query.get("t", [""])[0], tok) \
            or secrets.compare_digest(headers.get("x-strix-token", ""), tok)

    async def route(self, method: str, url, headers: dict, body: bytes, writer) -> None:
        app, path, query = self.app, url.path, parse_qs(url.query)
        host = headers.get("host", "").split(":")[0]
        if self.host in ("127.0.0.1", "localhost") and host not in ("localhost", "127.0.0.1", "[::1]"):
            return await respond(writer, 403, jbody({"error": "bad host"}))
        if not self.authorised(headers, query):
            return await respond(writer, 401, "Open the link printed by `strix web` (it contains your access token).".encode(), "text/plain")
        if path == "/" and method == "GET":
            cookie = f"strix_t={app.token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000"
            if "t" in query:  # strip the token from the address bar
                return await respond(writer, 302, b"", "text/plain", {"Location": "/", "Set-Cookie": cookie})
            return await respond(writer, 200, UI_FILE.read_bytes(), "text/html", {"Set-Cookie": cookie})
        if path == "/api/events":
            return await self.sse(headers, query, writer)
        try:
            data = json.loads(body) if body else {}
            out = await self.api(method, path, query, data)
        except ValueError as e:
            return await respond(writer, 400, jbody({"error": str(e)}))
        except repo.GitError as e:
            return await respond(writer, 400, jbody({"error": str(e)}))
        if out is None:
            return await respond(writer, 404, jbody({"error": "not found"}))
        await respond(writer, 200, jbody(out))

    async def api(self, method: str, path: str, query: dict, d: dict):
        app = self.app
        if method == "GET":
            if path == "/api/state":
                return app.state()
            if path == "/api/projects":
                return {"projects": app.list_projects()}
            if path == "/api/files":
                return app.list_files(query.get("path", [""])[0])
            if path == "/api/file":
                return app.read_file(query.get("path", [""])[0])
            return None
        if path == "/api/send":
            text = str(d.get("text", "")).strip()
            if not text:
                raise ValueError("type something first")
            app.send(text)
        elif path == "/api/stop":
            app.stop()
        elif path == "/api/permission":
            fut = app.pending.get(d.get("pid", ""))
            if fut and not fut.done():
                fut.set_result(d.get("answer") if d.get("answer") in ("yes", "always", "no") else "no")
        elif path == "/api/mode":
            if d.get("mode") not in ("ask", "auto-edit", "yolo"):
                raise ValueError("bad mode")
            app.agent.mode = d["mode"]; app.push_state()
        elif path == "/api/planning":
            app.agent.planning = bool(d.get("on")); app.push_state()
        elif path == "/api/command":
            return {"message": await app.command(str(d.get("cmd", "")), str(d.get("arg", "")))}
        elif path == "/api/project":
            if app.busy:
                raise ValueError("Stop the current run before switching projects.")
            if d.get("clone"):
                root = await asyncio.to_thread(repo.clone, str(d["clone"]).strip(), app.projects_dir)
                app.open_project(root.name)
            else:
                app.open_project(str(d.get("name", "")))
        elif path == "/api/settings":
            for k, env in KEY_ENV.items():
                if k in d:
                    save_env_key(env, str(d[k]).strip())
            app.push_state()
        else:
            return None
        return {"ok": True}

    async def sse(self, headers: dict, query: dict, writer) -> None:
        app = self.app
        after = int(headers.get("last-event-id") or query.get("after", ["0"])[0] or 0)
        writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nX-Accel-Buffering: no\r\nConnection: keep-alive\r\n\r\n")
        q: asyncio.Queue = asyncio.Queue()
        backlog = [e for e in app.log if e["id"] > after]
        app.subs.add(q)
        try:
            writer.write(b"retry: 1500\n\n")
            for ev in backlog:
                writer.write(f"id: {ev['id']}\ndata: {json.dumps(ev)}\n\n".encode())
            await writer.drain()
            sent = backlog[-1]["id"] if backlog else after
            while True:
                try:
                    ev = await asyncio.wait_for(q.get(), 15)
                except asyncio.TimeoutError:
                    writer.write(b": ping\n\n")
                    await writer.drain()
                    continue
                if ev["id"] <= sent:
                    continue
                sent = ev["id"]
                writer.write(f"id: {ev['id']}\ndata: {json.dumps(ev)}\n\n".encode())
                await writer.drain()
        except (ConnectionError, asyncio.CancelledError):
            pass
        finally:
            app.subs.discard(q)


async def serve(cfg: Config, projects_dir: Path, host: str, port: int, open_browser: bool = True) -> None:
    app = WebApp(cfg, projects_dir)
    server = Server(app, host, port)
    await server.start()
    print(f"\n  STRIX web {__version__}\n  Projects folder: {projects_dir}\n\n  Open this link in Chrome:\n  {server.url}\n\n  (Ctrl+C to stop)\n", flush=True)
    if open_browser and os.environ.get("TERMUX_VERSION"):
        os.system(f"termux-open-url '{server.url}' >/dev/null 2>&1 &")
    async with server.srv:
        await server.srv.serve_forever()
