export const meta = {
  name: 'orchestrator',
  description: 'Continuous multi-agent orchestration: scan → PO consult → spikes+impl in parallel → review → finalize → loop',
  phases: [
    { title: 'Scan', detail: 'Read backlog state and classify tasks' },
    { title: 'PO', detail: 'Product Owner unblocks ambiguous spike questions' },
    { title: 'Spikes', detail: 'Run research agents in parallel' },
    { title: 'Implement', detail: 'Run implementer agents (parallelism-safe)' },
    { title: 'Review', detail: 'Reviewer + optional security auditor per task' },
    { title: 'Merge', detail: 'Poll for user merges, verify master' },
    { title: 'Finalize', detail: 'AC/DoD check, decisions, final summary, mark Done' },
    { title: 'Summary', detail: 'Report results and remaining board state' },
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
          open_product_questions: { type: 'string', description: 'Unresolved product questions the PO should answer before spike starts. Empty string if none.' },
        },
        required: ['id', 'title', 'description', 'open_product_questions'],
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
    in_review_tasks: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          pr_url: { type: 'string' },
          pr_number: { type: 'string' },
          needs_security_audit: { type: 'boolean' },
        },
        required: ['id', 'needs_security_audit'],
      },
    },
  },
  required: ['eligible_spikes', 'eligible_tasks', 'blocked_tasks', 'in_review_tasks'],
}

const PO_RESULT_SCHEMA = {
  type: 'object',
  properties: {
    task_id: { type: 'string' },
    decisions_created: { type: 'array', items: { type: 'string' } },
    summary: { type: 'string' },
    unresolved: { type: 'string', description: 'Any questions still requiring user input. Empty if none.' },
  },
  required: ['task_id', 'decisions_created', 'summary'],
}

const OVERLAP_SCHEMA = {
  type: 'object',
  properties: {
    groups: {
      type: 'array',
      description: 'Groups of task IDs that can run in parallel (no file overlap within group). Groups run sequentially.',
      items: { type: 'array', items: { type: 'string' } },
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

// ─── State (accumulated across loop iterations) ──────────────────────────────

const allSpikeResults = []
const allImplResults = []
const allReviewResults = []
const allMergedTaskIds = []
const allFinalizedTaskIds = []
const seenTaskIds = new Set()

// ─── Main loop ──────────────────────────────────────────────────────────────
// Runs until: nothing eligible AND nothing in-review (everything blocked or done)

let iteration = 0
const MAX_ITERATIONS = 6

while (iteration < MAX_ITERATIONS) {
  iteration++
  log(`=== Iteration ${iteration} ===`)

  // ── Phase: Scan ────────────────────────────────────────────────────────────

  phase('Scan')

  const scan = await agent(
    `You are scanning the albiondata-client backlog to classify all tasks.

Working directory: ${ROOT}

Run in order:
1. \`backlog task list --plain\` — get all tasks and statuses
2. For each "To Do" task: \`backlog task view TASK-X --plain\` to read description and deps
3. \`gh pr list --json number,headRefName,url,state --jq '.[] | select(.state=="OPEN")'\` — open PRs

Classify:
- eligible_spikes: To Do tasks with [spike] prefix where all dependencies are Done
- eligible_tasks: To Do non-spike tasks where all deps (including spike deps) are Done
- blocked_tasks: To Do tasks waiting on an incomplete dep (list what they're waiting on)
- in_review_tasks: In Review tasks — include pr_url and pr_number if found via gh pr list

Security audit needed for tasks mentioning: VPN, permissions, auth, token, network, storage, capture.

For eligible_spikes: list any unresolved product questions (things the spike cannot answer without product direction).

Return structured JSON.`,
    { label: `scan:${iteration}`, phase: 'Scan', schema: SCAN_SCHEMA, agentType: 'general-purpose' }
  )

  if (!scan) {
    log('Scan failed — stopping loop')
    break
  }

  log(`Eligible: ${scan.eligible_spikes.length} spike(s), ${scan.eligible_tasks.length} task(s) | In Review: ${scan.in_review_tasks.length} | Blocked: ${scan.blocked_tasks.length}`)
  if (scan.blocked_tasks.length > 0) {
    log(`Blocked: ${scan.blocked_tasks.map(t => `${t.id}←${t.blocked_by}`).join(', ')}`)
  }

  // Stop condition: nothing to do this iteration
  const hasWork = scan.eligible_spikes.length > 0 || scan.eligible_tasks.length > 0 || scan.in_review_tasks.length > 0
  if (!hasWork) {
    log('Nothing eligible or in-review. Board is blocked or complete.')
    break
  }

  // Prevent re-running already-started tasks
  const freshSpikes = scan.eligible_spikes.filter(s => !seenTaskIds.has(s.id))
  const freshTasks = scan.eligible_tasks.filter(t => !seenTaskIds.has(t.id))
  const freshInReview = scan.in_review_tasks.filter(t => !seenTaskIds.has(t.id))

  freshSpikes.forEach(s => seenTaskIds.add(s.id))
  freshTasks.forEach(t => seenTaskIds.add(t.id))
  freshInReview.forEach(t => seenTaskIds.add(t.id))

  if (freshSpikes.length === 0 && freshTasks.length === 0 && freshInReview.length === 0) {
    log('No new work this iteration — waiting for external state change (merges). Stopping.')
    break
  }

  // ── Phase: PO (product questions on spikes) ────────────────────────────────

  const spikesWithQuestions = freshSpikes.filter(s => s.open_product_questions && s.open_product_questions.trim().length > 0)

  if (spikesWithQuestions.length > 0) {
    phase('PO')
    log(`PO consulting on ${spikesWithQuestions.length} spike(s) with open product questions`)

    await parallel(
      spikesWithQuestions.map(spike => async () => {
        const poResult = await agent(
          `You are a Product Owner agent for the albiondata-client project.

Task needing product direction: ${spike.id} — ${spike.title}
Open product questions: ${spike.open_product_questions}

Steps:
1. Read product context:
   - \`backlog doc view doc-4 --plain\`
   - \`backlog doc view doc-5 --plain\`
   - \`backlog doc view doc-6 --plain\`
   - \`backlog decision list --plain\`
2. Answer each open question with a concrete decision aligned with product goals
3. For each decision: \`backlog decision create "title" -s accepted\`
   Then write full content (options, rationale, chosen approach) to the returned file path
4. Add notes to task: \`backlog task edit ${spike.id} --notes "PO decisions: <summary of choices made>"\`

Product priorities (in order):
- Android-idiomatic > Go-idiomatic when there's a conflict
- MVP simplicity (works end-to-end) > completeness
- Private data security (token never plaintext) is non-negotiable
- Desktop parity: mobile replicates TASK-7 + TASK-9 behavior

Make concrete choices. Do not defer — "decide and note assumption" beats "wait for human".

Return structured result.`,
          {
            label: `po:${spike.id}`,
            phase: 'PO',
            schema: PO_RESULT_SCHEMA,
            agentType: 'general-purpose',
          }
        )

        if (poResult && poResult.unresolved && poResult.unresolved.trim().length > 0) {
          log(`PO: ${spike.id} still has unresolved questions requiring user: ${poResult.unresolved}`)
        }

        return poResult
      })
    )
  }

  // ── Phase: Spikes (parallel) ───────────────────────────────────────────────

  let iterSpikeResults = []

  if (freshSpikes.length > 0) {
    phase('Spikes')
    log(`Running ${freshSpikes.length} spike(s) in parallel`)

    iterSpikeResults = (await parallel(
      freshSpikes.map(spike => async () => {
        await agent(
          `Run: cd ${ROOT} && backlog task edit ${spike.id} --status "In Progress"`,
          { label: `claim:${spike.id}`, phase: 'Spikes', agentType: 'general-purpose' }
        )

        return agent(
          `You are a Spike/Research agent executing ${spike.id}: ${spike.title}

Working directory: ${ROOT}

Steps:
1. Read the full task: \`backlog task view ${spike.id} --plain\`
2. Read relevant product docs and prior decisions:
   - \`backlog doc view doc-4 --plain\`
   - \`backlog doc view doc-5 --plain\`
   - \`backlog doc view doc-6 --plain\`
   - \`backlog decision list --plain\`
3. Research ALL acceptance criteria in the task (web search, read code as needed)
4. Create a backlog decision: \`backlog decision create "title" -s accepted\`
   Write full content to returned file path (options evaluated, tradeoffs, chosen approach, rationale)
5. Add implementation notes: \`backlog task edit ${spike.id} --notes "Research findings: ..."\`
6. Move to In Review: \`backlog task edit ${spike.id} --status "In Review"\`

Rules:
- NEVER write or edit source code files
- NEVER commit anything
- If you hit a genuine blocker (API doesn't exist, OS restriction), flag it explicitly

Return structured result.`,
          {
            label: `spike:${spike.id}`,
            phase: 'Spikes',
            schema: SPIKE_RESULT_SCHEMA,
            agentType: 'general-purpose',
          }
        )
      })
    )).filter(Boolean)

    allSpikeResults.push(...iterSpikeResults)

    const spikesDone = iterSpikeResults.filter(r => r.status === 'done')
    const spikesBlocked = iterSpikeResults.filter(r => r.status === 'blocked')
    log(`Spikes: ${spikesDone.length} done, ${spikesBlocked.length} blocked`)

    for (const r of spikesBlocked) {
      await agent(
        `Run: cd ${ROOT} && backlog task edit ${r.task_id} --status "To Do" && backlog task edit ${r.task_id} --notes "Spike blocker: ${r.blockers}"`,
        { label: `reset:${r.task_id}`, phase: 'Spikes', agentType: 'general-purpose' }
      )
    }
  }

  // ── Phase: Implement (parallel groups) ────────────────────────────────────

  let iterImplResults = []

  if (freshTasks.length > 0) {
    phase('Implement')

    const overlapCheck = await agent(
      `Check file overlap between tasks to determine safe parallelism groups.

Tasks:
${freshTasks.map(t => `- ${t.id}: ${t.title} (scope: ${t.file_scope_hint})`).join('\n')}

Always serialize tasks touching: go.mod, go.work, AndroidManifest.xml, Info.plist, exported types in collector/
Group tasks with no file overlap together (parallel). Tasks with overlap in separate groups (sequential).

Return groups as arrays of task IDs.`,
      { label: `overlap:${iteration}`, phase: 'Implement', schema: OVERLAP_SCHEMA, agentType: 'general-purpose' }
    )

    const groups = overlapCheck ? overlapCheck.groups : [freshTasks.map(t => t.id)]
    log(`Parallelism: ${groups.map(g => g.join('+')).join(' → ')}`)

    for (const group of groups) {
      const groupTasks = group.map(id => freshTasks.find(t => t.id === id)).filter(Boolean)

      const groupResults = await parallel(
        groupTasks.map(task => async () => {
          const branch = branchName(task.id, task.title)
          const worktree = worktreePath(task.id)

          await agent(
            `Run: cd ${ROOT} && backlog task edit ${task.id} --status "In Progress"`,
            { label: `claim:${task.id}`, phase: 'Implement', agentType: 'general-purpose' }
          )

          await agent(
            `Setup git worktree for ${task.id}.
cd ${ROOT}
git worktree add ${worktree} -b ${branch} 2>/dev/null || git worktree add ${worktree} ${branch}
Verify: ls ${worktree}`,
            { label: `worktree:${task.id}`, phase: 'Implement', agentType: 'general-purpose' }
          )

          return agent(
            `You are an Implementer agent executing ${task.id}: ${task.title}

Worktree: ${worktree}
Branch: ${branch}
Main repo: ${ROOT}

Steps:
1. Read task: \`backlog task view ${task.id} --plain\` (run from ${ROOT})
2. Read relevant decisions: \`backlog decision list --plain\`
3. Work in worktree: all file edits in ${worktree}
4. Implement all acceptance criteria. Stay within task scope.
5. Do NOT touch client/ or lib/ unless task explicitly requires it.
6. Run quality gate: \`cd ${ROOT} && make check\` — fix issues, max 3 attempts
7. Open PR: \`cd ${worktree} && gh pr create --title "feat(scope): ${task.title} (${task.id})" --body "Closes ${task.id}. See task for AC."\`
8. Move to In Review: \`cd ${ROOT} && backlog task edit ${task.id} --status "In Review"\`

Commit format: Conventional Commits. No Co-Authored-By trailers.
If make check fails 3x: move task to "To Do" with blocker note, return status "blocked".

Return structured result.`,
            {
              label: `impl:${task.id}`,
              phase: 'Implement',
              schema: IMPL_RESULT_SCHEMA,
              agentType: 'general-purpose',
            }
          )
        })
      )

      iterImplResults.push(...groupResults.filter(Boolean))
    }

    allImplResults.push(...iterImplResults)
    log(`Implement: ${iterImplResults.filter(r => r.status === 'in_review').length} in review, ${iterImplResults.filter(r => r.status !== 'in_review').length} blocked`)
  }

  // ── Phase: Review (includes pre-existing in-review tasks) ─────────────────

  const toReview = [
    ...iterImplResults.filter(r => r.status === 'in_review' && r.pr_url),
    ...freshInReview.map(t => ({ task_id: t.id, pr_url: t.pr_url, pr_number: t.pr_number, needs_security_audit: t.needs_security_audit })),
  ]

  if (toReview.length > 0) {
    phase('Review')
    log(`Reviewing ${toReview.length} task(s)`)

    const iterReviewResults = (await parallel(
      toReview.map(impl => async () => {
        const taskMeta = scan.eligible_tasks.find(t => t.id === impl.task_id)
          || scan.in_review_tasks.find(t => t.id === impl.task_id)

        const review = await agent(
          `You are a Reviewer agent for ${impl.task_id}.

PR: ${impl.pr_url || 'find via: gh pr list --search "head:task/" --json number,headRefName,url'}
Main repo: ${ROOT}

Steps:
1. \`backlog task view ${impl.task_id} --plain\`
2. \`gh pr diff ${impl.pr_number || impl.task_id} 2>/dev/null || gh pr list --search "${impl.task_id}" --json number,url\`
3. Review: scope, acceptance criteria, correctness, no core/ violations, no Co-Authored-By in commits
4. \`backlog task edit ${impl.task_id} --notes "Code review: <verdict> — <findings>"\`

Finding format: path:line: 🔴CRITICAL/🟠HIGH/🟡MEDIUM/🔵INFO: problem. fix.
Verdict: LGTM or BLOCKED: <reason>`,
          {
            label: `review:${impl.task_id}`,
            phase: 'Review',
            schema: REVIEW_RESULT_SCHEMA,
            agentType: 'general-purpose',
          }
        )

        if (!review) return null

        let secVerdict = 'SKIPPED'
        let secFindings = ''

        const needsAudit = taskMeta ? taskMeta.needs_security_audit : false
        if (needsAudit) {
          log(`Security audit: ${impl.task_id}`)
          const secAudit = await agent(
            `You are a Security Auditor for ${impl.task_id}.

PR: ${impl.pr_url || 'find via gh pr list'}
Main repo: ${ROOT}

Steps:
1. \`gh pr diff ${impl.pr_number || impl.task_id}\`
2. Audit: auth tokens (never plaintext/logcat), network TLS, Android permissions, VPN service isolation, token storage (Keystore only)
3. \`backlog task edit ${impl.task_id} --notes "Security audit: <PASS|BLOCKED> — <findings>"\`

CRITICAL findings block merge.`,
            {
              label: `security:${impl.task_id}`,
              phase: 'Review',
              schema: REVIEW_RESULT_SCHEMA,
              agentType: 'general-purpose',
            }
          )

          if (secAudit) {
            secVerdict = secAudit.security_verdict || secAudit.verdict
            secFindings = secAudit.security_findings || secAudit.findings || ''
          }
        }

        return {
          task_id: impl.task_id,
          pr_url: impl.pr_url,
          pr_number: impl.pr_number,
          verdict: review.verdict,
          findings: review.findings,
          security_verdict: secVerdict,
          security_findings: secFindings,
          merge_ready: review.verdict === 'LGTM' && secVerdict !== 'BLOCKED',
        }
      })
    )).filter(Boolean)

    allReviewResults.push(...iterReviewResults)
  }

  // ── Phase: Merge poll ─────────────────────────────────────────────────────

  const mergeReady = allReviewResults.filter(r => r.merge_ready && !allMergedTaskIds.includes(r.task_id))

  if (mergeReady.length > 0) {
    phase('Merge')
    log(`${mergeReady.length} PR(s) ready to merge:`)
    mergeReady.forEach(r => log(`  ${r.task_id}: ${r.pr_url || '(url via gh pr list)'}`))
    log('Polling for merges (user must approve in GitHub)...')

    // Poll a few times then continue — don't block the loop
    for (let p = 0; p < 3; p++) {
      const mergeStatus = await agent(
        `Check merge status for these PRs:
${mergeReady.map(r => `- ${r.task_id}: ${r.pr_url || 'find via gh pr list'}`).join('\n')}

For each: \`gh pr view <number> --json state,mergedAt --jq '{number,state,mergedAt}'\`
After any detected merge: \`cd ${ROOT} && git pull && make check\`

Return: { merged: ["TASK-X",...], pending: ["TASK-Y",...], make_check_passed: true/false }`,
        {
          label: `merge-poll:${iteration}-${p}`,
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
          agentType: 'general-purpose',
        }
      )

      if (mergeStatus && mergeStatus.merged.length > 0) {
        allMergedTaskIds.push(...mergeStatus.merged.filter(id => !allMergedTaskIds.includes(id)))
        if (!mergeStatus.make_check_passed) {
          log(`WARNING: make check failed on master after merge — tasks held from Done`)
        }
        if (mergeStatus.pending.length === 0) break
      }
    }
  }

  // ── Phase: Finalize ────────────────────────────────────────────────────────

  const toFinalize = [
    ...allSpikeResults.filter(r => r.status === 'done').map(r => r.task_id),
    ...allMergedTaskIds,
  ].filter(id => !allFinalizedTaskIds.includes(id))

  if (toFinalize.length > 0) {
    phase('Finalize')

    await parallel(
      toFinalize.map(taskId => async () => {
        const result = await agent(
          `You are a Finalizer agent closing task ${taskId} after successful merge.

Task: ${taskId}
Main repo: ${ROOT}

Follow finalization guide:
1. \`backlog instructions task-finalization\`
2. \`backlog task view ${taskId} --plain\`
3. Verify each acceptance criterion with evidence (run commands)
4. \`backlog task edit ${taskId} --check-ac <n>\` (only with evidence)
5. \`backlog task edit ${taskId} --check-dod <n>\`
6. Create missing decisions: \`backlog decision create "..." -s accepted\`
7. \`backlog task edit ${taskId} --append-notes "Final notes: ..."\`
8. \`backlog task edit ${taskId} --final-summary "..."\`
9. \`backlog task edit ${taskId} --status "Done"\``,
          {
            label: `finalize:${taskId}`,
            phase: 'Finalize',
            agentType: 'general-purpose',
          }
        )
        if (result) allFinalizedTaskIds.push(taskId)
        return result
      })
    )

    log(`Finalized: ${allFinalizedTaskIds.length} total`)
  }

  // Continue loop — next iteration picks up newly unblocked tasks
  log(`Iteration ${iteration} complete. Continuing to check for newly eligible tasks...`)
}

// ─── Summary ────────────────────────────────────────────────────────────────

phase('Summary')

const reviewBlocked = allReviewResults.filter(r => !r.merge_ready)
const mergeReadyFinal = allReviewResults.filter(r => r.merge_ready && !allMergedTaskIds.includes(r.task_id))

return {
  iterations: iteration,
  spikes: {
    done: allSpikeResults.filter(r => r.status === 'done').map(r => r.task_id),
    blocked: allSpikeResults.filter(r => r.status === 'blocked').map(r => r.task_id),
  },
  implementation: {
    in_review: allImplResults.filter(r => r.status === 'in_review').map(r => r.task_id),
    blocked: allImplResults.filter(r => r.status !== 'in_review').map(r => r.task_id),
  },
  review: {
    merge_ready: mergeReadyFinal.map(r => ({ task_id: r.task_id, pr_url: r.pr_url })),
    blocked: reviewBlocked.map(r => ({ task_id: r.task_id, verdict: r.verdict, findings: r.findings })),
  },
  merged: allMergedTaskIds,
  finalized: allFinalizedTaskIds,
  action_required: [
    ...reviewBlocked.map(r => `Fix review blockers — ${r.task_id}: ${r.findings}`),
    ...mergeReadyFinal.map(r => `Merge pending — ${r.task_id}: ${r.pr_url || 'check gh pr list'}`),
  ].join('\n') || 'All work finalized.',
}
