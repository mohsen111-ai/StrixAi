"""Command line entry: `strix` (TUI), `strix run`, `strix models`, `strix doctor`, `strix init`."""
from __future__ import annotations

import argparse
import asyncio
import os
import sys
from pathlib import Path

import httpx
from rich.console import Console
from rich.markup import escape

from . import __version__, repo
from .agent import Agent, Event
from .config import DEFAULT_PATH, ROLES, load_config, load_dotenv
from .router import Router
from .tools import Toolbox

console = Console(soft_wrap=True)
STRIX_MD = """# Project notes for Strix

Describe how to run tests, code style, and anything the agent should know.

- Test command:
- Lint command:
- Conventions:
"""


def resolve_workspace(args) -> Path:
    if getattr(args, "repo", None):
        spec = args.repo
        if Path(spec).is_dir():
            return Path(spec).resolve()
        return repo.clone(spec, Path(args.dir or "workspace").resolve())
    return Path(args.dir or ".").resolve()


def cmd_init(args) -> int:
    target = Path("strix.yaml")
    if target.exists():
        console.print("[yellow]strix.yaml already exists[/]")
    else:
        target.write_text(DEFAULT_PATH.read_text())
        console.print("[green]wrote strix.yaml[/]")
    if not Path("STRIX.md").exists():
        Path("STRIX.md").write_text(STRIX_MD)
        console.print("[green]wrote STRIX.md[/]")
    env = Path(".env")
    if not env.exists():
        env.write_text("OPENROUTER_API_KEY=\n# GEMINI_API_KEY=\n# GITHUB_TOKEN=\n")
        console.print("[green]wrote .env (add your keys)[/]")
    return 0


def cmd_models(args) -> int:
    r = httpx.get("https://openrouter.ai/api/v1/models", timeout=30)
    rows = []
    needles = [n.lower() for n in args.search]
    for m in r.json()["data"]:
        free = m["id"].endswith(":free")
        if args.free and not free:
            continue
        if needles and not any(n in m["id"].lower() for n in needles):
            continue
        if "tools" not in (m.get("supported_parameters") or []):
            continue
        price = float(m.get("pricing", {}).get("prompt") or 0) * 1_000_000
        rows.append((m["id"], m.get("context_length", 0), "free" if free else f"${price:.2f}/M"))
    rows.sort(key=lambda x: x[0])
    for mid, ctx, price in rows[: args.limit]:
        console.print(f"openrouter:{escape(mid):<58} {ctx // 1000:>5}k  {price}")
    console.print(f"[dim]{len(rows)} tool-capable models. Put a spec like openrouter:<id> into strix.yaml[/]")
    return 0


def cmd_doctor(args) -> int:
    cfg = load_config(args.config)
    console.print(f"strix {__version__}  config: {cfg.source}")
    missing = cfg.missing_keys()
    for name in sorted({m.provider for c in cfg.roles.values() for m in c}):
        p = cfg.providers.get(name)
        if not p:
            console.print(f"[red]✗[/] provider {name} not defined"); continue
        state = "[green]✓ key set[/]" if (not p.api_key_env or p.api_key) else f"[red]✗ set {p.api_key_env}[/]"
        console.print(f"  {name:<11} {p.base_url}  {state}")
    for role in ROLES:
        if cfg.roles.get(role):
            console.print(f"  {role:<8} " + " → ".join(str(m) for m in cfg.roles[role]))
    console.print(f"git: {'✓' if repo.is_repo(Path('.')) else '–'}   GITHUB_TOKEN: {'✓' if os.environ.get('GITHUB_TOKEN') else '–'}")
    return 1 if missing else 0


async def _ask_terminal(name: str, args: dict, preview: str) -> str:
    console.print(f"[bold red]⚠ {name}[/]\n{escape(preview[:1500])}")
    ans = await asyncio.to_thread(input, "allow? [y]es / [a]lways / [n]o: ")
    return {"y": "yes", "a": "always"}.get(ans.strip().lower()[:1], "no")


def _print_event(e: Event) -> None:
    d = e.data
    if e.kind == "model":
        console.print(f"[red]◈ {d['role']}[/] [dim]{d['model']}[/]")
    elif e.kind == "plan":
        console.print(f"[dim]{escape(d['text'])}[/]")
    elif e.kind == "say":
        console.print(escape(d["text"]))
    elif e.kind == "tool_result":
        mark = "[white]✓[/]" if d["ok"] else "[bold red]✗[/]"
        arg = d["args"].get("path") or d["args"].get("command") or d["args"].get("pattern") or ""
        console.print(f"  [dim]⚙ {d['name']}[/] {escape(str(arg))[:70]} {mark} [dim]{escape(d['summary'])}[/]")
    elif e.kind in ("info", "compact"):
        console.print(f"[dim]· {escape(d.get('text') or d.get('how'))}[/]")
    elif e.kind == "error":
        console.print(f"[bold red]{escape(d['text'])}[/]")


async def _run_headless(args) -> int:
    cfg = load_config(args.config)
    if args.mode:
        cfg.settings.mode = args.mode
    if cfg.missing_keys():
        console.print(f"[red]missing API key(s): {', '.join(cfg.missing_keys())}[/]  (add to .env or export)")
        return 2
    root = resolve_workspace(args)
    if args.branch:
        repo.ensure_repo(root)
        repo.create_branch(root, args.branch)
    router = Router(cfg, on_switch=lambda role, ref, why: console.print(f"[dim]↪ {role} → {ref} ({escape(why)})[/]"))
    agent = Agent(cfg, router, Toolbox(root), _print_event, _ask_terminal)
    console.print(f"[bold red]STRIX[/] {root}  mode={agent.mode}")
    await agent.run(args.task)
    if args.pr and repo.changes(root):
        msg = await repo.commit(router, root)
        console.print(f"committed: {escape(msg.splitlines()[0])}")
        repo.push(root)
        console.print("PR:", await repo.open_pr(root, msg.splitlines()[0], "Opened by Strix."))
    return 0


def main(argv: list[str] | None = None) -> int:
    load_dotenv()
    ap = argparse.ArgumentParser(prog="strix", description="Autonomous terminal coding agent for open and budget models.")
    ap.add_argument("--version", action="version", version=f"strix {__version__}")
    ap.add_argument("-c", "--config", help="path to strix.yaml")
    sub = ap.add_subparsers(dest="cmd")

    def workspace_opts(p):
        p.add_argument("--repo", help="local path, owner/repo, or git URL (cloned into ./workspace)")
        p.add_argument("--dir", help="working directory (default: current)")
        p.add_argument("--mode", choices=["ask", "auto-edit", "yolo"])

    tui = sub.add_parser("tui", help="open the terminal UI (default)"); workspace_opts(tui)
    run = sub.add_parser("run", help="run one task headless"); workspace_opts(run)
    run.add_argument("task"); run.add_argument("--branch"); run.add_argument("--pr", action="store_true", help="commit, push and open a PR afterwards")
    models = sub.add_parser("models", help="list tool-capable OpenRouter models")
    models.add_argument("search", nargs="*"); models.add_argument("--free", action="store_true"); models.add_argument("--limit", type=int, default=60)
    web = sub.add_parser("web", help="phone-friendly web UI (open it in your browser)")
    web.add_argument("--port", type=int, default=8000); web.add_argument("--host", default="127.0.0.1")
    web.add_argument("--projects", help="folder that holds your projects (default ~/strix-projects)")
    sub.add_parser("doctor", help="check config and keys"); sub.add_parser("init", help="write strix.yaml, STRIX.md and .env here")

    args = ap.parse_args(argv)
    try:
        if args.cmd == "init":
            return cmd_init(args)
        if args.cmd == "models":
            return cmd_models(args)
        if args.cmd == "doctor":
            return cmd_doctor(args)
        if args.cmd == "web":
            from .web import serve
            cfg = load_config(args.config)
            try:
                asyncio.run(serve(cfg, Path(args.projects or "~/strix-projects").expanduser(), args.host, args.port))
            except OSError as e:
                console.print(f"[red]cannot start on port {args.port}: {escape(str(e))}. Try --port 8010[/]")
                return 2
            return 0
        if args.cmd == "run":
            return asyncio.run(_run_headless(args))
        from .tui import run_tui
        if args.cmd is None:
            args.repo = args.dir = args.mode = None
        return run_tui(args, load_config(args.config), resolve_workspace(args))
    except KeyboardInterrupt:
        return 130
    except (FileNotFoundError, ValueError, repo.GitError) as e:
        console.print(f"[red]{escape(str(e))}[/]")
        return 2


if __name__ == "__main__":
    sys.exit(main())
