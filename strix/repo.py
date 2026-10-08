"""Git and GitHub helpers: clone, branch, commit (with an AI-written message), push and open a pull request."""
from __future__ import annotations

import base64
import os
import re
import subprocess
from pathlib import Path

import httpx

from .providers import ProviderError


class GitError(Exception):
    pass


def _auth_args() -> list[str]:
    """Pass the GitHub token as a one-off header so it never lands in .git/config or the remote URL."""
    tok = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not tok:
        return []
    basic = base64.b64encode(f"x-access-token:{tok}".encode()).decode()
    return ["-c", f"http.https://github.com/.extraheader=AUTHORIZATION: basic {basic}"]


def git(root: Path, *args: str, check: bool = True, auth: bool = False) -> str:
    cmd = ["git", *(_auth_args() if auth else []), *args]
    p = subprocess.run(cmd, cwd=root, capture_output=True, text=True, env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})
    if check and p.returncode != 0:
        raise GitError((p.stderr or p.stdout).strip().replace(os.environ.get("GITHUB_TOKEN", "\0"), "***"))
    return (p.stdout or "").strip()


def is_repo(root: Path) -> bool:
    return subprocess.run(["git", "rev-parse", "--git-dir"], cwd=root, capture_output=True).returncode == 0


def ensure_repo(root: Path) -> None:
    if not is_repo(root):
        git(root, "init", "-q")


def branch(root: Path) -> str:
    return git(root, "rev-parse", "--abbrev-ref", "HEAD", check=False) or "(no commits)"


def changes(root: Path) -> list[str]:
    return [l for l in git(root, "status", "--porcelain", check=False).splitlines() if l.strip()]


def diff_stat(root: Path) -> tuple[int, int, int]:
    """(files, added, removed) for everything uncommitted."""
    out = git(root, "diff", "HEAD", "--numstat", check=False) or git(root, "diff", "--numstat", check=False)
    files = add = rem = 0
    for line in out.splitlines():
        a, r, _ = (line.split("\t") + ["", "", ""])[:3]
        files += 1
        add += int(a) if a.isdigit() else 0
        rem += int(r) if r.isdigit() else 0
    untracked = len(git(root, "ls-files", "--others", "--exclude-standard", check=False).splitlines())
    return files + untracked, add, rem


def create_branch(root: Path, name: str) -> None:
    git(root, "checkout", "-B", re.sub(r"[^A-Za-z0-9._/-]+", "-", name).strip("-"))


def clone(spec: str, dest_dir: Path) -> Path:
    """Clone 'owner/repo' or a URL into dest_dir/<repo> (or reuse it if present)."""
    url = spec if re.match(r"^(https?://|git@|/)", spec) else f"https://github.com/{spec}.git"
    name = re.sub(r"\.git$", "", url.rstrip("/").split("/")[-1])
    target = dest_dir / name
    if (target / ".git").exists():
        return target
    dest_dir.mkdir(parents=True, exist_ok=True)
    git(dest_dir, "clone", url, str(target), auth=True)
    return target


def remote_slug(root: Path) -> str | None:
    url = git(root, "remote", "get-url", "origin", check=False)
    m = re.search(r"github\.com[:/]([^/]+/[^/]+?)(?:\.git)?$", url)
    return m.group(1) if m else None


async def commit_message(router, root: Path) -> str:
    git(root, "add", "-A")
    diff = git(root, "diff", "--cached", "--stat", check=False) + "\n" + git(root, "diff", "--cached", check=False)[:12000]
    try:
        r = await router.complete("fast", [
            {"role": "system", "content": "Write a git commit message for this diff: an imperative subject line under 72 chars, then an optional short body. Output only the message."},
            {"role": "user", "content": diff}])
        return r.content.strip().strip("`") or "Update code"
    except ProviderError:
        return "Update code"


async def commit(router, root: Path, message: str | None = None) -> str:
    ensure_repo(root)
    if not changes(root):
        raise GitError("nothing to commit")
    msg = message or await commit_message(router, root)
    git(root, "add", "-A")
    git(root, "-c", "user.name=" + os.environ.get("GIT_AUTHOR_NAME", "Strix"), "-c", "user.email=" + os.environ.get("GIT_AUTHOR_EMAIL", "strix@users.noreply.github.com"),
        "commit", "-q", "-m", msg)
    return msg


def push(root: Path) -> str:
    b = branch(root)
    git(root, "push", "-u", "origin", b, auth=True)
    return b


async def open_pr(root: Path, title: str, body: str, base: str | None = None) -> str:
    tok = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    slug = remote_slug(root)
    if not tok:
        raise GitError("set GITHUB_TOKEN to open pull requests")
    if not slug:
        raise GitError("no GitHub 'origin' remote found")
    headers = {"Authorization": f"Bearer {tok}", "Accept": "application/vnd.github+json"}
    async with httpx.AsyncClient(timeout=30, headers=headers) as c:
        if not base:
            r = await c.get(f"https://api.github.com/repos/{slug}")
            base = r.json().get("default_branch", "main") if r.status_code == 200 else "main"
        r = await c.post(f"https://api.github.com/repos/{slug}/pulls", json={"title": title, "body": body, "head": branch(root), "base": base})
    if r.status_code >= 300:
        raise GitError(f"GitHub said {r.status_code}: {r.text[:200]}")
    return r.json()["html_url"]


async def create_github_repo(root: Path, name: str, private: bool = True) -> str:
    """Create a repo on the signed-in user's GitHub account and set it as 'origin'."""
    tok = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if not tok:
        raise GitError("add a GitHub token in Settings first")
    headers = {"Authorization": f"Bearer {tok}", "Accept": "application/vnd.github+json"}
    async with httpx.AsyncClient(timeout=30, headers=headers) as c:
        r = await c.post("https://api.github.com/user/repos", json={"name": name, "private": private})
    if r.status_code >= 300:
        raise GitError(f"GitHub said {r.status_code}: {r.json().get('message', r.text[:200]) if r.headers.get('content-type', '').startswith('application/json') else r.text[:200]}")
    url = r.json()["clone_url"]
    ensure_repo(root)
    git(root, "remote", "remove", "origin", check=False)
    git(root, "remote", "add", "origin", url)
    return r.json()["html_url"]
