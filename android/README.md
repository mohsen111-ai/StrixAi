# Strix for Android

A native Android coding agent. Point it at a GitHub repo, tell it what to build or fix, review the diff, and commit
or open a pull request. It runs on open and budget models (Qwen, Kimi, DeepSeek, Gemma, Gemini) through OpenRouter,
Google AI Studio, or any OpenAI-compatible endpoint. No PC, Termux or server needed.

## How it works

```
Compose UI  ->  SessionController  ->  Agent loop  ->  Router (roles -> fallback chains)  ->  OpenAI-compatible APIs
                      |                    |
                  Workspace  <----  Tools: list_dir read_file glob grep write_file edit_file delete_file move_file todo
                      |
                GitHub REST (zipball snapshot, git data API commit, pulls)
```

1. Opening a repo downloads it once as a zip into memory (one request, so grep is instant).
2. The agent's tools edit that in-memory copy. Nothing reaches GitHub yet. The agent cannot run code or tests.
3. You review the staged diff, then commit: one commit on a new branch (or the base branch), optionally a pull request.
4. Roles: `planner` (outline bigger tasks), `coder` (tool loop), `fast` (commit messages, context summaries). Each role is
   a fallback chain; rate limits, errors and repeated bad tool calls move to the next model.

Sessions, staged edits and the conversation survive app restarts. A foreground notification keeps long runs alive.

## Set up

1. Install the APK (allow "install unknown apps" for your browser or file manager).
2. Settings: paste a GitHub token (fine-grained: Contents + Pull requests read/write, or a classic `repo` token) and a
   model key. Tap Verify on each.
3. Pick a repo, pick a branch, describe the task.

Free OpenRouter models allow roughly 50 requests/day until you add a few dollars of credit (then ~1000/day). Adding a Gemini
key as well gives separate free quota. Free endpoints may log prompts: keep private code on paid or local models.

## Build

```bash
cd android
./gradlew testDebugUnitTest        # unit, integration and Robolectric UI tests (screenshots in app/build/screens)
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew connectedDebugAndroidTest  # needs an emulator or device
```

CI (`.github/workflows/android.yml`) runs the tests, lint, builds the APK as an artifact, and runs the instrumented
smoke tests on an API 34 emulator.

The APK is signed with `app/strix-sideload.keystore`. That key is committed on purpose: it is not a secret, it only makes every build
(local or CI) install over the previous one. Do not publish this app to a store with it.

## Fonts

Chakra Petch and JetBrains Mono, both SIL Open Font License 1.1 (from github.com/google/fonts).
