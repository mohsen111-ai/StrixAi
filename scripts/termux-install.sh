#!/data/data/com.termux/files/usr/bin/bash
# One-paste installer for Strix on Android (Termux). Safe to run again to update.
set -e
BRANCH="${STRIX_BRANCH:-ccr-c1097969-w4w5qy}"
pkg update -y && pkg install -y python git
if [ -d "$HOME/StrixAi/.git" ]; then
  git -C "$HOME/StrixAi" pull -q origin "$BRANCH" || true
else
  git clone -b "$BRANCH" https://github.com/mohsen111-ai/StrixAi "$HOME/StrixAi"
fi
cd "$HOME/StrixAi" && pip install -q -e .
mkdir -p "$HOME/strix-projects"
cat <<'MSG'

  Strix is installed.

  Start it any time with:      strix web
  Then open the link it prints in Chrome.
  Tip: run `termux-wake-lock` first so Android does not pause Strix when the screen turns off.

MSG
