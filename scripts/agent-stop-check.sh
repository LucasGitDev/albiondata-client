#!/usr/bin/env bash
# Runs on every Claude Code Stop event (project hook).
# Warns if any task is "In Progress" without an open PR.

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if ! command -v backlog &>/dev/null; then
  exit 0
fi

if ! command -v gh &>/dev/null; then
  exit 0
fi

cd "$ROOT"

backlog task list --status "In Progress" --plain 2>/dev/null | grep -oE 'TASK-[0-9]+(\.[0-9]+)?' | while read -r task_id; do
  slug=$(echo "$task_id" | tr '[:upper:]' '[:lower:]')
  pr=$(gh pr list --search "head:task/${slug}" --json number --jq '.[0].number' 2>/dev/null)
  if [ -z "$pr" ]; then
    echo "⚠ ${task_id} is In Progress but has no open PR — did you forget to open one or update status?"
  fi
done
