"""Agent tools: file read/write/edit, search, shell, todo. All paths are confined to the workspace."""
from __future__ import annotations

import ast
import difflib
import json
import os
import re
import signal
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

IGNORE_DIRS = {".git", "node_modules", "__pycache__", ".venv", "venv", ".mypy_cache", ".pytest_cache", "dist", "build", ".next", "target", ".idea", ".strix"}
SECRET_ENV = re.compile(r"(KEY|TOKEN|SECRET|PASSWORD|PASSWD|CREDENTIAL)", re.I)
MAX_OUT = 9000


class ToolError(Exception):
    """A problem the model can read and recover from."""


@dataclass
class Tool:
    name: str
    description: str
    parameters: dict
    kind: str  # read | write | exec

    def schema(self) -> dict:
        return {"type": "function", "function": {"name": self.name, "description": self.description, "parameters": self.parameters}}


def _obj(props: dict, required: list[str]) -> dict:
    return {"type": "object", "properties": props, "required": required}


S = {"type": "string"}
TOOLS: list[Tool] = [
    Tool("read_file", "Read a text file with line numbers. Use start/end for big files.",
         _obj({"path": S, "start": {"type": "integer"}, "end": {"type": "integer"}}, ["path"]), "read"),
    Tool("list_dir", "List files and folders (default depth 2).",
         _obj({"path": S, "depth": {"type": "integer"}}, []), "read"),
    Tool("glob", "Find files by glob pattern, e.g. 'src/**/*.py'.", _obj({"pattern": S}, ["pattern"]), "read"),
    Tool("grep", "Search file contents with a regex. Returns path:line:text.",
         _obj({"pattern": S, "path": S, "include": {"type": "string", "description": "glob filter like *.py"}}, ["pattern"]), "read"),
    Tool("write_file", "Create or fully overwrite a file. Prefer edit_file for existing files.",
         _obj({"path": S, "content": S}, ["path", "content"]), "write"),
    Tool("edit_file", "Replace exact text in a file. 'old' must match the file exactly (without line numbers) and be unique unless replace_all is true.",
         _obj({"path": S, "old": S, "new": S, "replace_all": {"type": "boolean"}}, ["path", "old", "new"]), "write"),
    Tool("bash", "Run a shell command in the workspace (tests, linters, git, package managers). Output is truncated.",
         _obj({"command": S, "timeout": {"type": "integer", "description": "seconds, default 120"}}, ["command"]), "exec"),
    Tool("todo", "Set the visible task checklist. Each item: {text, status: pending|in_progress|done}. Call again to update.",
         _obj({"items": {"type": "array", "items": _obj({"text": S, "status": {"type": "string"}}, ["text", "status"])}}, ["items"]), "read"),
]
TOOL_BY_NAME = {t.name: t for t in TOOLS}


def schemas() -> list[dict]:
    return [t.schema() for t in TOOLS]


def parse_args(raw: str | dict) -> dict:
    """Parse model-produced tool arguments, repairing the usual damage (fences, trailing commas, python dicts)."""
    if isinstance(raw, dict):
        return raw
    s = (raw or "").strip()
    if not s:
        return {}
    s = re.sub(r"^```(?:json)?\s*|\s*```$", "", s)
    for attempt in (s, re.sub(r",\s*([}\]])", r"\1", s)):
        try:
            v = json.loads(attempt)
            if isinstance(v, dict):
                return v
        except ValueError:
            pass
    try:
        v = ast.literal_eval(s)
        if isinstance(v, dict):
            return v
    except (ValueError, SyntaxError):
        pass
    raise ToolError("arguments were not valid JSON. Send a single JSON object matching the tool schema.")


def validate_args(tool: Tool, args: dict) -> None:
    missing = [k for k in tool.parameters["required"] if k not in args]
    if missing:
        props = ", ".join(f"{k}" for k in tool.parameters["properties"])
        raise ToolError(f"{tool.name} is missing required argument(s): {', '.join(missing)}. Accepted: {props}.")


def _clip(text: str, limit: int = MAX_OUT) -> str:
    if len(text) <= limit:
        return text
    head, tail = text[: limit * 2 // 3], text[-limit // 3:]
    return f"{head}\n... [{len(text) - limit} chars truncated] ...\n{tail}"


def unified(old: str, new: str, name: str) -> str:
    return "".join(difflib.unified_diff(old.splitlines(True), new.splitlines(True), f"a/{name}", f"b/{name}", n=2))


class Toolbox:
    def __init__(self, root: str | Path):
        self.root = Path(root).resolve()
        self.todos: list[dict] = []
        self.undo_stack: list[tuple[Path, str | None]] = []
        self.last_diff = ""
        self.on_todo: Callable[[list[dict]], None] | None = None

    # ---- paths -------------------------------------------------------
    def rel(self, p: Path) -> str:
        try:
            return str(p.relative_to(self.root))
        except ValueError:
            return str(p)

    def _path(self, path: str, write: bool = False) -> Path:
        if not isinstance(path, str) or not path:
            raise ToolError("path must be a non-empty string")
        p = Path(path)
        p = (p if p.is_absolute() else self.root / p).resolve()
        if p != self.root and self.root not in p.parents:
            raise ToolError(f"path '{path}' is outside the workspace ({self.root})")
        if write and ".git" in p.relative_to(self.root).parts:
            raise ToolError("writing inside .git is not allowed")
        return p

    # ---- dispatch ----------------------------------------------------
    def run(self, name: str, args: dict) -> str:
        tool = TOOL_BY_NAME.get(name)
        if not tool:
            raise ToolError(f"unknown tool '{name}'. Available: {', '.join(TOOL_BY_NAME)}")
        validate_args(tool, args)
        self.last_diff = ""
        return getattr(self, "t_" + name)(**{k: v for k, v in args.items() if k in tool.parameters["properties"]})

    def preview(self, name: str, args: dict) -> str:
        """Text shown in the permission prompt: a diff for edits, the command for bash."""
        try:
            if name == "bash":
                return str(args.get("command", ""))
            if name == "write_file":
                p = self._path(args["path"], True)
                old = p.read_text(errors="replace") if p.exists() else ""
                return unified(old, args.get("content", ""), self.rel(p)) or "(no change)"
            if name == "edit_file":
                p = self._path(args["path"], True)
                old = p.read_text(errors="replace")
                new, _ = self._apply_edit(old, args["old"], args["new"], bool(args.get("replace_all")), self.rel(p))
                return unified(old, new, self.rel(p))
        except (ToolError, OSError, KeyError) as e:
            return f"(cannot preview: {e})"
        return ""

    # ---- read tools --------------------------------------------------
    def t_read_file(self, path: str, start: int = 1, end: int | None = None) -> str:
        p = self._path(path)
        if not p.is_file():
            raise ToolError(f"{path} is not a file" + (" (it is a directory; use list_dir)" if p.is_dir() else " (does not exist)"))
        data = p.read_bytes()
        if b"\0" in data[:2048]:
            raise ToolError(f"{path} looks binary")
        lines = data.decode("utf-8", "replace").splitlines()
        start = max(1, int(start or 1))
        stop = min(len(lines), int(end) if end else start + 399)
        body = "\n".join(f"{i:>5}\t{lines[i - 1]}" for i in range(start, stop + 1))
        note = f"\n[showing {start}-{stop} of {len(lines)} lines; continue with start={stop + 1}]" if stop < len(lines) else ""
        return _clip(body or "(empty file)") + note

    def _walk(self, base: Path):
        for dirpath, dirs, files in os.walk(base):
            dirs[:] = sorted(d for d in dirs if d not in IGNORE_DIRS)
            for f in sorted(files):
                yield Path(dirpath) / f

    def t_list_dir(self, path: str = ".", depth: int = 2) -> str:
        base = self._path(path)
        if not base.is_dir():
            raise ToolError(f"{path} is not a directory")
        out, depth = [], max(1, int(depth or 2))

        def rec(d: Path, level: int):
            if len(out) > 250:
                return
            for c in sorted(d.iterdir(), key=lambda x: (x.is_file(), x.name.lower())):
                if c.name in IGNORE_DIRS:
                    continue
                out.append("  " * level + c.name + ("/" if c.is_dir() else ""))
                if c.is_dir() and level + 1 < depth:
                    rec(c, level + 1)

        rec(base, 0)
        return "\n".join(out[:250]) or "(empty)"

    def t_glob(self, pattern: str) -> str:
        hits = [self.rel(p) for p in self.root.glob(pattern) if p.is_file() and not (set(p.relative_to(self.root).parts) & IGNORE_DIRS)]
        hits.sort()
        more = f"\n... {len(hits) - 200} more" if len(hits) > 200 else ""
        return "\n".join(hits[:200]) + more if hits else "no matches"

    def t_grep(self, pattern: str, path: str = ".", include: str | None = None) -> str:
        try:
            rx = re.compile(pattern)
        except re.error as e:
            raise ToolError(f"invalid regex: {e}") from None
        base = self._path(path)
        files = [base] if base.is_file() else self._walk(base)
        hits: list[str] = []
        for f in files:
            if include and not f.match(include):
                continue
            try:
                if f.stat().st_size > 1_000_000:
                    continue
                text = f.read_bytes()
                if b"\0" in text[:1024]:
                    continue
                for n, line in enumerate(text.decode("utf-8", "replace").splitlines(), 1):
                    if rx.search(line):
                        hits.append(f"{self.rel(f)}:{n}:{line.strip()[:200]}")
                        if len(hits) >= 100:
                            return "\n".join(hits) + "\n[stopped at 100 matches; narrow the pattern]"
            except OSError:
                continue
        return "\n".join(hits) if hits else "no matches"

    # ---- write tools -------------------------------------------------
    def _save(self, p: Path, new: str, old: str | None) -> str:
        self.undo_stack.append((p, old))
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(new)
        self.last_diff = unified(old or "", new, self.rel(p))
        return self._syntax_warning(p, new)

    @staticmethod
    def _syntax_warning(p: Path, text: str) -> str:
        try:
            if p.suffix == ".py":
                compile(text, str(p), "exec")
            elif p.suffix == ".json":
                json.loads(text)
        except (SyntaxError, ValueError) as e:
            return f"\nWARNING: {p.name} now has a syntax error: {e}. Fix it."
        return ""

    def t_write_file(self, path: str, content: str) -> str:
        p = self._path(path, True)
        if p.is_dir():
            raise ToolError(f"{path} is a directory")
        old = p.read_text(errors="replace") if p.exists() else None
        warn = self._save(p, content, old)
        return f"{'updated' if old is not None else 'created'} {self.rel(p)} ({content.count(chr(10)) + 1} lines){warn}"

    @staticmethod
    def _apply_edit(text: str, old: str, new: str, replace_all: bool, name: str) -> tuple[str, int]:
        if old == new:
            raise ToolError("'old' and 'new' are identical")
        if not old:
            raise ToolError("'old' is empty. To create a file use write_file.")
        n = text.count(old)
        if n == 0:
            # help the model: it often copies line numbers or wrong indentation
            first = old.strip().splitlines()[0].strip() if old.strip() else ""
            close = difflib.get_close_matches(first, [l.strip() for l in text.splitlines()], n=1, cutoff=0.6)
            hint = f" Closest line in file: {close[0]!r}." if close else ""
            raise ToolError(f"'old' text not found in {name}. Copy it exactly from read_file output (no line numbers, same indentation).{hint}")
        if n > 1 and not replace_all:
            raise ToolError(f"'old' matches {n} places in {name}. Add surrounding lines to make it unique, or set replace_all=true.")
        return text.replace(old, new) if replace_all else text.replace(old, new, 1), n

    def t_edit_file(self, path: str, old: str, new: str, replace_all: bool = False) -> str:
        p = self._path(path, True)
        if not p.is_file():
            raise ToolError(f"{path} does not exist; use write_file to create it")
        text = p.read_text(errors="replace")
        updated, n = self._apply_edit(text, old, new, bool(replace_all), self.rel(p))
        warn = self._save(p, updated, text)
        return f"edited {self.rel(p)} ({n if replace_all else 1} replacement{'s' if replace_all and n != 1 else ''}){warn}"

    def undo(self) -> str:
        if not self.undo_stack:
            return "nothing to undo"
        p, old = self.undo_stack.pop()
        if old is None:
            p.unlink(missing_ok=True)
            return f"removed {self.rel(p)}"
        p.write_text(old)
        return f"restored {self.rel(p)}"

    # ---- shell -------------------------------------------------------
    def t_bash(self, command: str, timeout: int = 120) -> str:
        env = {k: v for k, v in os.environ.items() if not SECRET_ENV.search(k)}
        env.update({"CI": "1", "GIT_TERMINAL_PROMPT": "0", "PAGER": "cat"})
        proc = subprocess.Popen(["bash", "-c", command], cwd=self.root, env=env, stdin=subprocess.DEVNULL,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace", start_new_session=True)
        try:
            out, _ = proc.communicate(timeout=max(1, min(int(timeout or 120), 900)))
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid, signal.SIGKILL)
            out, _ = proc.communicate()
            return _clip(out or "") + f"\n[killed after {timeout}s timeout]"
        return f"exit {proc.returncode}\n" + _clip(out or "")

    # ---- todo --------------------------------------------------------
    def t_todo(self, items: list) -> str:
        clean = []
        for it in items if isinstance(items, list) else []:
            if isinstance(it, dict) and it.get("text"):
                st = it.get("status", "pending")
                clean.append({"text": str(it["text"]), "status": st if st in ("pending", "in_progress", "done") else "pending"})
        self.todos = clean
        if self.on_todo:
            self.on_todo(clean)
        return "todo updated: " + ", ".join(f"[{t['status']}] {t['text']}" for t in clean)


SAFE_PREFIXES = ("ls", "pwd", "cat", "head", "tail", "wc", "git status", "git diff", "git log", "git branch", "git show",
                 "pytest", "python -m pytest", "python3 -m pytest", "python -m py_compile", "python3 -m py_compile", "ruff", "mypy",
                 "npm test", "npm run test", "npm run lint", "npm run build", "pnpm test", "yarn test", "go test", "go build", "go vet",
                 "cargo test", "cargo build", "cargo check", "tsc", "node --version", "python --version", "python3 --version", "make test")
SHELL_META = re.compile(r"[;&|<>`]|\$\(")


def is_safe_command(cmd: str) -> bool:
    c = cmd.strip()
    return bool(c) and not SHELL_META.search(c) and any(c == p or c.startswith(p + " ") for p in SAFE_PREFIXES)


DANGEROUS = re.compile(r"(rm\s+-[a-z]*r[a-z]*f?\s+(/|~|\$HOME)(\s|$)|:\(\)\s*\{|mkfs|dd\s+if=.*of=/dev/|git\s+push\s+.*--force|curl[^|]*\|\s*(ba)?sh)")


def is_dangerous(cmd: str) -> bool:
    return bool(DANGEROUS.search(cmd))


def repo_map(root: Path, limit: int = 160) -> str:
    """File list plus top-level Python symbols: cheap orientation for the model."""
    files: list[str] = []
    try:
        out = subprocess.run(["git", "ls-files"], cwd=root, capture_output=True, text=True, timeout=10)
        if out.returncode == 0:
            files = [f for f in out.stdout.splitlines() if not (set(Path(f).parts) & IGNORE_DIRS)]
    except (OSError, subprocess.SubprocessError):
        pass
    if not files:
        for dirpath, dirs, fs in os.walk(root):
            dirs[:] = sorted(d for d in dirs if d not in IGNORE_DIRS)
            files += [str((Path(dirpath) / f).relative_to(root)) for f in sorted(fs)]
    lines = []
    for f in files[:limit]:
        entry = f
        if f.endswith(".py") and len(lines) < 60:
            try:
                tree = ast.parse((root / f).read_text(errors="replace"))
                names = [n.name for n in tree.body if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))][:8]
                if names:
                    entry += "  [" + ", ".join(names) + "]"
            except (SyntaxError, OSError):
                pass
        lines.append(entry)
    more = f"\n... and {len(files) - limit} more files" if len(files) > limit else ""
    return "\n".join(lines) + more if lines else "(empty repository)"
