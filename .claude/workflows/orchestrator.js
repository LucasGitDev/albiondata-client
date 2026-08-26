export const meta = {
  name: 'orchestrator',
  description: 'Coordinate multi-agent task execution: scan backlog, run spikes in parallel, implement, review, finalize',
  phases: [
    { title: 'Scan', detail: 'Read backlog state and plan execution' },
    { title: 'Spikes', detail: 'Run research agents in parallel' },
    { title: 'Implement', detail: 'Run implementer agents (parallelism-safe)' },
    { title: 'Review', detail: 'Run reviewer + optional security auditor per task' },
    { title: 'Merge', detail: 'Wait for user merge approval, verify master' },
    { title: 'Finalize', detail: 'Check AC/DoD, record decisions, write final summary, mark Done' },
    { title: 'Summary', detail: 'Report results' },
  ],
}

// ─── Schemas ────────────────────────────────────────────────────────────────

const SCAN_SCHEMA = {
  type: 'object',
  properties: {
    eligible_spikes: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          title: { type: 'string' },
          description: { type: 'string' },
        },
        required: ['id', 'title', 'description'],
      },
    },
    eligible_tasks: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          title: { type: 'string' },
          description: { type: 'string' },
          file_scope_hint: { type: 'string' },
          needs_security_audit: { type: 'boolean' },
        },
        required: ['id', 'title', 'description', 'file_scope_hint', 'needs_security_audit'],
      },
    },
    blocked_tasks: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          blocked_by: { type: 'string' },
        },
        required: ['id', 'blocked_by'],
      },
    },
  },
  required: ['eligible_spikes', 'eligible_tasks', 'blocked_tasks'],
}

const OVERLAP_SCHEMA = {
  type: 'object',
  properties: {
    groups: {
      type: 'array',
      description: 'Groups of task IDs that can run in parallel (no file overlap within group)',
      items: {
        type: 'array',
        items: { type: 'string' },
      },
    },
    reasoning: { type: 'string' },
  },
  required: ['groups', 'reasoning'],
}

const SPIKE_RESULT_SCHEMA = {
  type: 'object',
  properties: {
    task_id: { type: 'string' },
    decision_created: { type: 'boolean' },
    decision_title: { type: 'string' },
    summary: { type: 'string' },
    blockers: { type: 'string' },
    status: { type: 'string', enum: ['done', 'blocked'] },
  },
  required: ['task_id', 'decision_created', 'summary', 'status'],
}

const IMPL_RESULT_SCHEMA = {
  type: 'object',
  properties: {
    task_id: { type: 'string' },
    pr_number: { type: 'string' },
    pr_url: { type: 'string' },
    make_check_passed: { type: 'boolean' },
    status: { type: 'string', enum: ['in_review', 'blocked', 'escalate'] },
    blocker_note: { type: 'string' },
  },
  required: ['task_id', 'make_check_passed', 'status'],
}

const REVIEW_RESULT_SCHEMA = {
  type: 'object',
  properties: {
    task_id: { type: 'string' },
    verdict: { type: 'string', enum: ['LGTM', 'BLOCKED'] },
    findings: { type: 'string' },
    security_verdict: { type: 'string', enum: ['PASS', 'BLOCKED', 'SKIPPED'] },
    security_findings: { type: 'string' },
    merge_ready: { type: 'boolean' },
  },
  required: ['task_id', 'verdict', 'merge_ready'],
}

// ─── Helpers ────────────────────────────────────────────────────────────────

const ROOT = '/Users/lucas/dev/lucas/side/albiondata-client'

function worktreePath(taskId) {
  return `${ROOT}/../albiondata-client-task-${taskId.toLowerCase()}`
}

function branchName(taskId, title) {
  const slug = title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
    .slice(0, 40)
  return `task/${taskId.toLowerCase()}-${slug}`
}

// ─── Phase 1: Scan ──────────────────────────────────────────────────────────

phase('Scan')

const scan = await agent(
  `You are scanning the albiondata-client backlog to determine what can run next.

Working directory: ${ROOT}

Run these commands and analyze the output:
1. \`backlog task list --plain\` — get all tasks and statuses
2. For each "To Do" task: \`backlog task view TASK-X --plain\` to read description and dependencies
3. For each "In Progress" task: check if it has an open PR via \`gh pr list --search "head:task/" --json number,headRefName,state\`

Determine:
- eligible_spikes: To Do tasks with type:spike where parent spike dependencies are Done
- eligible_tasks: To Do non-spike tasks where all spike/parent dependencies are Done
- blocked_tasks: To Do tasks still waiting on an incomplete dependency

A task is blocked if its parent epic has an incomplete spike subtask that it depends on.
Check task descriptions for explicit "depends on TASK-X" language.

Security audit needed for tasks mentioning: VPN, permissions, auth, token, network, storage, capture.

Return structured JSON.`,
  { label: 'scan:backlog', phase: 'Scan', schema: SCAN_SCHEMA, agentType: 'orchestrator' }
)

if (!scan) {
  log('Scan failed — aborting')
  return { error: 'scan failed' }
}

log(`Scan complete: ${scan.eligible_spikes.length} spikes, ${scan.eligible_tasks.length} tasks, ${scan.blocked_tasks.length} blocked`)

if (scan.blocked_tasks.length > 0) {
  log(`Blocked: ${scan.blocked_tasks.map(t => `${t.id} (waiting on ${t.blocked_by})`).join(', ')}`)
}

if (scan.eligible_spikes.length === 0 && scan.eligible_tasks.length === 0) {
  log('Nothing eligible to run. All tasks are either Done, In Progress, or blocked.')
  return { eligible_spikes: [], eligible_tasks: [], blocked_tasks: scan.blocked_tasks }
}

// ─── Phase 2: Spikes (parallel) ─────────────────────────────────────────────

phase('Spikes')

let spikeResults = []

if (scan.eligible_spikes.length > 0) {
  log(`Running ${scan.eligible_spikes.length} spike(s) in parallel`)

  spikeResults = await parallel(
    scan.eligible_spikes.map(spike => async () => {
      log(`Claiming ${spike.id}`)

      // Claim the task
      await agent(
        `Run: backlog task edit ${spike.id} --status "In Progress"`,
        { label: `claim:${spike.id}`, phase: 'Spikes', agentType: 'orchestrator', model: 'haiku' }
      )

      // Run the spike
      return agent(
        `You are a Spike/Research agent executing ${spike.id}: ${spike.title}

Task: ${spike.id}
Title: ${spike.title}
Description: ${spike.description}

Working directory: ${ROOT}

Steps:
1. Read the full task: \`backlog task view ${spike.id} --plain\`
2. Research all acceptance criteria in the task
3. Create a backlog decision: \`backlog decision create "your-decision-title"\`
4. Add implementation notes: \`backlog task edit ${spike.id} --notes "your notes here"\`
5. Move to In Review: \`backlog task edit ${spike.id} --status "In Review"\`

Research using web search and reading existing code as needed.
Return structured result with what you found.`,
        {
          label: `spike:${spike.id}`,
          phase: 'Spikes',
          schema: SPIKE_RESULT_SCHEMA,
          agentType: 'spike',
        }
      )
    })
  )

  spikeResults = spikeResults.filter(Boolean)
  const done = spikeResults.filter(r => r.status === 'done')
  const blocked = spikeResults.filter(r => r.status === 'blocked')
  log(`Spikes: ${done.length} done, ${blocked.length} blocked`)

  if (blocked.length > 0) {
    for (const r of blocked) {
      log(`Spike ${r.task_id} blocked: ${r.blockers}`)
      await agent(
        `Run: backlog task edit ${r.task_id} --status "To Do" && backlog task edit ${r.task_id} --notes "Blocker from spike: ${r.blockers}"`,
        { label: `reset:${r.task_id}`, phase: 'Spikes', agentType: 'orchestrator', model: 'haiku' }
      )
    }
  }

  // Move done spikes to In Review (finalizer will move to Done)
  for (const r of done) {
    await agent(
      `Run: backlog task edit ${r.task_id} --status "In Review"`,
      { label: `mark-review:${r.task_id}`, phase: 'Spikes', agentType: 'orchestrator', model: 'haiku' }
    )
  }
}

// ─── Phase 3: Implement ─────────────────────────────────────────────────────

phase('Implement')

let implResults = []

if (scan.eligible_tasks.length > 0) {
  // Determine parallelism groups via overlap check
  const overlapCheck = await agent(
    `You are checking file overlap between tasks to determine safe parallelism.

Tasks to analyze:
${scan.eligible_tasks.map(t => `- ${t.id}: ${t.title} (scope hint: ${t.file_scope_hint})`).join('\n')}

Files that ALWAYS serialize (never parallelize tasks touching these):
- go.mod, go.work
- AndroidManifest.xml, Info.plist
- Any exported types in collector/ package
- Any shared data model or API interface file

Based on scope hints and task descriptions, group tasks that can safely run in parallel.
Tasks with overlapping file scopes MUST be in separate groups (serialized).
Return groups as arrays of task IDs. Each group runs in parallel; groups run sequentially.`,
    { label: 'overlap:check', phase: 'Implement', schema: OVERLAP_SCHEMA, agentType: 'orchestrator' }
  )

  const groups = overlapCheck ? overlapCheck.groups : [scan.eligible_tasks.map(t => t.id)]
  log(`Parallelism: ${groups.length} group(s) — ${groups.map(g => g.join('+')).join(' → ')}`)

  for (const group of groups) {
    const groupTasks = group
      .map(id => scan.eligible_tasks.find(t => t.id === id))
      .filter(Boolean)

    const groupResults = await parallel(
      groupTasks.map(task => async () => {
        const branch = branchName(task.id, task.title)
        const worktree = worktreePath(task.id)

        log(`Claiming ${task.id}`)
        await agent(
          `Run: backlog task edit ${task.id} --status "In Progress"`,
          { label: `claim:${task.id}`, phase: 'Implement', agentType: 'orchestrator', model: 'haiku' }
        )

        // Setup worktree
        await agent(
          `Setup git worktree for task ${task.id}.

Run these commands in order:
1. cd ${ROOT}
2. git worktree add ${worktree} -b ${branch} 2>/dev/null || git worktree add ${worktree} ${branch}
3. Verify: ls ${worktree}

Report: worktree path and branch name.`,
          { label: `worktree:${task.id}`, phase: 'Implement', agentType: 'orchestrator', model: 'haiku' }
        )

        // Run implementer
        return agent(
          `You are an Implementer agent executing ${task.id}: ${task.title}

Task ID: ${task.id}
Branch: ${branch}
Worktree: ${worktree}
Main repo: ${ROOT}

Your working directory is the worktree: ${worktree}

Steps:
1. Read the full task: \`backlog task view ${task.id} --plain\` (run from ${ROOT})
2. Verify you are in the worktree: \`pwd\` should be ${worktree}
3. Implement all acceptance criteria — stay within task scope
4. Do NOT modify client/ or lib/ unless task explicitly requires it
5. Run quality gate: \`cd ${ROOT} && make check\` — fix issues, max 3 attempts
6. Open PR: \`gh pr create --title "type(scope): ${task.title} (${task.id})" --body "$(cat ${ROOT}/.github/pull_request_template.md)"\`
7. Move to In Review: \`backlog task edit ${task.id} --status "In Review"\` (run from ${ROOT})

If make check fails 3 times: move task to "To Do" with blocker note and return status: "blocked".
Commit format: Conventional Commits, no Co-Authored-By trailers.

Return structured result.`,
          {
            label: `impl:${task.id}`,
            phase: 'Implement',
            schema: IMPL_RESULT_SCHEMA,
            agentType: 'implementer',
          }
        )
      })
    )

    implResults = implResults.concat(groupResults.filter(Boolean))
  }

  const implDone = implResults.filter(r => r.status === 'in_review')
  const implBlocked = implResults.filter(r => r.status !== 'in_review')
  log(`Implement: ${implDone.length} in review, ${implBlocked.length} blocked/escalated`)
}

// ─── Phase 4: Review ────────────────────────────────────────────────────────

phase('Review')

const inReview = implResults.filter(r => r.status === 'in_review' && r.pr_url)

const reviewResults = await parallel(
  inReview.map(impl => async () => {
    const task = scan.eligible_tasks.find(t => t.id === impl.task_id)

    // Code review
    const review = await agent(
      `You are a Reviewer agent for ${impl.task_id}.

PR: ${impl.pr_url || 'find via gh pr list'}
Task: ${impl.task_id}
Main repo: ${ROOT}

Steps:
1. Read task: \`backlog task view ${impl.task_id} --plain\`
2. Get diff: \`gh pr diff ${impl.pr_number || impl.task_id}\`
3. Review against acceptance criteria, scope, correctness, security basics
4. Write findings to task: \`backlog task edit ${impl.task_id} --notes "Review: <findings>"\`
5. Comment on PR with verdict

Output format for findings: path:line: 🔴/🟠/🟡/🔵 SEVERITY: problem. fix.
Verdict: LGTM or BLOCKED: reason`,
      {
        label: `review:${impl.task_id}`,
        phase: 'Review',
        schema: REVIEW_RESULT_SCHEMA,
        agentType: 'reviewer',
      }
    )

    if (!review) return null

    // Security audit if needed
    let secVerdict = 'SKIPPED'
    let secFindings = ''

    if (task && task.needs_security_audit) {
      log(`Security audit triggered for ${impl.task_id}`)
      const secAudit = await agent(
        `You are a Security Auditor for ${impl.task_id}.

PR: ${impl.pr_url || 'find via gh pr list'}
Main repo: ${ROOT}

Steps:
1. Get diff: \`gh pr diff ${impl.pr_number || impl.task_id}\`
2. Run security audit checklist (auth tokens, network TLS, permissions, VPN, storage)
3. Write findings: \`backlog task edit ${impl.task_id} --notes "Security audit: <verdict> — <findings>"\`

Output: SECURITY: PASS or SECURITY: BLOCKED — <findings>`,
        {
          label: `security:${impl.task_id}`,
          phase: 'Review',
          schema: REVIEW_RESULT_SCHEMA,
          agentType: 'security-auditor',
        }
      )

      if (secAudit) {
        secVerdict = secAudit.security_verdict || secAudit.verdict
        secFindings = secAudit.security_findings || secAudit.findings || ''
      }
    }

    const mergeReady = review.verdict === 'LGTM' && secVerdict !== 'BLOCKED'

    return {
      task_id: impl.task_id,
      pr_url: impl.pr_url,
      pr_number: impl.pr_number,
      verdict: review.verdict,
      findings: review.findings,
      security_verdict: secVerdict,
      security_findings: secFindings,
      merge_ready: mergeReady,
    }
  })
)

// ─── Phase 5: Merge gate ────────────────────────────────────────────────────

phase('Merge')

const reviewed = reviewResults.filter(Boolean)
const mergeReady = reviewed.filter(r => r.merge_ready)
const reviewBlocked = reviewed.filter(r => !r.merge_ready)

log(`Review complete: ${mergeReady.length} ready to merge, ${reviewBlocked.length} blocked`)

// Orchestrator cannot push to master — user must approve merge.
// We poll until merged PRs appear on master, then run finalization.
let mergedTaskIds = []

if (mergeReady.length > 0) {
  log(`Waiting for user to merge ${mergeReady.length} PR(s)...`)
  log(`PRs ready:\n${mergeReady.map(r => `  ${r.task_id}: ${r.pr_url}`).join('\n')}`)

  // Poll until all ready PRs are merged or budget runs out
  const maxPolls = 12 // ~12 checks
  for (let i = 0; i < maxPolls; i++) {
    const mergeStatus = await agent(
      `Check if these PRs are merged into master:
${mergeReady.map(r => `- PR: ${r.pr_url} (${r.task_id})`).join('\n')}

For each PR run: gh pr view <number> --json state,mergedAt --jq '{state,mergedAt}'
Also run: make check on master after any merge: cd ${ROOT} && git pull && make check

Return JSON: { merged: ["TASK-X", ...], pending: ["TASK-Y", ...], make_check_passed: true/false }`,
      {
        label: `merge:poll-${i}`,
        phase: 'Merge',
        schema: {
          type: 'object',
          properties: {
            merged: { type: 'array', items: { type: 'string' } },
            pending: { type: 'array', items: { type: 'string' } },
            make_check_passed: { type: 'boolean' },
          },
          required: ['merged', 'pending', 'make_check_passed'],
        },
        agentType: 'orchestrator',
        model: 'haiku',
      }
    )

    if (mergeStatus) {
      mergedTaskIds = mergeStatus.merged
      if (!mergeStatus.make_check_passed && mergeStatus.merged.length > 0) {
        log(`WARNING: make check failed on master after merge — tasks NOT moving to Done`)
      }
      if (mergeStatus.pending.length === 0 || mergedTaskIds.length === mergeReady.length) {
        break
      }
    }
  }
}

// ─── Phase 6: Finalize ──────────────────────────────────────────────────────

phase('Finalize')

const toFinalize = [
  ...spikeResults.filter(r => r.status === 'done').map(r => r.task_id),
  ...mergedTaskIds,
]

const finalizationResults = await parallel(
  toFinalize.map(taskId => async () =>
    agent(
      `You are a Finalizer agent closing task ${taskId} after successful merge and make check on master.

Task ID: ${taskId}
Main repo: ${ROOT}

Follow the finalization guide exactly:
1. backlog instructions task-finalization
2. backlog task view ${taskId} --plain
3. Verify each acceptance criterion with objective evidence (run commands, check output)
4. Check AC items: backlog task edit ${taskId} --check-ac <n> (only with evidence)
5. Check DoD items: backlog task edit ${taskId} --check-dod <n>
6. Create any missing backlog decisions: backlog decision create "..."
7. Append final notes: backlog task edit ${taskId} --append-notes "..."
8. Write final summary: backlog task edit ${taskId} --final-summary "..."
9. Move to Done: backlog task edit ${taskId} --status "Done"

Be thorough — this is the permanent record of the task.`,
      {
        label: `finalize:${taskId}`,
        phase: 'Finalize',
        agentType: 'finalizer',
      }
    )
  )
)

log(`Finalized ${finalizationResults.filter(Boolean).length}/${toFinalize.length} tasks`)

// ─── Phase 7: Summary ───────────────────────────────────────────────────────

phase('Summary')

return {
  spikes: {
    ran: spikeResults.length,
    done: spikeResults.filter(r => r.status === 'done').map(r => r.task_id),
    blocked: spikeResults.filter(r => r.status === 'blocked').map(r => r.task_id),
  },
  implementation: {
    ran: implResults.length,
    in_review: implResults.filter(r => r.status === 'in_review').map(r => r.task_id),
    blocked: implResults.filter(r => r.status !== 'in_review').map(r => r.task_id),
  },
  review: {
    merge_ready: mergeReady.map(r => ({
      task_id: r.task_id,
      pr_url: r.pr_url,
      verdict: r.verdict,
      security_verdict: r.security_verdict,
    })),
    blocked: reviewBlocked.map(r => ({
      task_id: r.task_id,
      verdict: r.verdict,
      findings: r.findings,
      security_verdict: r.security_verdict,
      security_findings: r.security_findings,
    })),
  },
  finalized: toFinalize,
  blocked_tasks: scan.blocked_tasks,
  action_required: reviewBlocked.length > 0
    ? `Fix review blockers:\n${reviewBlocked.map(r => `  ${r.task_id}: ${r.findings}`).join('\n')}`
    : mergeReady.length > 0 && mergedTaskIds.length < mergeReady.length
      ? `Still pending merge:\n${mergeReady.filter(r => !mergedTaskIds.includes(r.task_id)).map(r => `  ${r.task_id}: ${r.pr_url}`).join('\n')}`
      : 'All tasks finalized.',
}
