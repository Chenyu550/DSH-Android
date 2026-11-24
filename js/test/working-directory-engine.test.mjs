// #76: a conversation's working directory (工作目录) on a built stage tree. The app sends dsh 0.2.2's
// `session/working-directory/set` right before a new conversation's first `session/prompt` (SessionStore
// sendPendingWorkingDirectory): the first model request already runs in that folder, the log has the
// `working-directory/change` record before the turn and dsh's notice (`source.kind: working-directory`,
// the chat's 工作目录 line) inside it, and a resumed session keeps the folder.
//   DSH_STAGE_NM=<WORK_DIR>/stage/node_modules node --test js/test/working-directory-engine.test.mjs
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

// Fake OpenAI-compatible endpoint: records each chat request's messages.
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
      } else if (m.method !== undefined) onNotify(m.method, m.params)
      else {
        const p = pending.get(m.id); pending.delete(m.id)
        if (p) m.error ? p.reject(Object.assign(new Error(m.error.message), m.error)) : p.resolve(m.result)
      }
    }
  })
  return {
    request: (method, params) => new Promise((resolve, reject) => { const i = ++id; pending.set(i, { resolve, reject }); writable.write(JSON.stringify({ jsonrpc: '2.0', id: i, method, params }) + '\n') }),
  }
}

let root, home
const engines = []
const events = []

async function boot() {
  const dshHome = join(root, 'dsh-home'); const tmp = join(root, 'tmp')
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
  root = mkdtempSync(join(TMP, 'workdir-'))
  home = join(root, 'home')
  const dshHome = join(root, 'dsh-home')
  for (const d of [join(dshHome, 'android'), join(home, 'blog'), join(root, 'tmp')]) mkdirSync(d, { recursive: true })
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

const idle = (engine, sessionId) => Promise.race([engine.sdk.request('session/wait', { sessionId }), sleep(60_000, undefined, { ref: false }).then(() => { throw new Error(`timeout\n${engine.stderr.slice(-2000)}`) })])
const prompt = (engine, sessionId, text) => engine.sdk.request('session/prompt', { sessionId, contentBlocks: [{ type: 'text', text }] })

test('set before the first prompt: the first request runs there; record before the turn, notice inside it; kept on resume', async () => {
  const engine = await boot()
  const sessionId = 'wd-new-1'
  const blog = join(home, 'blog')
  const set = await engine.sdk.request('session/working-directory/set', { sessionId, path: blog })
  assert.equal(set.cwd, blog)
  await prompt(engine, sessionId, 'write a post')
  await idle(engine, sessionId)
  // The system prompt's working-directory context of the first request (messages JSON-encoded twice).
  assert.ok(chats.at(-1).includes(`Current working directory: \\"${blog}\\"`), chats.at(-1).slice(0, 3000))
  const mine = events.filter(e => e.sessionId === sessionId)
  const change = mine.findIndex(e => e.type === 'working-directory/change')
  const start = mine.findIndex(e => e.type === 'turn/start')
  const notice = mine.findIndex(e => e.type === 'user/message' && e.data?.source?.kind === 'working-directory')
  assert.equal(mine[change]?.data?.cwd, blog, JSON.stringify(mine.map(e => e.type)))
  assert.ok(change < start, 'the change record comes before the turn')
  assert.ok(notice > start, `the notice is inside the turn: ${JSON.stringify(mine.map(e => [e.type, e.data?.source?.kind]))}`)
  await engine.stop()

  // A new engine process: resume, then ask where it is.
  const next = await boot()
  await next.sdk.request('session/resume', { sessionId })
  assert.deepEqual(await next.sdk.request('session/working-directory/get', { sessionId }), { cwd: blog })
  // A folder that is not there is refused (the app keeps the old one and says so).
  await assert.rejects(next.sdk.request('session/working-directory/set', { sessionId, path: join(home, 'missing') }))
  await next.stop()
})
