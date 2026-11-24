// #2 on a built stage: 编辑 / 重试 / 从这里分叉 through plugin-guard's `android/session/fork` with dsh's own fork
// seed, session store and resume. A fork before a message continues without that message's turn (the edited
// message instead), a fork of the whole log continues with all of it, a fork across an engine restart is
// resumed like any stored session, the source conversation is untouched by its forks, and a retried message's
// image goes again by its stored attachment reference.
//   DSH_STAGE_NM=<WORK_DIR>/stage/node_modules node --test js/test/session-fork-engine.test.mjs
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createServer } from 'node:http'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as sleep } from 'node:timers/promises'

const AGENT = resolve(fileURLToPath(new URL('../..', import.meta.url)))
const NM = resolve(process.env.DSH_STAGE_NM ?? join(AGENT, 'toolchains/out/engine-build/stage/node_modules'))
const NATIVE = resolve(process.env.DSH_NATIVE_DIR ?? resolve(NM, '../../host-native'))
const TMP = process.env.TMPDIR ?? join(AGENT, 'toolchains/tmp')

// Fake OpenAI-compatible endpoint (a custom provider, as in the report); records each chat's user messages.
const chats = []
const server = createServer((req, res) => {
  let body = ''
  req.on('data', d => { body += d })
  req.on('end', () => {
    if (req.method === 'GET') { res.writeHead(200, { 'content-type': 'application/json' }); res.end('{"object":"list","data":[{"id":"flash"}]}'); return }
    const json = JSON.parse(body)
    if (Array.isArray(json.tools) && json.tools.length > 0) chats.push(JSON.stringify(json.messages ?? []))
    res.writeHead(200, { 'content-type': 'text/event-stream' })
    const chunk = o => res.write(`data: ${JSON.stringify({ id: 'c', object: 'chat.completion.chunk', created: 1, model: json.model, ...o })}\n\n`)
    chunk({ choices: [{ index: 0, delta: { role: 'assistant', content: 'ok' }, finish_reason: null }] })
    chunk({ choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] })
    chunk({ choices: [], usage: { prompt_tokens: 10, completion_tokens: 1, total_tokens: 11 } })
    res.end('data: [DONE]\n\n')
  })
})

function rpcPeer(readable, writable, onRequest, onNotify = () => {}) {
  let buf = ''
  let id = 0
  const pending = new Map()
  readable.setEncoding('utf8')
  readable.on('data', d => {
    buf += d
    let n
    while ((n = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, n); buf = buf.slice(n + 1)
      if (!line.trim()) continue
      let m
      try { m = JSON.parse(line) } catch { continue }
      if (m.method !== undefined && m.id !== undefined) {
        Promise.resolve(onRequest(m.method, m.params)).then(
          result => writable.write(JSON.stringify({ jsonrpc: '2.0', id: m.id, result }) + '\n'),
          error => writable.write(JSON.stringify({ jsonrpc: '2.0', id: m.id, error: { code: -32000, message: String(error?.message ?? error) } }) + '\n'))
      } else if (m.method !== undefined) {
        onNotify(m.method, m.params)
      } else if (m.method === undefined) {
        const p = pending.get(m.id); pending.delete(m.id)
        if (p) m.error ? p.reject(Object.assign(new Error(m.error.message), m.error)) : p.resolve(m.result)
      }
    }
  })
  return {
    request: (method, params) => new Promise((resolve, reject) => { const i = ++id; pending.set(i, { resolve, reject }); writable.write(JSON.stringify({ jsonrpc: '2.0', id: i, method, params }) + '\n') }),
  }
}

let root
/** Every `session.event` the engines notified, as the app logs them. */
const events = []
const engines = []

/** One engine process on the shared DSH_HOME (an app engine generation). */
async function boot() {
  const dshHome = join(root, 'dsh-home'); const home = join(root, 'home'); const tmp = join(root, 'tmp')
  const env = {
    PATH: process.env.PATH, LANG: 'C.UTF-8', DSH_HOME: dshHome, HOME: home, TMPDIR: tmp, SHELL: '/bin/sh',
    DSH_NATIVE_DIR: NATIVE, DSH_ANDROID_LOCALE: 'zh-CN', DSH_TELEMETRY_DISABLED: '1', DSH_ANDROID_KEY_CUSTOM: 'sk-fake', DSH_BRIDGE_FD: '3',
  }
  const preload = join(NM, '@dsh-android/bundle-android/android-env.cjs')
  const child = spawn(process.execPath, ['--expose-internals', '--require', preload, join(NM, '@dsh-android/bundle-android/main.mjs')], { cwd: '/', env, stdio: ['pipe', 'pipe', 'pipe', 'pipe'] })
  const engine = { child, stderr: '' }
  engines.push(engine)
  child.stderr.setEncoding('utf8')
  child.stderr.on('data', d => { engine.stderr += d })
  rpcPeer(child.stdio[3], child.stdio[3], async method => {
    if (method === 'capability/list') return { tools: [] }
    if (method === 'experience/turn') return { reflect: false }
    throw new Error(`unexpected ${method}`)
  })
  engine.sdk = rpcPeer(child.stdout, child.stdin, () => ({ outcome: 'allowed-once' }), (method, params) => {
    if (method === 'session.event') events.push({ sessionId: params.sessionId, ...params.event })
  })
  await engine.sdk.request('initialize', { cwd: home, provider: 'custom', model: 'flash', approvalRequests: true, capabilities: { userQuestions: true } })
  engine.stop = async () => {
    await Promise.race([engine.sdk.request('shutdown', {}).catch(() => {}), sleep(5000, undefined, { ref: false })])
    child.kill('SIGKILL')
  }
  return engine
}

before(async () => {
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  root = mkdtempSync(join(TMP, 'session-fork-'))
  const dshHome = join(root, 'dsh-home')
  for (const d of [join(dshHome, 'android'), join(root, 'home'), join(root, 'tmp')]) mkdirSync(d, { recursive: true })
  writeFileSync(join(dshHome, 'android/app-layer.patch.yml'), [
    `- id: llm-service-lite\n  config: ${JSON.stringify({ providers: { custom: { displayName: 'custom', interfaceType: 'openai-chat-completions',
      baseURL: `http://127.0.0.1:${server.address().port}/v1`, apiKeyEnv: 'DSH_ANDROID_KEY_CUSTOM', includeUsage: true,
      models: [{ id: 'flash', contextWindow: 131072, defaults: { maxTokens: 8192 } }] } } })}`,
    '- id: tool-web\n  config: {"search":false,"fetch":false,"searchTimeoutMs":60000}',
    '',
  ].join('\n'))
})

after(async () => {
  for (const e of engines) if (e.child.exitCode === null) await e.stop()
  server.closeAllConnections()
  server.close()
})

// What SessionStore.prompt sends for a conversation before its prompt (the route the app sends per conversation, #53).
const describe = (engine, sessionId) => engine.sdk.request('android/session/model', { sessionId, provider: 'custom', model: 'flash' })
const prompt = (engine, sessionId, text) => engine.sdk.request('session/prompt', { sessionId, contentBlocks: [{ type: 'text', text }] })
const idle = (engine, sessionId) => Promise.race([engine.sdk.request('session/wait', { sessionId }), sleep(60_000, undefined, { ref: false }).then(() => { throw new Error(`timeout\n${engine.stderr.slice(-2000)}`) })])

/** The id of [sessionId]'s human message whose text contains [marker]. */
const messageId = (sessionId, marker) => events.find(e => e.sessionId === sessionId && e.type === 'user/message'
  && e.data?.source?.kind === 'user' && JSON.stringify(e.data.content).includes(marker))?.data?.id
const fork = (engine, sessionId, childSessionId, beforeMessageId) =>
  engine.sdk.request('android/session/fork', { sessionId, childSessionId, ...beforeMessageId === undefined ? {} : { beforeMessageId } })
// What SessionStore.prompt does for a conversation with logged events in a new engine generation.
const open = async (engine, sessionId) => { await engine.sdk.request('session/resume', { sessionId }); await describe(engine, sessionId) }

test('edit / retry / fork: forks continue from their cut, across an engine restart, and leave the source alone', async () => {
  const a = crypto.randomUUID()
  const first = await boot()
  await describe(first, a)
  await prompt(first, a, '第一条 [one]')
  await idle(first, a)
  await prompt(first, a, '第二条 [two]')
  await idle(first, a)
  const two = messageId(a, '[two]')
  assert.ok(two, 'the app sees the message id in the logged user/message')

  // 编辑 the second message: the fork keeps the first turn only; the edited message goes to the child.
  const edited = crypto.randomUUID()
  const r = await fork(first, a, edited, two)
  assert.equal(r.sessionId, edited)
  assert.ok(r.inherited > 0)
  assert.deepEqual(r.closers, [])
  // Stored, not open: like a session of an earlier engine, prompt refuses it until it is resumed (#57 path).
  await describe(first, edited)
  const refused = await prompt(first, edited, 'x').then(() => undefined, e => e)
  assert.equal(refused?.message, `session "${edited}" already exists`)
  await open(first, edited)
  await prompt(first, edited, '改过的第二条 [edited]')
  await idle(first, edited)
  let last = chats.at(-1)
  assert.ok(last.includes('[one]') && last.includes('[edited]'), 'the edit keeps the first turn')
  assert.ok(!last.includes('[two]'), 'the edited message replaced the original one')

  // 编辑 the first message: only what precedes its turn and its inbox entry (the session's permission,
  // sandbox, approval and model selection records).
  const fresh = crypto.randomUUID()
  const one = messageId(a, '[one]')
  const before = await fork(first, a, fresh, one)
  const queued = events.find(e => e.sessionId === a && e.type === 'agent/inbox/spliced' && e.data.inserted.some(m => m.id === one)).seq
  assert.ok(queued < events.find(e => e.sessionId === a && e.type === 'turn/start').seq)
  assert.equal(before.inherited, queued)
  if (before.inherited > 0) await open(first, fresh); else await describe(first, fresh)
  await prompt(first, fresh, '重新开始 [fresh]')
  await idle(first, fresh)
  last = chats.at(-1)
  assert.ok(last.includes('[fresh]') && !last.includes('[one]'))
  await first.stop()

  // 从这里分叉 after the last turn, in the next engine generation (the source is resumed first, as the app does).
  const second = await boot()
  await open(second, a)
  const branch = crypto.randomUUID()
  const whole = await fork(second, a, branch)
  assert.ok(whole.inherited > r.inherited)
  await open(second, branch)
  await prompt(second, branch, '第三条 [three]')
  await idle(second, branch)
  last = chats.at(-1)
  assert.ok(last.includes('[one]') && last.includes('[two]') && last.includes('[three]'), 'the fork keeps the whole conversation')

  // The source goes on as it was: none of its forks' messages.
  await prompt(second, a, '第四条 [four]')
  await idle(second, a)
  last = chats.at(-1)
  assert.ok(last.includes('[two]') && last.includes('[four]') && !last.includes('[edited]') && !last.includes('[three]'))

  // 重试 a message with an image: the child gets the image again by its stored attachment reference.
  const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=='
  await second.sdk.request('session/prompt', { sessionId: a, contentBlocks: [{ type: 'text', text: '看图 [pic]' }, { type: 'image', mimeType: 'image/png', data: png }] })
  await idle(second, a)
  const pic = events.find(e => e.sessionId === a && e.type === 'user/message' && JSON.stringify(e.data.content).includes('[pic]')).data
  const blocks = pic.content.filter(b => b.type !== 'text')
  assert.equal(blocks.length, 1)
  assert.ok(blocks[0].attachment?.attachmentId, 'the logged message names its stored image')
  const retried = crypto.randomUUID()
  await fork(second, a, retried, pic.id)
  await open(second, retried)
  await second.sdk.request('session/prompt', { sessionId: retried, contentBlocks: [{ type: 'text', text: '看图 [pic]' }, ...blocks] })
  await idle(second, retried)
  const again = events.find(e => e.sessionId === retried && e.type === 'user/message' && JSON.stringify(e.data.content).includes('[pic]')).data
  assert.notEqual(again.id, pic.id)
  assert.deepEqual(again.content.filter(b => b.type !== 'text'), blocks)

  // Refusals: an unknown message, an id that already exists.
  await assert.rejects(fork(second, a, crypto.randomUUID(), 'no-such-message'), /is not in session/)
  await assert.rejects(fork(second, a, branch), /already exists/)
})
