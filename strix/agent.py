"""The agent loop: plan -> call model with tools -> run tools -> repeat, with permissions and context compaction."""
from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Awaitable, Callable

from . import tools as T
from .config import Config
from .router import AllModelsFailed, Router
from .providers import ProviderError

SYSTEM = """You are Strix, an autonomous coding agent working inside a git repository. You finish the user's task by calling tools, not by describing what you would do.

How to work:
- Explore first: list_dir, grep and read_file before editing. Never invent file contents you have not read.
- Make small, focused changes with edit_file (the 'old' text must match the file exactly and be unique). Use write_file only for new files.
- After changing code, verify it: run the project's tests, linter or a quick script with bash. If something fails, read the error, fix it and re-run.
- For tasks with several steps, keep a checklist with the todo tool and update it as you go.
- Do not paste whole files into chat. Do not run destructive commands. Never print secrets.
- When done, reply with a short summary: what changed, how you verified it, and anything left for the user. Reply without tool calls only when the task is complete or you need an answer from the user.

Workspace: {root}
{project_notes}
Repository map:
{repo_map}
"""

PLANNER = """You are the planning model for a coding agent. Given the task and repository map, write a concise plan: 3 to 6 numbered steps, naming the files to inspect or change and how to verify the result. No code, no preamble."""


@dataclass
class Event:
    kind: str  # info plan model say tool_start tool_result todo usage compact error done
    data: dict = field(default_factory=dict)


Permission = Callable[[str, dict, str], Awaitable[str]]  # -> "yes" | "always" | "no"


def estimate_tokens(messages: list[dict]) -> int:
    return sum(len(json.dumps(m, default=str)) for m in messages) // 4


class Agent:
    def __init__(self, cfg: Config, router: Router, toolbox: T.Toolbox, on_event: Callable[[Event], None] | None = None,
                 ask: Permission | None = None):
        self.cfg, self.router, self.tb = cfg, router, toolbox
        self.emit = on_event or (lambda e: None)
        self.ask = ask
        self.mode = cfg.settings.mode
        self.planning = cfg.settings.planning
        self.allowed: set[str] = set()
        self.messages: list[dict] = []
        self.tool_schemas = T.schemas()
        self.reset()

    # ---- session -----------------------------------------------------
    def reset(self) -> None:
        notes = ""
        for name in ("STRIX.md", "AGENTS.md", "CLAUDE.md"):
            f = self.tb.root / name
            if f.is_file():
                notes = f"Project notes ({name}):\n{f.read_text(errors='replace')[:4000]}\n"
                break
        self.messages = [{"role": "system", "content": SYSTEM.format(root=self.tb.root, project_notes=notes, repo_map=T.repo_map(self.tb.root))}]

    # ---- permissions -------------------------------------------------
    def _needs_permission(self, tool: T.Tool, args: dict) -> bool:
        if tool.kind == "read" or self.mode == "yolo":
            return tool.kind == "exec" and T.is_dangerous(str(args.get("command", "")))
        if tool.kind == "write":
            return self.mode == "ask" and "write" not in self.allowed
        cmd = str(args.get("command", ""))
        if T.is_dangerous(cmd):
            return True
        return not (T.is_safe_command(cmd) or "bash:" + cmd.split(" ")[0] in self.allowed)

    async def _authorise(self, tool: T.Tool, args: dict) -> bool:
        if not self._needs_permission(tool, args):
            return True
        if self.ask is None:
            return False
        answer = await self.ask(tool.name, args, self.tb.preview(tool.name, args))
        if answer == "always":
            self.allowed.add("write" if tool.kind == "write" else "bash:" + str(args.get("command", "")).split(" ")[0])
        return answer in ("yes", "always")

    # ---- context management -----------------------------------------
    async def _compact(self) -> None:
        limit = self.cfg.settings.context_tokens
        if estimate_tokens(self.messages) < limit * 0.7:
            return
        keep = 8
        for m in self.messages[1:-keep]:  # shrink old tool output first
            if m["role"] == "tool" and len(m["content"]) > 600:
                m["content"] = m["content"][:300] + "\n...[old output trimmed]...\n" + m["content"][-200:]
        if estimate_tokens(self.messages) < limit * 0.7:
            self.emit(Event("compact", {"how": "trimmed old tool output"}))
            return
        cut = len(self.messages) - keep
        while cut < len(self.messages) and self.messages[cut]["role"] == "tool":
            cut += 1
        old = self.messages[1:cut]
        if len(old) < 4:
            return
        transcript = "\n".join(f"{m['role']}: {str(m.get('content') or m.get('tool_calls'))[:500]}" for m in old)[:24000]
        try:
            r = await self.router.complete("fast", [
                {"role": "system", "content": "Summarise this coding-agent transcript in under 300 words: goal, files changed, decisions, failures still open."},
                {"role": "user", "content": transcript}])
            summary = r.content
        except ProviderError:
            summary = "(earlier steps omitted to save context)"
        self.messages = [self.messages[0], {"role": "user", "content": f"Summary of earlier work in this session:\n{summary}"}] + self.messages[cut:]
        self.emit(Event("compact", {"how": f"summarised {len(old)} messages"}))

    # ---- planning ----------------------------------------------------
    async def _plan(self, task: str) -> str:
        if not (self.planning and len(task.split()) >= 8) or "planner" not in self.cfg.roles:
            return ""
        try:
            r = await self.router.complete("planner", [
                {"role": "system", "content": PLANNER},
                {"role": "user", "content": f"Task:\n{task}\n\nRepository map:\n{T.repo_map(self.tb.root, 80)}"}])
        except ProviderError as e:
            self.emit(Event("info", {"text": f"planner unavailable, continuing without a plan ({str(e).splitlines()[0][:80]})"}))
            return ""
        self.emit(Event("model", {"role": "planner", "model": str(self.router.current("planner"))}))
        self.emit(Event("plan", {"text": r.content.strip()}))
        return r.content.strip()

    # ---- main loop ---------------------------------------------------
    async def run(self, task: str) -> str:
        plan = await self._plan(task)
        self.messages.append({"role": "user", "content": task + (f"\n\n<plan from planner>\n{plan}\n</plan>" if plan else "")})
        bad_models: set[str] = set()
        bad_streak, seen = 0, {}
        final = ""
        pending: list = []
        try:
            for _step in range(self.cfg.settings.max_steps):
                await self._compact()
                resp = await self.router.complete("coder", self.messages, self.tool_schemas, skip=bad_models)
                model = str(self.router.current("coder"))
                self.emit(Event("model", {"role": "coder", "model": model}))
                self.emit(Event("usage", dict(self.router.stats)))
                if resp.content.strip():
                    self.emit(Event("say", {"text": resp.content.strip()}))
                if not resp.tool_calls:
                    final = resp.content.strip()
                    self.messages.append({"role": "assistant", "content": resp.content})
                    break
                parsed = []
                for tc in resp.tool_calls:
                    try:
                        parsed.append((tc, T.parse_args(tc.arguments), None))
                    except T.ToolError as e:
                        parsed.append((tc, {}, f"ERROR: {e}"))
                self.messages.append({"role": "assistant", "content": resp.content or None, "tool_calls": [
                    {"id": tc.id, "type": "function", "function": {"name": tc.name, "arguments": json.dumps(args) if err is None else "{}"}}
                    for tc, args, err in parsed]})
                pending = [tc.id for tc, _, _ in parsed]
                step_bad = False
                for tc, args, err in parsed:
                    out = err or await self._execute(tc.name, args, seen)
                    step_bad = step_bad or out.startswith("ERROR")
                    self.messages.append({"role": "tool", "tool_call_id": tc.id, "content": out})
                    pending.remove(tc.id)
                bad_streak = bad_streak + 1 if step_bad else 0
                if bad_streak >= self.cfg.settings.bad_calls_before_switch:
                    bad_models.add(model)
                    bad_streak = 0
                    self.emit(Event("info", {"text": f"{model} keeps sending bad tool calls; switching model"}))
            else:
                final = f"Stopped after {self.cfg.settings.max_steps} steps. Say 'continue' to keep going."
                self.emit(Event("say", {"text": final}))
        except AllModelsFailed as e:
            self.emit(Event("error", {"text": str(e)}))
            final = ""
        except asyncio.CancelledError:
            for cid in pending:  # keep the transcript valid for the next turn
                self.messages.append({"role": "tool", "tool_call_id": cid, "content": "cancelled by user"})
            self.messages.append({"role": "user", "content": "(the user interrupted the previous run)"})
            self.emit(Event("info", {"text": "stopped"}))
            raise
        self.emit(Event("done", {"text": final}))
        return final

    async def _execute(self, name: str, args: dict, seen: dict) -> str:
        tool = T.TOOL_BY_NAME.get(name)
        if tool is None:
            return f"ERROR: unknown tool '{name}'. Available: {', '.join(T.TOOL_BY_NAME)}"
        key = name + json.dumps(args, sort_keys=True, default=str)
        seen[key] = seen.get(key, 0) + 1
        if seen[key] > 2 and tool.kind != "exec":
            return "ERROR: you already made this exact call twice. Try something different or explain what is blocking you."
        self.emit(Event("tool_start", {"name": name, "args": args}))
        if not await self._authorise(tool, args):
            self.emit(Event("tool_result", {"name": name, "args": args, "ok": False, "summary": "denied", "diff": ""}))
            return "ERROR: the user did not allow this action. Ask what they want instead, or choose a different approach."
        try:
            out = await asyncio.to_thread(self.tb.run, name, args)
            ok = True
        except T.ToolError as e:
            out, ok = f"ERROR: {e}", False
        except Exception as e:  # tool bug: report it, keep the loop alive
            out, ok = f"ERROR: {e.__class__.__name__}: {e}", False
        summary = out.splitlines()[0][:90] if out else ""
        if name == "bash":
            ok = ok and out.startswith("exit 0")
            summary = out.splitlines()[0] if out else ""
        elif name == "read_file":
            summary = f"{len(out.splitlines())} lines"
        elif name in ("grep", "glob", "list_dir"):
            summary = "no matches" if out == "no matches" else f"{len(out.splitlines())} results"
        self.emit(Event("tool_result", {"name": name, "args": args, "ok": ok, "summary": summary, "diff": self.tb.last_diff}))
        return out
