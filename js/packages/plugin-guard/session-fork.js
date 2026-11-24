// Edit, retry and fork a conversation (#2, ENGINE-CONTRACT §2): `android/session/fork
// { sessionId, childSessionId, beforeMessageId? }` → `{ sessionId, inherited, closers }`. dsh models no
// session tree, only boundary forks: a new ordinary session seeded with an exact prefix of the source's
// log (dsh-session's `buildForkSeed`: the copied events, a `session/end-seed` marker and, for a cut inside
// a turn, synthetic `forked` tool results and step/turn closers), what the Web host's `session/fork` does.
// The source stays as it is. The child is written straight to dsh's session store (`sessionPersistence`,
// as dsh-deepseek-chat-import writes an imported session) and is not opened: the app's next
// `session/prompt` on it goes through `session/resume`, like any session of an earlier engine process.
//
// The cut: `beforeMessageId` = the id of a human `user/message`. The child keeps everything before the
// turn that message opened and before the `agent/inbox/spliced` that queued it (or, for a message
// steered into a running turn, everything before the message itself), so the app can send the edited
// or the same message again as the child's next turn.
// Without it the child keeps the whole log (从这里分叉 after the last turn). `inherited` is the number of
// source events copied (0: nothing to keep, no session is written and the app's prompt creates it);
// `closers` are the child's synthetic closing events, which the app logs after its copy of the prefix.

export const METHOD = 'android/session/fork'

/** Events that make a turn's content: a human message after one of them is not the turn's first. */
const CONTENT = new Set(['assistant/message', 'assistant/attempt', 'tool/call', 'tool/result', 'turn/end'])
const isHumanPrompt = e => e?.type === 'user/message' && e.data?.source?.kind === 'user'

/**
 * The inclusive seq the child inherits through (-1: nothing), or undefined when [beforeMessageId] is
 * not a human message of [events] (contiguous seqs from 0).
 */
export function forkBoundary(events, beforeMessageId) {
  if (beforeMessageId === undefined) return events.length - 1
  const at = events.findIndex(e => isHumanPrompt(e) && e.data?.id === beforeMessageId)
  if (at < 0) return undefined
  let cut = at
  for (let i = at - 1; i >= 0; i--) {
    const e = events[i]
    if (e?.type === 'turn/start') { cut = i; continue }
    // The inbox entry that queued the message for its turn goes too: the child's inbox never held it.
    if (e?.type === 'agent/inbox/spliced' && e.data?.inserted?.some?.(m => m?.id === beforeMessageId)) { cut = i; break }
    if (CONTENT.has(e?.type) || isHumanPrompt(e)) break
  }
  return cut - 1
}

/** Validated payload. Throws TypeError on bad input. */
export function normalizeForkParams(params) {
  const { sessionId, childSessionId, beforeMessageId } = params ?? {}
  if (typeof sessionId !== 'string' || sessionId === '') throw new TypeError(`${METHOD} requires a sessionId string`)
  if (typeof childSessionId !== 'string' || childSessionId === '' || childSessionId === sessionId)
    throw new TypeError(`${METHOD} requires a new childSessionId string`)
  if (beforeMessageId !== undefined && beforeMessageId !== null && (typeof beforeMessageId !== 'string' || beforeMessageId === ''))
    throw new TypeError(`${METHOD}: beforeMessageId must be a non-empty string when given`)
  return { sessionId, childSessionId, beforeMessageId: beforeMessageId ?? undefined }
}

/**
 * Serves `android/session/fork` on [ctx] (`sessions`, `sessionPersistence`).
 * `deps` (tests): `buildForkSeed`, `validate(id, seed, header, inherited)` (dsh's Session construction).
 */
export function setupSessionFork(ctx, deps = {}) {
  let buildForkSeed = deps.buildForkSeed
  let validate = deps.validate
  const load = async () => {
    buildForkSeed ??= (await import('@deepseek-ai/dsh-session/fork')).buildForkSeed
    if (validate === undefined) {
      const { Session } = await import('@deepseek-ai/dsh-session')
      // The same seed validation a live Session runs; the detached instance is discarded.
      validate = (id, seed, header, inherited) => { Session.create(id, seed, header, inherited, ctx.get('sessions')?.messageProjections) }
    }
  }

  /** The source's events and header: the live session's (flushed first), else the stored log. */
  const source = async (persistence, sessionId) => {
    const live = ctx.get('sessions')?.get(sessionId)
    if (live !== undefined) {
      await ctx.get('sessions').flush(live)
      return { events: live.snapshotEvents(), header: live.header }
    }
    if (await persistence.stat(sessionId) === undefined) throw new Error(`session "${sessionId}" not found`)
    const handle = await persistence.open(sessionId, 'read')
    try {
      return { events: (await handle.read()).events, header: handle.header }
    } finally {
      await handle.close()
    }
  }

  const fork = async params => {
    const { sessionId, childSessionId, beforeMessageId } = normalizeForkParams(params)
    const persistence = ctx.get('sessionPersistence')
    if (persistence === undefined) throw new Error(`${METHOD}: this engine stores no sessions`)
    if (ctx.get('sessions')?.get(childSessionId) !== undefined || await persistence.stat(childSessionId) !== undefined)
      throw new Error(`session "${childSessionId}" already exists`)
    const { events, header } = await source(persistence, sessionId)
    const boundary = forkBoundary(events, beforeMessageId)
    if (boundary === undefined) throw new Error(`message "${beforeMessageId}" is not in session "${sessionId}"`)
    if (boundary < 0) return { sessionId: childSessionId, inherited: 0, closers: [] }
    await load()
    const seed = buildForkSeed(events, boundary)
    const inherited = boundary + 1
    const childHeader = {
      version: header.version,
      id: childSessionId,
      createdAt: Date.now(),
      ...header.cwd === undefined ? {} : { cwd: header.cwd },
      isSeeded: true,
      ...header.agentPreset === undefined ? {} : { agentPreset: header.agentPreset },
    }
    validate(childSessionId, seed, childHeader, inherited)
    const handle = await persistence.create(childHeader, { inheritedEventCount: inherited })
    try {
      await handle.append(seed)
      await handle.flush()
    } finally {
      await handle.close()
    }
    // After the copied prefix and the `session/end-seed` marker.
    return { sessionId: childSessionId, inherited, closers: seed.slice(inherited + 1) }
  }

  ctx.on('sdk-extensions/resolve', method => method === METHOD ? fork : undefined)
}
