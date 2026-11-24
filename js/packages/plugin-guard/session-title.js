// Automatic conversation titles (#89; row `android-session-title`, bundle-android). dsh's title service
// (`sessionTitle`, dsh-session-title) already publishes a deterministic fallback from the first message
// and runs one registered provider for a conversation's first prompt, off the main request, superseded
// by a user rename. dsh's own model provider (`session-title-llm`) is off in SDK mode and asks in
// parallel with the first request from the message alone; this one waits until the first turn has
// ended and names the conversation from the first message and the start of its reply (no tool
// results), with the model and reasoning level of 设置 → 对话标题 (the app's settings layer writes
// this row: `disabled` when 自动生成对话标题 is off). Request bounds, the deadline, cancellation and
// the logged `session/title-llm-request` are dsh-session-title-llm's. A failure only logs: the
// fallback title stays.

export const name = 'android-session-title'
export const inject = ['sessionTitle', 'llm', 'sessions']

const PROVIDER_ID = 'android-first-reply-llm'
export const RENAME_METHOD = 'android/session/title'
/** Automation runs are named after their rule (app); no model call for them. */
const AUTO_PREFIX = 'auto_'
/** Excerpts sent to the title model, in characters. */
export const USER_CHARS = 1000
export const REPLY_CHARS = 1500
const LIMITS = Object.freeze({
  maxInputBytes: 16384,
  // A ceiling against runaway output (reasoning a model cannot switch off counts too), as in dsh-base.
  maxOutputTokens: 4096,
  timeoutMs: 60000,
})
const MAX_TITLE_BYTES = 80
/** `reasoning`: the route's lowest level (off where it can be), the conversation's own, or one level id. */
export const LOWEST = 'lowest'
export const CONVERSATION = 'conversation'

/** Validated row config: `{ provider?, model? }` (both or neither: none = the conversation's model) and `reasoning`. */
export function resolveConfig(config) {
  const c = config ?? {}
  const str = v => typeof v === 'string' && v !== '' ? v : undefined
  const provider = str(c.provider)
  const model = str(c.model)
  const route = provider !== undefined && model !== undefined ? { provider, model } : {}
  return Object.freeze({ ...route, reasoning: str(c.reasoning) ?? LOWEST })
}

/**
 * The level for the title call on [model] (dsh's model info: `reasoning.efforts`, lowest first):
 * [want] while the model offers it; `conversation` = [conversation]'s level (none = the model's
 * default, as the conversation runs); otherwise the lowest one.
 */
export function chooseEffort(model, want, conversation) {
  const ids = (model?.reasoning?.efforts ?? []).map(e => e?.id).filter(id => typeof id === 'string')
  if (ids.length === 0) return undefined
  if (want === CONVERSATION) return ids.includes(conversation) ? conversation : undefined
  return ids.includes(want) ? want : ids[0]
}

const textOf = content => Array.isArray(content)
  ? content.filter(b => b?.type === 'text' && typeof b.text === 'string').map(b => b.text).join('\n')
  : ''

/** Whether the first turn (the one [firstSeq] started) has ended in [events]. */
export function firstTurnEnded(events, firstSeq) {
  return events.some(e => e?.type === 'turn/end' && e.seq > firstSeq)
}

/** The assistant's text of the first turn (no reasoning, no tool calls or results), up to [max] characters. */
export function firstReply(events, firstSeq, max = REPLY_CHARS) {
  const parts = []
  for (const e of events) {
    if (e?.seq <= firstSeq) continue
    if (e?.type === 'turn/end') break
    if (e?.type === 'assistant/message') {
      const t = textOf(e.data?.message?.content).trim()
      if (t !== '') parts.push(t)
    }
  }
  return clip(parts.join('\n'), max)
}

const clip = (s, max) => {
  const chars = [...s]
  return chars.length <= max ? s : chars.slice(0, max).join('') + '…'
}

/** Language-aware system instruction. */
export function systemPrompt() {
  return [
    'Create a concise title for a conversation between a user and an AI assistant on their phone, from its first user message and the start of the assistant\'s reply.',
    'Return only the title on one line, in plain natural language, with no quotes, prefix (such as "Title:"), explanation, Markdown or code.',
    'Use the language of the user\'s message.',
    'Aim for about 5 words in non-CJK languages or at most 12 CJK characters; name the topic or the task, not the greeting.',
    'If the exchange gives little to name, still return a short best-effort title, such as Greeting, instead of explaining.',
  ].join('\n')
}

/** The exchange as JSON, so user text cannot break structural delimiters. */
export function frameInput(user, reply) {
  return `Generate the conversation title from this JSON exchange:\n${JSON.stringify(reply === '' ? { user } : { user, assistant: reply })}`
}

const QUOTES = /^["'“”‘’「」『』《》]+|["'“”‘’「」『』《》]+$/gu
const PREFIX = /^(?:title|标题|標題|заголовок|название)\s*[:：]\s*/iu
const EMPHASIS = /^(\*{1,3})(\S(?:.*\S)?)\1$/u

/** The title from model output: its first non-empty line, without wrapping emphasis, a "Title:" prefix or quotes. */
export function titleFromOutput(text) {
  let line = String(text ?? '').split(/\r?\n/u).map(s => s.trim()).find(s => s.length > 0) ?? ''
  line = line.replace(EMPHASIS, '$2').replace(PREFIX, '').replace(QUOTES, '').trim()
  return line.replace(/[。.]$/u, '')
}

/**
 * Registers the title provider on [ctx]. `deps` (tests): `execute` (dsh-session-title-llm's
 * executeSessionTitleLlm), `normalize` (dsh-session-title's normalizeSessionTitle), `log`.
 */
export function setupSessionTitle(ctx, config, deps = {}) {
  const cfg = resolveConfig(config)
  const log = deps.log ?? ((m) => process.stderr.write(`[android-session-title] ${m}\n`))
  let execute = deps.execute
  let normalize = deps.normalize
  const load = async () => {
    execute ??= (await import('@deepseek-ai/dsh-session-title-llm')).executeSessionTitleLlm
    normalize ??= (await import('@deepseek-ai/dsh-session-title')).normalizeSessionTitle
  }

  /** session id → callbacks waiting for its next `turn/end`. */
  const waiters = new Map()
  ctx.on('session/event', (session, event) => {
    if (event?.type !== 'turn/end') return
    const id = String(session?.id)
    const list = waiters.get(id)
    if (list === undefined) return
    waiters.delete(id)
    for (const done of list) done()
  })
  const turnEnded = (session, firstSeq, signal) => new Promise((resolve, reject) => {
    if (firstTurnEnded(session.snapshotEvents?.() ?? [], firstSeq)) return resolve()
    signal.throwIfAborted()
    const id = String(session.id)
    const list = waiters.get(id) ?? []
    const done = () => { signal.removeEventListener('abort', abort); resolve() }
    const abort = () => {
      const left = (waiters.get(id) ?? []).filter(f => f !== done)
      if (left.length > 0) waiters.set(id, left); else waiters.delete(id)
      reject(signal.reason)
    }
    list.push(done)
    waiters.set(id, list)
    signal.addEventListener('abort', abort, { once: true })
  })

  const generate = async request => {
    const { session, signal } = request
    const first = request.messages[0]
    if (first === undefined) throw new Error('no human message to name the conversation after')
    if (String(session.id).startsWith(AUTO_PREFIX)) throw new Error('automation run: not named by a model')
    await load()
    // After the first reply, never alongside the conversation's own first request.
    await turnEnded(session, first.seq, signal)
    const events = session.snapshotEvents?.() ?? []
    const reply = firstReply(events, first.seq)
    const conversationEffort = session.requestHeader?.()?.config?.reasoningEffort
    const response = await execute(ctx, { ...LIMITS, ...cfg.provider === undefined ? {} : { provider: cfg.provider, model: cfg.model } }, request, PROVIDER_ID, {
      system: systemPrompt(),
      input: frameInput(clip(first.text.trim(), USER_CHARS), reply),
      messageSeqs: [first.seq],
      selectReasoningEffort: model => chooseEffort(model, cfg.reasoning, conversationEffort),
    })
    if (response.finish.kind !== 'stop') throw new Error(`title model finished with ${response.finish.kind}`)
    const title = normalize(titleFromOutput(response.blocks.filter(b => b.type === 'text').map(b => b.text).join(' ')), MAX_TITLE_BYTES)
    if (title.length === 0) throw new Error('title model produced no text')
    log(`session ${session.id}: titled by ${response.model.provider}/${response.model.model}`)
    return { title, messageSeqs: [first.seq], model: response.model }
  }

  // 重命名 in the app (`android/session/title { sessionId, title }`): dsh's user title pins the
  // conversation, so a title call still waiting or running is dropped. A conversation this process
  // does not hold changes nothing here (the app keeps its own record of the rename).
  ctx.on('sdk-extensions/resolve', method => method !== RENAME_METHOD ? undefined : params => {
    const { sessionId, title } = params ?? {}
    if (typeof sessionId !== 'string' || sessionId === '' || typeof title !== 'string') throw new TypeError(`${RENAME_METHOD} requires sessionId and title strings`)
    const session = ctx.sessions.get(sessionId)
    if (session === undefined) return { applied: false }
    ctx.sessionTitle.rename(session, title)
    return { applied: true }
  })

  return ctx.sessionTitle.register({ id: PROVIDER_ID, automatic: 'first-prompt', generate })
}

export function apply(ctx, config) {
  setupSessionTitle(ctx, config)
}
