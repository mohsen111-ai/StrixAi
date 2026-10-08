"""The Strix terminal UI (Textual): splash, live agent stream, plan/router/usage sidebar, permission prompts."""
from __future__ import annotations

import asyncio
from pathlib import Path

from rich.markup import escape
from rich.text import Text
from textual import events
from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.screen import ModalScreen, Screen
from textual.widgets import Input, RichLog, Static

from . import __version__, repo
from .agent import Agent, Event, estimate_tokens
from .config import Config
from .router import Router
from .tools import Toolbox

CRIMSON = "#FF1744"
FRAMES = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏"
OWL = f"""  ▟▛▀▀▀▀▀▀▀▜▙
  █ [{CRIMSON}]◆◆◆[/]   [{CRIMSON}]◆◆◆[/] █
  ▜▙   ▼▼▼   ▟▛
    ▀▜▙ ▼ ▟▛▀
       ▀▀▀"""
HELP = """[b]Commands[/b]
  /mode ask|auto-edit|yolo   permission mode
  /plan      toggle planner step      /models   show routing chains
  /diff      uncommitted changes      /undo     revert last file edit
  /new       fresh conversation       /clear    clear the screen
  /branch N  create a git branch      /commit   commit with an AI message
  /push      push current branch      /pr       push + open pull request
  /quit      exit
[b]Keys[/b]  enter send · esc stop the run · ctrl+c twice quit"""


def mk(s: str) -> Text:
    return Text.from_markup(s)


def short(model: str) -> str:
    return model.split(":", 1)[-1].split("/")[-1]


class SplashScreen(Screen):
    def __init__(self, checks: list[tuple[bool, str]]):
        super().__init__()
        self.checks = checks

    def compose(self) -> ComposeResult:
        with Vertical(id="splash"):
            yield Static(mk(f"[b]{OWL}[/b]\n\n[b]S  T  R  I  X[/b]\n[dim]v{__version__} · open-model coding agent[/dim]"), id="splash-art")
            lines = [f"[white]✓[/white] {escape(t)}" if ok else f"[{CRIMSON}]✗[/] {escape(t)}" for ok, t in self.checks]
            yield Static(mk("\n".join(lines)), id="splash-boot")
            yield Static("", id="splash-bar")

    def on_mount(self) -> None:
        self.n = 0
        self.set_interval(0.07, self._tick)

    def _tick(self) -> None:
        self.n += 1
        total = 20
        on = min(total, self.n)
        self.query_one("#splash-bar", Static).update(mk(f"[{CRIMSON}]{'▮' * on}[/][#2b2f3d]{'▮' * (total - on)}[/]"))
        if self.n >= total + 2:
            self.dismiss()

    def on_key(self, event: events.Key) -> None:
        self.dismiss()


class PermissionScreen(ModalScreen[str]):
    BINDINGS = [Binding("y", "answer('yes')", "allow"), Binding("a", "answer('always')", "always"),
                Binding("n,escape", "answer('no')", "deny")]

    def __init__(self, tool: str, preview: str):
        super().__init__()
        self.tool, self.preview = tool, preview

    def compose(self) -> ComposeResult:
        with Vertical(id="dialog"):
            yield Static(mk(f"⚠  [b]{escape(self.tool)}[/b] wants to run"), id="dialog-title")
            body = Text()
            for line in (self.preview or "(no preview)").splitlines()[:200]:
                style = "green" if line.startswith("+") and not line.startswith("+++") else CRIMSON if line.startswith("-") and not line.startswith("---") else "bright_black" if line.startswith("@@") else ""
                body.append(line + "\n", style=style)
            yield VerticalScroll(Static(body), id="dialog-body")
            yield Static(mk(f"[b on {CRIMSON}] y [/] allow   [b] a [/] always this session   [b] n [/] deny"), id="dialog-keys")

    def action_answer(self, answer: str) -> None:
        self.dismiss(answer)


class StrixApp(App):
    CSS_PATH = "strix.tcss"
    TITLE = "Strix"
    BINDINGS = [Binding("ctrl+c", "interrupt", "stop", priority=True), Binding("escape", "stop", "stop"),
                Binding("ctrl+l", "clear", "clear"), Binding("ctrl+d", "quit", "quit")]

    def __init__(self, cfg: Config, root: Path, router: Router | None = None, chat_fn=None, splash: bool = True):
        super().__init__()
        self.cfg, self.root = cfg, root
        self.router = router or Router(cfg, chat_fn=chat_fn, on_switch=self._on_switch)
        if router is not None:
            router.on_switch = self._on_switch
        self.toolbox = Toolbox(root)
        self.agent = Agent(cfg, self.router, self.toolbox, self.handle_event, self.ask_permission)
        self.todos: list[dict] = []
        self.busy = False
        self.activity = ""
        self.frame = 0
        self.worker = None
        self.quit_armed = False
        self.status_shown = False
        self.show_splash = splash

    # ---- layout ------------------------------------------------------
    def compose(self) -> ComposeResult:
        yield Static("", id="topbar")
        with Horizontal(id="body"):
            yield RichLog(id="stream", markup=True, wrap=True, highlight=False, auto_scroll=True)
            with Vertical(id="side"):
                for pid in ("plan", "route", "context", "usage", "git"):
                    yield Static("", id=pid, classes="panel")
        yield Static("", id="status")
        with Horizontal(id="inputrow"):
            yield Static("❯", id="caret")
            yield Input(placeholder="ask Strix, or type /help", id="prompt")
            yield Static(mk("[dim]/help[/dim]"), id="hints")

    def on_mount(self) -> None:
        self.set_interval(0.08, self._spin)
        self.refresh_all()
        self.log_line(f"[dim]workspace[/dim] {escape(str(self.root))}  [dim]mode[/dim] {self.agent.mode}")
        missing = self.cfg.missing_keys()
        if missing:
            self.log_line(f"[{CRIMSON}]No API key found for: {', '.join(missing)}[/]. Add it to .env or export it, then restart. (see: strix doctor)")
        self.query_one("#prompt", Input).focus()
        if self.show_splash:
            checks = [(True, f"config · {Path(self.cfg.source).name if self.cfg.source != 'built-in default' else 'built-in default'}"),
                      (not missing, "api keys" + (f" · missing {missing[0]}" if missing else " · ok")),
                      (True, f"router · {sum(len(c) for c in self.cfg.roles.values())} models in {len(self.cfg.roles)} roles"),
                      (repo.is_repo(self.root), f"git · {repo.branch(self.root)}" if repo.is_repo(self.root) else "git · not a repo yet")]
            self.push_screen(SplashScreen(checks))

    # ---- rendering helpers ------------------------------------------
    def log_line(self, markup: str) -> None:
        self.query_one("#stream", RichLog).write(mk(markup))

    def refresh_all(self) -> None:
        mode_color = {"ask": "white", "auto-edit": CRIMSON, "yolo": CRIMSON}[self.agent.mode]
        coder = str(self.router.current("coder"))
        self.query_one("#topbar", Static).update(mk(
            f"[b]◆ STRIX[/b]  [dim]{escape(self.root.name)}[/dim]  ⎇ {escape(repo.branch(self.root) if repo.is_repo(self.root) else '-')}"
            f"   [{mode_color}][{self.agent.mode.upper()}][/]  [dim]{escape(short(coder))}[/dim]"))
        # plan
        icons = {"done": "[dim]☑[/dim]", "in_progress": f"[{CRIMSON}]▸[/]", "pending": "[dim]☐[/dim]"}
        plan = "\n".join(f"{icons[t['status']]} {'[dim]' if t['status'] == 'done' else ''}{escape(t['text'])[:26]}{'[/dim]' if t['status'] == 'done' else ''}" for t in self.todos) or "[dim]no checklist yet[/dim]"
        self.query_one("#plan", Static).update(mk(f"[dim]PLAN[/dim]\n{plan}"))
        # router
        rows = []
        for role in ("planner", "coder", "fast", "longctx"):
            if self.cfg.roles.get(role):
                rows.append(f"[{CRIMSON}]{role[:7]:<7}[/] {escape(short(str(self.router.current(role))))[:20]}")
        self.query_one("#route", Static).update(mk("[dim]ROUTER[/dim]\n" + "\n".join(rows)))
        # context
        used, limit = estimate_tokens(self.agent.messages), self.cfg.settings.context_tokens
        pct = min(100, used * 100 // limit)
        cells = 14
        filled = pct * cells // 100
        self.query_one("#context", Static).update(mk(f"[dim]CONTEXT[/dim]  {pct}%\n[{CRIMSON}]{'▮' * filled}[/][#2b2f3d]{'▮' * (cells - filled)}[/]"))
        s = self.router.stats
        self.query_one("#usage", Static).update(mk(f"[dim]USAGE[/dim]\ntokens {(s['prompt'] + s['completion']) / 1000:.1f}k · calls {s['calls']}"))
        # git
        if repo.is_repo(self.root):
            files, add, rem = repo.diff_stat(self.root)
            self.query_one("#git", Static).update(mk(f"[dim]GIT[/dim]\n⎇ {escape(repo.branch(self.root))}\n{files} files  +{add} −{rem}"))
        else:
            self.query_one("#git", Static).update(mk("[dim]GIT[/dim]\nnot a repository"))

    def _spin(self) -> None:
        status = self.query_one("#status", Static)
        if self.busy:
            self.frame = (self.frame + 1) % len(FRAMES)
            status.update(mk(f"{FRAMES[self.frame]} {escape(self.activity)}"))
            self.status_shown = True
        elif self.status_shown:
            status.update("")
            self.status_shown = False

    def _on_switch(self, role: str, ref, why: str) -> None:
        self.log_line(f"[dim]↪ {role} → {escape(str(ref))} ({escape(why)})[/dim]")

    # ---- agent events ------------------------------------------------
    def handle_event(self, e: Event) -> None:
        d = e.data
        if e.kind == "model":
            self.activity = f"{d['role']} · {short(d['model'])}"
            self.log_line(f"[{CRIMSON}]◈ {d['role']}[/] [dim]{escape(short(d['model']))}[/dim]")
        elif e.kind == "plan":
            for line in d["text"].splitlines():
                self.log_line(f"  [dim]{escape(line)}[/dim]")
        elif e.kind == "say":
            self.log_line(escape(d["text"]))
        elif e.kind == "tool_start":
            arg = d["args"].get("path") or d["args"].get("command") or d["args"].get("pattern") or ""
            self.activity = f"{d['name']} {str(arg)[:60]}"
        elif e.kind == "tool_result":
            arg = d["args"].get("path") or d["args"].get("command") or d["args"].get("pattern") or ""
            mark = "✓" if d["ok"] else f"[b {CRIMSON}]✗[/]"
            self.log_line(f"[dim]⚙ {d['name']}[/dim] {escape(str(arg))[:72]} {mark} [dim]{escape(d['summary'])}[/dim]")
            for line in d.get("diff", "").splitlines()[2:16]:
                if line.startswith("+"):
                    self.log_line(f"    [white on #1d2130]{escape(line)}[/]")
                elif line.startswith("-"):
                    self.log_line(f"    [#ff9fb2 on #2a0d15]{escape(line)}[/]")
            if d["name"] == "todo":
                self.todos = self.toolbox.todos
        elif e.kind in ("info", "compact"):
            self.log_line(f"[dim]· {escape(d.get('text') or d.get('how'))}[/dim]")
        elif e.kind == "error":
            self.log_line(f"[b {CRIMSON}]{escape(d['text'])}[/]")
        self.refresh_all()

    async def ask_permission(self, name: str, args: dict, preview: str) -> str:
        return await self.push_screen_wait(PermissionScreen(name, preview))

    # ---- input -------------------------------------------------------
    async def on_input_submitted(self, event: Input.Submitted) -> None:
        text = event.value.strip()
        event.input.value = ""
        if not text:
            return
        if text.startswith("/"):
            await self.command(text)
            return
        if self.busy:
            self.log_line("[dim]busy: press esc to stop the current run first[/dim]")
            return
        self.log_line(f"\n[b]you ▸[/b] {escape(text)}")
        self.busy, self.activity = True, "thinking"
        self.worker = self.run_worker(self._run(text), exclusive=True)

    async def _run(self, text: str) -> None:
        try:
            await self.agent.run(text)
        except asyncio.CancelledError:
            raise
        except Exception as e:  # never let a bug kill the UI
            self.log_line(f"[b {CRIMSON}]internal error: {escape(repr(e))}[/]")
        finally:
            self.busy = False
            self.refresh_all()

    async def command(self, text: str) -> None:
        cmd, _, arg = text[1:].partition(" ")
        arg = arg.strip()
        try:
            if cmd in ("quit", "exit", "q"):
                self.exit()
            elif cmd == "help":
                self.log_line(HELP)
            elif cmd == "clear":
                self.query_one("#stream", RichLog).clear()
            elif cmd == "new":
                self.agent.reset(); self.todos = []; self.query_one("#stream", RichLog).clear(); self.log_line("[dim]new conversation[/dim]")
            elif cmd == "mode":
                if arg not in ("ask", "auto-edit", "yolo"):
                    self.log_line("usage: /mode ask|auto-edit|yolo")
                else:
                    self.agent.mode = arg; self.log_line(f"[dim]mode → {arg}[/dim]")
            elif cmd == "plan":
                self.agent.planning = not self.agent.planning; self.log_line(f"[dim]planner {'on' if self.agent.planning else 'off'}[/dim]")
            elif cmd == "models":
                for role, chain in self.cfg.roles.items():
                    self.log_line(f"[{CRIMSON}]{role:<8}[/] " + " [dim]→[/dim] ".join(escape(short(str(m))) for m in chain))
            elif cmd == "diff":
                out = await asyncio.to_thread(repo.git, self.root, "diff", "HEAD", check=False)
                for line in (out or "no changes").splitlines()[:120]:
                    color = "white on #1d2130" if line.startswith("+") else "#ff9fb2 on #2a0d15" if line.startswith("-") else "dim"
                    self.log_line(f"[{color}]{escape(line)}[/]")
            elif cmd == "undo":
                self.log_line(f"[dim]{escape(self.toolbox.undo())}[/dim]")
            elif cmd == "branch":
                if not arg:
                    self.log_line("usage: /branch NAME")
                else:
                    repo.ensure_repo(self.root); repo.create_branch(self.root, arg); self.log_line(f"[dim]on branch {escape(arg)}[/dim]")
            elif cmd == "commit":
                msg = await repo.commit(self.router, self.root, arg or None)
                self.log_line(f"committed: {escape(msg.splitlines()[0])}")
            elif cmd == "push":
                self.log_line(f"pushed {escape(await asyncio.to_thread(repo.push, self.root))}")
            elif cmd == "pr":
                if repo.changes(self.root):
                    self.log_line(f"committed: {escape((await repo.commit(self.router, self.root)).splitlines()[0])}")
                await asyncio.to_thread(repo.push, self.root)
                title = repo.git(self.root, "log", "-1", "--pretty=%s")
                self.log_line(f"[b]PR[/b] {escape(await repo.open_pr(self.root, title, 'Opened by Strix.'))}")
            else:
                self.log_line(f"unknown command /{escape(cmd)}. Try /help")
        except repo.GitError as e:
            self.log_line(f"[{CRIMSON}]{escape(str(e))}[/]")
        self.refresh_all()

    # ---- keys --------------------------------------------------------
    def action_stop(self) -> None:
        if self.busy and self.worker:
            self.worker.cancel()
            self.busy = False
            self.log_line("[dim]stopped[/dim]")

    def action_interrupt(self) -> None:
        if self.busy:
            self.action_stop()
        elif self.quit_armed:
            self.exit()
        else:
            self.quit_armed = True
            self.log_line("[dim]press ctrl+c again to quit[/dim]")
            self.set_timer(2.0, lambda: setattr(self, "quit_armed", False))

    def action_clear(self) -> None:
        self.query_one("#stream", RichLog).clear()


def run_tui(args, cfg: Config, root: Path) -> int:
    if getattr(args, "mode", None):
        cfg.settings.mode = args.mode
    StrixApp(cfg, root).run()
    return 0
