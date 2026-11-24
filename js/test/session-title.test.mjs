// plugin-guard session-title.js (#89): automatic conversation titles after the first reply, on a fake context.
//   node --test js/test/session-title.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { RENAME_METHOD, chooseEffort, firstReply, frameInput, resolveConfig, setupSessionTitle, titleFromOutput } from '../packages/plugin-guard/session-title.js'

const msg = (seq, type, data) => ({ seq, type, data })
const userMsg = (seq, text) => msg(seq, 'user/message', { source: { kind: 'user' }, content: [{ type: 'text', text }] })
const reply = (seq, ...content) => msg(seq, 'assistant/message', { message: { content } })

function harness(config, { output = 'Weather in Paris', header = { provider: 'deepseek-official', model: 'deepseek-pro', reasoningEffort: 'high' } } = {}) {
  const handlers = new Map()
  let provider
  const calls = []
  const ctx = {
    on: (event, fn) => { handlers.set(event, [...handlers.get(event) ?? [], fn]); return () => {} },
    sessionTitle: { register: p => { provider = p; return () => {} }, rename: (s, title) => renames.push({ id: s.id, title }) },
    sessions: { get: id => live.get(id) },
  }
  const renames = []
  const live = new Map()
  const execute = async (_ctx, cfg, request, id, prepared) => {
    const route = cfg.provider === undefined ? request.route : { provider: cfg.provider, model: cfg.model }
    const efforts = { efforts: ['off', 'low', 'high', 'max'].map(id => ({ id })) }
    calls.push({ cfg, id, prepared, route, effort: prepared.selectReasoningEffort({ reasoning: efforts }) })
    return { blocks: [{ type: 'text', text: output }], finish: { kind: 'stop' }, model: route }
  }
  setupSessionTitle(ctx, config, { execute, normalize: s => s.trim(), log: () => {} })
  const session = (id, events) => ({ id, events, snapshotEvents() { return this.events }, requestHeader: () => ({ config: header }) })
  const emit = (s, event) => { s.events.push(event); for (const h of handlers.get('session/event') ?? []) h(s, event) }
  const generate = (s, signal = new AbortController().signal) =>
    provider.generate({ session: s, messages: [{ seq: 1, text: 'What is the weather in Paris?' }], route: { provider: header.provider, model: header.model }, signal })
  const method = name => handlers.get('sdk-extensions/resolve').map(h => h(name)).find(Boolean)
  return { provider: () => provider, calls, session, emit, generate, method, renames, live }
}

test('registers one first-prompt provider', () => {
  const h = harness({})
  assert.equal(h.provider().automatic, 'first-prompt')
})

test('waits for the first turn to end, then names it from the message and the reply on the conversation\'s model', async () => {
  const h = harness({ reasoning: 'lowest' })
  const s = h.session('s1', [userMsg(1, 'What is the weather in Paris?')])
  let done = false
  const pending = h.generate(s).then(r => { done = true; return r })
  await new Promise(r => setTimeout(r, 10))
  assert.equal(done, false, 'no title call while the first turn runs')
  assert.equal(h.calls.length, 0)
  h.emit(s, reply(2, { type: 'thinking', text: 'secret' }, { type: 'text', text: 'Let me check.' }, { type: 'tool-call', name: 'web' }))
  h.emit(s, msg(3, 'tool/result', { output: 'TOOL OUTPUT' }))
  h.emit(s, reply(4, { type: 'text', text: 'It is sunny, 21°C.' }))
  h.emit(s, msg(5, 'turn/end', { reason: { kind: 'completed' } }))
  const result = await pending
  assert.deepEqual(result, { title: 'Weather in Paris', messageSeqs: [1], model: { provider: 'deepseek-official', model: 'deepseek-pro' } })
  const [call] = h.calls
  assert.equal(call.cfg.provider, undefined)
  assert.equal(call.effort, 'off')
  assert.equal(call.prepared.input, frameInput('What is the weather in Paris?', 'Let me check.\nIt is sunny, 21°C.'))
  assert.ok(!call.prepared.input.includes('TOOL OUTPUT') && !call.prepared.input.includes('secret'))
})

test('a pinned model and level, or the conversation\'s level', async () => {
  const h = harness({ provider: 'kimi', model: 'kimi-k3', reasoning: 'low' })
  const s = h.session('s2', [userMsg(1, 'x'), reply(2, { type: 'text', text: 'y' }), msg(3, 'turn/end', {})])
  await h.generate(s)
  assert.deepEqual(h.calls[0].route, { provider: 'kimi', model: 'kimi-k3' })
  assert.equal(h.calls[0].effort, 'low')
  const c = harness({ reasoning: 'conversation' })
  await c.generate(c.session('s3', [userMsg(1, 'x'), msg(2, 'turn/end', {})]))
  assert.equal(c.calls[0].effort, 'high')
})

test('a rename (or engine stop) aborts the wait without a model call; automation runs are skipped', async () => {
  const h = harness({})
  const s = h.session('s4', [userMsg(1, 'x')])
  const ac = new AbortController()
  const pending = h.generate(s, ac.signal)
  ac.abort(new Error('user rename superseded automatic title generation'))
  await assert.rejects(pending, /user rename/)
  h.emit(s, msg(2, 'turn/end', {}))
  assert.equal(h.calls.length, 0)
  await assert.rejects(h.generate(h.session('auto_1', [userMsg(1, 'x'), msg(2, 'turn/end', {})])), /automation/)
  assert.equal(h.calls.length, 0)
})

test('config, levels and output cleanup', () => {
  assert.deepEqual(resolveConfig(undefined), { reasoning: 'lowest' })
  assert.deepEqual(resolveConfig({ provider: 'kimi', reasoning: 'max' }), { reasoning: 'max' }, 'a provider without a model is ignored')
  const m = { reasoning: { efforts: [{ id: 'low' }, { id: 'high' }] } }
  assert.equal(chooseEffort(m, 'lowest'), 'low')
  assert.equal(chooseEffort(m, 'high'), 'high')
  assert.equal(chooseEffort(m, 'max'), 'low', 'a level the model lacks falls back to the lowest')
  assert.equal(chooseEffort(m, 'conversation', 'high'), 'high')
  assert.equal(chooseEffort(m, 'conversation', undefined), undefined)
  assert.equal(chooseEffort({}, 'lowest'), undefined)
  assert.equal(titleFromOutput('**巴黎天气**\nbecause…'), '巴黎天气')
  assert.equal(titleFromOutput('标题：「巴黎天气」'), '巴黎天气')
  assert.equal(titleFromOutput('\n"Weather in Paris."'), 'Weather in Paris')
  assert.equal(firstReply([userMsg(1, 'x'), reply(2, { type: 'text', text: 'a'.repeat(2000) })], 1).length, 1501)
})

test('the app\'s rename pins the title in dsh when the conversation is live here', () => {
  const h = harness({})
  const rename = h.method(RENAME_METHOD)
  assert.equal(h.method('android/other'), undefined)
  assert.deepEqual(rename({ sessionId: 'gone', title: 'Mine' }), { applied: false })
  h.live.set('s5', h.session('s5', []))
  assert.deepEqual(rename({ sessionId: 's5', title: 'Mine' }), { applied: true })
  assert.deepEqual(h.renames, [{ id: 's5', title: 'Mine' }])
  assert.throws(() => rename({ sessionId: 's5' }), TypeError)
})
