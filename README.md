# STRIX

An autonomous terminal coding agent that runs on open and budget models (Qwen, Kimi, DeepSeek, Gemini) through
OpenRouter, Google AI Studio, or local servers like Ollama. It reads your repo, edits across files, runs your tests,
and can commit, push and open a pull request.

## Quick start

```bash
python -m venv .venv && source .venv/bin/activate
pip install -e .

strix init                 # writes strix.yaml, STRIX.md and .env
# put OPENROUTER_API_KEY=... in .env  (free key: https://openrouter.ai/keys)
strix doctor               # checks config and keys

cd your-project
strix                      # opens the TUI in the current folder
strix --repo owner/name    # or clone a GitHub repo into ./workspace and work there
strix run "add JWT auth with tests" --branch strix/jwt --pr    # headless, ends with a PR
```

`GITHUB_TOKEN` (repo scope) is only needed to clone private repos, push and open PRs. It is sent as a one-off HTTP
header and never written to `.git/config`, and it is stripped from the environment of commands the agent runs.

## On your phone (Android + Termux, no PC needed)

1. Install **Termux from F-Droid** (not the Play Store).
2. Paste: `pkg install -y curl && curl -fsSL https://raw.githubusercontent.com/mohsen111-ai/StrixAi/ccr-c1097969-w4w5qy/scripts/termux-install.sh | bash`
3. Run `strix web` and open the printed link in Chrome. Add your free OpenRouter key in the page (⚙ Settings).

The web UI is mobile-first: chat, plan, files, git, and tap-to-allow permission prompts. It listens on `localhost` only and
requires an access token (the link contains it; it is remembered in a cookie). No extra dependencies beyond the TUI's.
`strix web --port 8010` picks another port. Projects live in `~/strix-projects`.

## How it works

```
TUI (Textual)  ->  Agent loop  ->  Router (roles -> fallback chains)  ->  OpenAI-compatible providers
                      |
                  Tools: read_file list_dir glob grep write_file edit_file bash todo
```

1. **Plan.** For tasks of 8+ words the `planner` role writes a short numbered plan (no tools).
2. **Loop.** The `coder` role calls tools until the task is done. Each call is validated, repaired when the JSON is
   slightly broken, permission-checked, executed, and the result is fed back.
3. **Verify.** The system prompt tells the model to run tests/linters and fix failures. Python and JSON files get an
   automatic syntax check after every write.
4. **Fall back.** On 429/5xx/timeouts the router moves to the next model in the role's chain (with a cooldown). After
   3 consecutive bad tool calls the agent switches model too.
5. **Compact.** Past ~70% of `context_tokens`, old tool output is trimmed, then older turns are summarised by the `fast` role.

### Roles (strix.yaml)

| role | used for |
|---|---|
| `planner` | up-front plan for bigger tasks |
| `coder` | the tool-using edit loop |
| `fast` | commit messages and context summaries |
| `longctx` | reserved for very large reads |

Model specs are `provider:model-id`. List what is live right now with `strix models --free` or `strix models qwen kimi`.
Free endpoints are rate limited and may log prompts: keep private code on paid or local models (`ollama:` provider).

### Permission modes

| mode | writes | shell |
|---|---|---|
| `ask` | asks | asks |
| `auto-edit` (default) | allowed | asks, except a safe list (`pytest`, `git status`, `npm test`, ...) |
| `yolo` | allowed | allowed, except obviously destructive commands |

All file access is confined to the workspace, `.git` is read-only, and `/undo` reverts the last file edit.
The shell is **not** sandboxed beyond that. For untrusted repos run Strix inside a container.

## TUI commands

`/mode` `/plan` `/models` `/diff` `/undo` `/new` `/clear` `/branch NAME` `/commit` `/push` `/pr` `/help` `/quit`.
`esc` stops a run, `ctrl+c` twice quits.

## Project notes for the agent

Put build/test commands and conventions in `STRIX.md` (or `AGENTS.md` / `CLAUDE.md`) at the repo root. They are added to the system prompt.

## Design

`docs/design-board.html` holds the visual design (palette, wallpapers, TUI mockups, motion, owl emblem).
The theme lives in `strix/strix.tcss`.

## Develop

```bash
pip install -e '.[dev]' && pytest
```
