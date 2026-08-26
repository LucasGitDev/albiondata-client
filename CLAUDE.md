
<!-- BACKLOG.MD GUIDELINES START -->
<!-- backlog.md-instructions-version: 1.50.1 -->
<CRITICAL_INSTRUCTION>

## Backlog.md Workflow

This project uses Backlog.md for task and project management.

**For every user request in this project, run `backlog instructions overview` before answering or taking action.**

Use the overview to decide whether to search, read, create, or update Backlog tasks.

Before task lifecycle actions, read the matching detailed guide:
- `backlog instructions task-creation` before creating or splitting tasks
- `backlog instructions task-execution` before planning, changing status or assignee, adding a plan or implementation notes, or implementing task work
- `backlog instructions task-finalization` before checking acceptance criteria, writing final summaries, or moving tasks to terminal statuses

Use `backlog <command> --help` before running unfamiliar commands. Help shows options, fields, and examples.

Do not edit Backlog task, draft, document, decision, or milestone markdown files directly. Use the `backlog` CLI so metadata, relationships, and history stay consistent.

</CRITICAL_INSTRUCTION>
<!-- BACKLOG.MD GUIDELINES END -->

# Project: albiondata-client → Wails desktop rebuild

## Scope

Fork of albiondata-client rebuilt as native desktop app (Wails v2).
Core packet capture + parsing stays intact; work is focused on:
- Wails entrypoint and app shell
- UI layer: **React + shadcn/ui** (replacing CLI/systray with windowed app)
- Auth, config, and logging made dynamic (runtime, not static flags)
- Any integration points the new UI needs

**Target platforms: Windows, macOS, Linux** — all three from the start.
UI/packaging/build decisions must work on all three. No platform-only shortcuts.

## Implementation priority

1. **UI + packet capture working** (Wails scaffold → capture binding → capture UI + live logs)
2. **OAuth Google login** (uploader can be mocked during phase 1)
3. **Everything else** (settings persistence, auth header, auto-updater handling)

Login is intentionally last. The HTTP uploader can be mocked so capture flow is
validated end-to-end before auth is wired.

## Task workflow

All non-trivial work must exist as a Backlog task before implementation starts.
"Non-trivial" = anything requiring design decisions, touching multiple files, or
that should be reviewable as a unit of work.
Mechanical one-liners (rename, typo, obvious fmt fix) can skip task creation.

Task granularity: **small and incremental**. One task = one reviewable slice.
Never create a task like "rewrite X module" — break it into steps.

Board statuses: To Do → In Progress → In Review → Done.
Move to "In Review" before asking user to review. Move to "Done" only after
master passes quality gate post-merge (see Definition of Done below).

## Worktree workflow

Each task gets its own branch and git worktree.
Branch naming: `task/<id>-short-slug` (e.g. `task/1-wails-scaffold`)
Worktree path convention: `../albiondata-client-task-<id>` (sibling of main worktree).

Never commit task work directly to master. Always via PR from task branch.

## Definition of Done

A task is Done only when ALL of the following pass, in order:

1. **Scope respected** — no files outside task scope modified without explicit note
2. **Quality gate passes on task branch** — `make check` exits 0 (see Quality gate)
3. **Manual verification passes** — every task with UI or user-facing behavior must
   have 1–3 manual test steps written in the task; agent verifies them before marking done
4. **Architecture decisions documented** — any non-obvious approach written in task
   implementation notes or as a backlog decision (`backlog decision create "..."`)
5. **Branch merged to master via PR**
6. **Quality gate passes on master after merge** — `make check` exits 0 on master;
   only then mark task Done in backlog

## Quality gate

Single command: `make check` → runs `scripts/check.sh`.

What it runs:
- Go: `go build ./...`, `go vet ./...`, `go test ./...`
- Go lint: `golangci-lint run` if installed; skipped with warning if not
- Frontend (when `frontend/` exists): `npm ci`, `npm run lint`, `tsc --noEmit`, `npm run build`
- Wails smoke test: `wails build` for native platform (if wails CLI installed)

Cross-platform full build (win + linux + darwin) is a release-time gate, not per-task.

## Core protection

**Do not modify** `client/`, `lib/` (packet capture, Photon parser, routing, upload pipeline)
unless the active task explicitly requires it.
If a task touches core, flag it in the task's implementation notes before changing code.

## Documentation standard

Backlog.md is the canonical documentation system for this project.
Do NOT create standalone markdown files for decisions, guides, or specs.

| What to document | Command |
|-----------------|---------|
| Architecture decision (any scope) | `backlog decision create "title"` |
| Technical guide or runbook | `backlog doc create "title" -t guide` |
| Feature/API specification | `backlog doc create "title" -t specification` |
| Platform setup or README-style doc | `backlog doc create "title" -t readme` |
| Implementation notes for a task | `backlog task edit TASK-X --append-notes "..."` |
| Final task summary | `backlog task edit TASK-X --final-summary "..."` |

**Rules:**
- Any non-obvious architectural choice → `backlog decision create` before implementing
- Any platform-specific behavior (Android, iOS, macOS permissions, VPN) → `backlog doc create -t guide`
- Any API or data contract → `backlog doc create -t specification`
- NEVER create `.md` files directly in the repo for documentation purposes
- CLAUDE.md is the only exception — it holds harness instructions, not project docs

Do not implement silently. If you pick a non-obvious approach, write it down in backlog before writing code.

## Multi-Agent Harness

When multiple agents run in parallel, these rules are mandatory. Prose instructions without enforcement — an agent that ignores them causes merge conflicts and lost work.

### Task claiming (mutex)

Before an Orchestrator spawns an Implementer for a task, it MUST run:
```bash
backlog task edit TASK-X --status "In Progress"
```
This is the distributed lock. An Implementer's first action is to verify the task is "In Progress" — if not, abort and report back.

Never move a task to "In Progress" speculatively. Claim only when an agent is about to start work.

### Agent roles

| Role | Writes code? | Writes to backlog? | Output |
|------|--------------|--------------------|--------|
| Orchestrator | No | Status transitions only | Spawn decisions |
| Spike/Researcher | No | `backlog decision create`, task notes | Decision doc |
| Implementer | Yes (worktree only) | Task notes | PR + `make check` green |
| Reviewer | No | Task notes (findings) | LGTM or blockers |
| Security Auditor | No | Task notes | Findings with severity |

### Worktree isolation

Implementer operates ONLY inside its assigned worktree (`../albiondata-client-task-<id>`).
Never write to the main worktree or another task's worktree.
Never push directly to master.

### Signaling done

1. Implementer: run `make check` → pass → open PR → `backlog task edit --status "In Review"` → stop
2. If `make check` fails: fix and retry, max 3 attempts, then move task back to "To Do" with blocker note
3. Reviewer: findings → if approved, comment LGTM on PR → Orchestrator merges
4. After merge: Orchestrator runs `make check` on master → if pass, moves task to Done

### Parallelism boundaries

Before spawning two Implementers concurrently, Orchestrator MUST verify no file overlap:
```bash
git -C ../albiondata-client-task-A diff --name-only master > /tmp/scope-a.txt
git -C ../albiondata-client-task-B diff --name-only master > /tmp/scope-b.txt
comm -12 <(sort /tmp/scope-a.txt) <(sort /tmp/scope-b.txt)
# Non-empty = conflict → serialize
```

**Always serialize** tasks touching: `go.mod`, `go.work`, `AndroidManifest.xml`, `Info.plist`, exported types in `collector/`.

### Escalation

Any agent blocked for >2 tool-call cycles on the same problem: stop, move task to "To Do", add implementation note with blocker description, report to Orchestrator. Do not spin.

### Security Auditor trigger

Spawn Security Auditor (read-only) on any task with scope touching: VPN service, OS permissions, auth tokens, network trust boundaries, file storage on device. CRITICAL findings block merge.

## Commits and branches

Conventional Commits. Format: `type(scope): description`
Types: feat, fix, refactor, chore, docs, test
Scopes: wails, ui, config, auth, log, core, build, ci

No `Co-Authored-By` trailers. No AI attribution in commits or PRs.
