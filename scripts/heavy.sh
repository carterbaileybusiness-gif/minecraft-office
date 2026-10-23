#!/bin/bash
# Run one memory-heavy command (Gradle, a Minecraft server, swift build) at a time on this 8 GB Mac.
# Usage: scripts/heavy.sh <command...>   — waits for the lock, refuses if disk is under 4 GiB free.
lock="$HOME/Library/Caches/minecraft-office-heavy.lock"
free_kb=$(df -k "$HOME" | awk 'NR==2 {print $4}')
if [ "$free_kb" -lt 4194304 ]; then echo "heavy.sh: under 4 GiB free disk — refusing" >&2; exit 75; fi
until mkdir "$lock" 2>/dev/null; do
  holder=$(cat "$lock/pid" 2>/dev/null)
  if [ -n "$holder" ] && ! kill -0 "$holder" 2>/dev/null; then rm -rf "$lock"; continue; fi
  sleep 5
done
echo $$ > "$lock/pid"
trap 'rm -rf "$lock"' EXIT
"$@"
