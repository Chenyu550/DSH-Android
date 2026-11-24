// plugin-guard session-fork.js (#2): edit / retry / fork a conversation on a fake context.
//   node --test js/test/session-fork.test.mjs
// With DSH_STAGE_NM=<stage>/node_modules the seed comes from dsh's own `buildForkSeed`.
import test from 'node:test'
import assert from 'node:assert/strict'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { METHOD, forkBoundary, normalizeForkParams, setupSessionFork } from '../packages/plugin-guard/session-fork.js'

const stage = process.env.DSH_STAGE_NM
const realSeed = stage === undefined ? undefined
  : (await import(pathToFileURL(join(stage, '@deepseek-ai/dsh-session/lib/types/fork.js')).href)).buildForkSeed

/** dsh's seed shape without its closers: prefix, end-seed marker, then one turn/end for an open tail turn. */
function fakeSeed(events, boundary) {
  const prefix = events.slice(0, boundary + 1)
  prefix.push({ type: 'session/end-seed', seq: boundary + 1, time: events[boundary].time, data: { inherited: true } })
  let open = null
  for (const e of prefix) { if (e.type === 'turn/start') open = e.data.turn; if (e.type === 'turn/end') open = null }
  if (open !== null) prefix.push({ type: 'turn/end', seq: boundary + 2, time: events[boundary].time, data: { turn: open, reason: { kind: 'forked' } } })
  return prefix
}

const user = (id, text, kind = 'user') => ({ type: 'user/message', data: { id, role: 'user', content: [{ type: 'text', text }], source: { kind } } })
const assistant = (turn, step, text, calls = []) => ({ type: 'assistant/message', data: { turn, step, message: { id: `a-${text}`, role: 'assistant',
  content: [{ type: 'text', text }, ...calls.map(id => ({ type: 'tool-call', id, name: 'app_open', arguments: '{}' }))] } } })

/** Two turns; the second has a message steered into it after a tool call. */
function log() {
  const splice = id => ({ type: 'agent/inbox/spliced', data: { target: 'next-turn', start: 0, inserted: [{ id, role: 'user' }] } })
  const raw = [
    splice('m1'),                                         // queued before its turn
    { type: 'turn/start', data: { turn: 1 } },
    { type: 'step/start', data: { turn: 1, step: 1 } },
    { type: 'system/message', data: { turn: 1, step: 1 } },
    user('m1', 'hi'),
    assistant(1, 1, 'hello'),
    { type: 'step/end', data: { turn: 1, step: 1 } },
    { type: 'turn/end', data: { turn: 1, reason: { kind: 'completed' } } },
    { type: 'session/title', data: { title: 'Hi' } },
    splice('m2'),
    { type: 'turn/start', data: { turn: 2 } },
    { type: 'step/start', data: { turn: 2, step: 1 } },
    user('ctx', 'runtime context', 'runtime-context'),
    user('m2', 'open the app'),
    assistant(2, 1, 'opening', ['c1']),
    { type: 'tool/result', surfaceOp: 'append', data: { turn: 2, step: 1, message: { toolCallId: 'c1', source: { kind: 'tool', callId: 'c1' } } } },
    { type: 'step/end', data: { turn: 2, step: 1 } },
    { type: 'step/start', data: { turn: 2, step: 2 } },
    user('m3', 'and then the other one'),
    assistant(2, 2, 'done'),
    { type: 'step/end', data: { turn: 2, step: 2 } },
    { type: 'turn/end', data: { turn: 2, reason: { kind: 'completed' } } },
  ]
  return raw.map((e, seq) => ({ ...e, seq, time: 1000 + seq }))
}

test('the cut: before the turn a message opened, before a steered message, or the whole log', () => {
  const events = log()
  assert.equal(forkBoundary(events, 'm1'), -1)
  // The title record between the turns stays; the inbox entry that queued m2 and turn 2's injected context go with it.
  assert.equal(forkBoundary(events, 'm2'), 8)
  assert.equal(events[9].type, 'agent/inbox/spliced')
  assert.equal(forkBoundary(events, 'm3'), 17)
  assert.equal(forkBoundary(events, undefined), 21)
  assert.equal(forkBoundary(events, 'ctx'), undefined, 'only human messages anchor a cut')
  assert.equal(forkBoundary(events, 'nope'), undefined)
  assert.equal(forkBoundary([], undefined), -1)
})

test('payloads are validated', () => {
  assert.deepEqual(normalizeForkParams({ sessionId: 'a', childSessionId: 'b', beforeMessageId: null }), { sessionId: 'a', childSessionId: 'b', beforeMessageId: undefined })
  assert.throws(() => normalizeForkParams({ childSessionId: 'b' }), TypeError)
  assert.throws(() => normalizeForkParams({ sessionId: 'a' }), TypeError)
  assert.throws(() => normalizeForkParams({ sessionId: 'a', childSessionId: 'a' }), TypeError)
  assert.throws(() => normalizeForkParams({ sessionId: 'a', childSessionId: 'b', beforeMessageId: '' }), TypeError)
})

function harness({ live = false } = {}) {
  const events = log()
  const header = { version: 4, id: 'src', createdAt: 1, cwd: '/data/home', isSeeded: false }
  const stored = new Map([['src', { header, events, inherited: 0 }]])
  const flushed = []
  const validated = []
  const handlers = []
  const liveSession = { id: 'src', header, snapshotEvents: () => events }
  const persistence = {
    async stat(id) { return stored.has(id) ? { header: stored.get(id).header } : undefined },
    async open(id, access) {
      assert.equal(access, 'read')
      const s = stored.get(id)
      return { header: s.header, async read() { return { events: s.events } }, async close() {} }
    },
    async create(h, { inheritedEventCount }) {
      const s = { header: h, events: [], inherited: inheritedEventCount, durable: false, closed: false }
      stored.set(h.id, s)
      return {
        async append(batch) { assert.equal(batch[0].seq, s.events.length); s.events.push(...batch) },
        async flush() { s.durable = true },
        async close() { s.closed = true },
      }
    },
  }
  const ctx = {
    on: (event, fn) => { if (event === 'sdk-extensions/resolve') handlers.push(fn); return () => {} },
    get(name) {
      if (name === 'sessionPersistence') return persistence
      if (name === 'sessions') return { get: id => (live && id === 'src' ? liveSession : undefined), async flush(s) { flushed.push(s.id) } }
      return undefined
    },
  }
  setupSessionFork(ctx, {
    buildForkSeed: realSeed ?? fakeSeed,
    validate: (id, seed, h, inherited) => { validated.push({ id, inherited, n: seed.length }) },
  })
  const call = params => handlers.map(h => h(METHOD)).find(Boolean)(params)
  return { call, stored, flushed, validated }
}

test('edit / retry: the child is the log before the turn, stored durably, the source untouched', async () => {
  const h = harness()
  const r = await h.call({ sessionId: 'src', childSessionId: 'child', beforeMessageId: 'm2' })
  assert.deepEqual(r, { sessionId: 'child', inherited: 9, closers: [] })
  const child = h.stored.get('child')
  assert.equal(child.inherited, 9)
  assert.equal(child.header.isSeeded, true)
  assert.equal(child.header.cwd, '/data/home')
  assert.equal(child.header.id, 'child')
  assert.deepEqual(child.events.map(e => e.type).slice(-2), ['session/title', 'session/end-seed'])
  assert.ok(child.durable && child.closed)
  assert.deepEqual(h.validated, [{ id: 'child', inherited: 9, n: 10 }])
  assert.equal(h.stored.get('src').events.length, 22)
})

test('a cut inside a running turn closes it in the child and hands the closers to the app', async () => {
  const h = harness()
  const r = await h.call({ sessionId: 'src', childSessionId: 'child', beforeMessageId: 'm3' })
  assert.equal(r.inherited, 18)
  assert.ok(r.closers.length >= 1)
  const end = r.closers.at(-1)
  assert.equal(end.type, 'turn/end')
  assert.equal(end.data.turn, 2)
  assert.equal(end.data.reason.kind, 'forked')
  assert.equal(r.closers[0].seq, 19, 'closers follow the end-seed marker')
})

test('从这里分叉 after the last turn keeps the whole log; a live source is flushed and read in memory', async () => {
  const h = harness({ live: true })
  const r = await h.call({ sessionId: 'src', childSessionId: 'child' })
  assert.equal(r.inherited, 22)
  assert.deepEqual(r.closers, [])
  assert.deepEqual(h.flushed, ['src'])
})

test('editing the first message writes nothing: the prompt creates the child', async () => {
  const h = harness()
  assert.deepEqual(await h.call({ sessionId: 'src', childSessionId: 'child', beforeMessageId: 'm1' }), { sessionId: 'child', inherited: 0, closers: [] })
  assert.equal(h.stored.has('child'), false)
})

test('refusals: unknown source or message, an existing child', async () => {
  const h = harness()
  await assert.rejects(h.call({ sessionId: 'nope', childSessionId: 'child' }), /not found/)
  await assert.rejects(h.call({ sessionId: 'src', childSessionId: 'child', beforeMessageId: 'zz' }), /not in session/)
  await assert.rejects(h.call({ sessionId: 'src', childSessionId: 'src2', beforeMessageId: 'm2' }).then(() => h.call({ sessionId: 'src', childSessionId: 'src2' })), /already exists/)
})
