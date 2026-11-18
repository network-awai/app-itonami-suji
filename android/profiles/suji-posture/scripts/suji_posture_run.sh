#!/bin/sh
# Solves one posture task on this host and writes an unreviewed candidate.
# SUJI_REPO: checkout of network-awai/app-itonami-suji where `python3 -I android/build.py` has run.
# SUJI_TASK: task JSON (default: android/profiles/tasks/laptop-on-lap.json).
set -eu
repo="${SUJI_REPO:?set SUJI_REPO to the app-itonami-suji checkout}"
task="${SUJI_TASK:-$repo/android/profiles/tasks/laptop-on-lap.json}"
out="$repo/.agent-output/suji-posture-runs/$(date -u +%Y%m%dT%H%M%SZ)"
node "$repo/android/.build/host/host.js" run "$task" "$out"
