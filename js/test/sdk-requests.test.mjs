// SDK server → app requests end to end on a built stage tree (dsh 0.2.2's own `approval/request` and
// `user-question/ask`, which js patch 08 used to backport): the real engine (profile android, main.mjs)
// against a scripted fake OpenAI-compatible model, with a fake capability bridge serving a `write`-risk device tool.
//   - a conversation's approval names its session; the allowed call runs, the rejected one does not;
//   - a subagent never reaches the app: dsh pins a delegated agent's approval policy to `never` and refuses
//     its questions (DELEGATED_CALLER), so both fail inside the engine and the subagent reports back;
//   - ask_user_question reaches the app as `user-question/ask` (requestId, sessionId, questions); the
//     answer reaches the model;
//   - plugin-guard on dsh 0.2.2's native tool executor: a read-only shell command runs without asking, a
//     mutating one outside HOME asks, `rm -rf /sdcard` is hard-blocked without asking.
//   DSH_STAGE_NM=<WORK_DIR>/stage/node_modules node --test js/test/sdk-requests.test.mjs
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

// ── scripted fake model ──────────────────────────────────────────────────────
/** Per case: a queue of replies; a reply is { text } or { tools: [[name, args], …] }. */
const scripts = new Map()
const requests = []
let callSeq = 0
function sse(res, reply) {
  res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
  const chunk = o => res.write(`data: ${JSON.stringify({ id: 'c', object: 'chat.completion.chunk', created: 1, model: 'fake-chat', ...o })}\n\n`)
  if (reply.text) chunk({ choices: [{ index: 0, delta: { role: 'assistant', content: reply.text }, finish_reason: null }] })
  const tools = reply.tools ?? []
  tools.forEach(([name, args], index) => chunk({ choices: [{ index: 0, delta: { tool_calls: [{ index, id: `call_${++callSeq}`, type: 'function', function: { name, arguments: JSON.stringify(args) } }] }, finish_reason: null }] }))
  chunk({ choices: [{ index: 0, delta: {}, finish_reason: tools.length > 0 ? 'tool_calls' : 'stop' }] })
  chunk({ choices: [], usage: { prompt_tokens: 1000, completion_tokens: 10, total_tokens: 1010 } })
  res.end('data: [DONE]\n\n')
}
const server = createServer((req, res) => {
  let body = ''
  req.on('data', d => { body += d })
  req.on('end', () => {
    if (req.method === 'GET' && req.url === '/v1/models') { res.writeHead(200, { 'content-type': 'application/json' }); res.end('{"object":"list","data":[{"id":"fake-chat"}]}'); return }
    const json = JSON.parse(body)
    // The case is named in the first user message (a subagent's is its prompt).
    const id = /\[case:([a-z0-9-]+)\]/.exec(JSON.stringify(json.messages ?? []))?.[1]
    if (id === undefined || !Array.isArray(json.tools) || json.tools.length === 0) { sse(res, { text: 'ok' }); return }
    requests.push({ id, body: json })
    sse(res, (scripts.get(id) ?? []).shift() ?? { text: 'Done.' })
  })
})

// ── engine + fake bridge ─────────────────────────────────────────────────────
function rpcPeer(readable, writable, onRequest, onNotify) {
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
        Promise.resolve().then(() => onRequest(m.method, m.params)).then(
          result => writable.write(JSON.stringify({ jsonrpc: '2.0', id: m.id, result }) + '\n'),
          error => writable.write(JSON.stringify({ jsonrpc: '2.0', id: m.id, error: { code: -32000, message: String(error?.message ?? error) } }) + '\n'))
      } else if (m.method !== undefined) onNotify?.(m.method, m.params)
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

const TOOLS = [
  { name: 'device_note', group: 'apps', risk: 'write', needsUnlock: false, description: 'Save a note on the device (test tool).',
    inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] } },
]
const notes = []
const invokes = []
const approvals = []
const questions = []
/** Answers by tool-call order: 'allowed-once' unless the test queues another. */
const approvalAnswers = []
let main
let root

before(async () => {
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  root = mkdtempSync(join(TMP, 'sdk-requests-'))
  const dshHome = join(root, 'dsh-home'); const home = join(root, 'home'); const tmp = join(root, 'tmp')
  for (const d of [join(dshHome, 'android'), home, tmp]) mkdirSync(d, { recursive: true })
  const base = `http://127.0.0.1:${server.address().port}/v1`
  writeFileSync(join(dshHome, 'android/app-layer.patch.yml'), [
    `- id: llm-service-lite\n  config: ${JSON.stringify({ providers: { fake: { displayName: 'Fake', interfaceType: 'openai-chat-completions', baseURL: base, apiKeyEnv: 'DSH_ANDROID_KEY_FAKE', includeUsage: true, models: [{ id: 'fake-chat', contextWindow: 131072, defaults: { maxTokens: 8192 } }] } } })}`,
    '- id: tool-web\n  config: {"search":false,"fetch":false,"searchTimeoutMs":60000}',
    '',
  ].join('\n'))
  const env = {
    PATH: process.env.PATH, LANG: 'C.UTF-8', DSH_HOME: dshHome, HOME: home, TMPDIR: tmp, SHELL: '/bin/sh',
    DSH_NATIVE_DIR: NATIVE, DSH_ANDROID_LOCALE: 'zh-CN', DSH_TELEMETRY_DISABLED: '1', DSH_ANDROID_KEY_FAKE: 'sk-fake', DSH_BRIDGE_FD: '3',
  }
  const preload = join(NM, '@dsh-android/bundle-android/android-env.cjs')
  const child = spawn(process.execPath, ['--expose-internals', '--require', preload, join(NM, '@dsh-android/bundle-android/main.mjs')], { cwd: '/', env, stdio: ['pipe', 'pipe', 'pipe', 'pipe'] })
  main = { child, stderr: '' }
  child.stderr.setEncoding('utf8')
  child.stderr.on('data', d => { main.stderr += d })
  rpcPeer(child.stdio[3], child.stdio[3], async (method, params) => {
    if (method === 'capability/list') return { tools: TOOLS }
    if (method === 'capability/invoke') { invokes.push(params); return { ok: true, content: [{ type: 'text', text: `Saved ${params.args?.text}.` }] } }
    if (method === 'experience/turn') return { reflect: false }
    throw new Error(`unexpected ${method}`)
  })
  main.sdk = rpcPeer(child.stdout, child.stdin, async (method, params) => {
    if (method === 'approval/request') { approvals.push({ params }); return { outcome: approvalAnswers.shift() ?? 'allowed-once' } }
    if (method === 'user-question/ask') {
      questions.push(params)
      const q = params.questions[0]
      return { answers: [{ id: q.id, selected: [q.options[1].label] }] }
    }
    throw new Error(`unexpected ${method}`)
  }, (method, params) => notes.push({ method, params }))
  await main.sdk.request('initialize', { cwd: home, provider: 'fake', model: 'fake-chat', approvalRequests: true, capabilities: { userQuestions: true } })
})

after(async () => {
  if (main.child.exitCode === null && main.child.signalCode === null) await Promise.race([main.sdk.request('shutdown', {}).catch(() => {}), sleep(5000)])
  main.child.kill('SIGKILL')
  server.closeAllConnections()
  server.close()
})

const load = ['load_tools', { names: ['device_note', 'subagent'] }]
const note = t => ['device_note', { text: t }]
const text = (id, t) => ({ sessionId: id, contentBlocks: [{ type: 'text', text: t }] })
const until = (what, ms = 60_000) => Promise.race([what, sleep(ms).then(() => { throw new Error(`timeout\n${main.stderr.slice(-3000)}`) })])
const results = sessionId => notes.filter(n => n.method === 'session.event' && n.params.sessionId === sessionId && n.params.event.type === 'tool/result').map(n => JSON.stringify(n.params.event.data))

async function run(id, prompt) {
  const sessionId = `sr-${id}-${Date.now()}`
  await main.sdk.request('android/session/permission', { sessionId, preset: 'workspace-write' })
  await main.sdk.request('session/prompt', text(sessionId, `[case:${id}] ${prompt}`))
  await until(main.sdk.request('session/wait', { sessionId }))
  return sessionId
}

test('a conversation\'s approval names its own session; allowed runs, rejected does not', async () => {
  scripts.set('own', [{ tools: [load] }, { tools: [note('one')] }, { tools: [note('two')] }, { text: '好了。' }])
  approvalAnswers.push('allowed-once', 'rejected')
  const before = approvals.length
  const sessionId = await run('own', '记两条笔记。')
  const mine = approvals.slice(before).map(a => a.params)
  assert.equal(mine.length, 2, main.stderr.slice(-2000))
  for (const p of mine) {
    assert.equal(p.sessionId, sessionId)
    assert.equal(p.toolName, 'device_note')
    assert.equal(typeof p.callId, 'string')
  }
  assert.deepEqual(invokes.filter(i => i.sessionId === sessionId).map(i => i.args.text), ['one'], 'only the allowed call ran')
})

test('a subagent never asks: dsh refuses its approvals and questions without reaching the app', async () => {
  const question = { id: 'q1', header: '确认', question: '要记吗？', options: [{ label: '记', description: 'yes' }, { label: '不记', description: 'no' }], multiSelect: false }
  scripts.set('parent', [{ tools: [load] }, { tools: [['subagent', { description: '记笔记', prompt: '[case:child] 记一条笔记：kid。' }]] }, { text: '已交给子代理。' }])
  scripts.set('child', [{ tools: [note('kid')] }, { tools: [['ask_user_question', { questions: [question] }]] }, { text: '没有权限，交回给你。' }])
  const [approvalsBefore, questionsBefore] = [approvals.length, questions.length]
  const sessionId = await run('parent', '让子代理记一条笔记。')
  const started = notes.find(n => n.method === 'subagent.started' && n.params.parentSessionId === sessionId)
  assert.ok(started, 'subagent.started')
  const child = started.params.childSessionId
  assert.ok(notes.some(n => n.method === 'subagent.finished' && n.params.childSessionId === child), 'subagent.finished')
  // dsh pins a delegated agent's approval policy to `never`: the call is refused in the engine.
  assert.deepEqual(approvals.slice(approvalsBefore).map(a => a.params), [])
  assert.deepEqual(questions.slice(questionsBefore), [])
  assert.ok(!invokes.some(i => i.args.text === 'kid'), 'the refused call did not run')
  const childResults = notes.filter(n => n.method === 'session.event' && n.params.sessionId === child && n.params.event.type === 'tool/result').map(n => n.params.event.data.message)
  assert.equal(childResults.length, 2)
  assert.ok(childResults.every(m => m.isError === true), JSON.stringify(childResults))
})

test('ask_user_question reaches the app as user-question/ask and the answer reaches the model', async () => {
  const question = { id: 'q1', header: '颜色', question: '选哪个颜色？', options: [{ label: '红', description: 'red' }, { label: '蓝', description: 'blue' }], multiSelect: false }
  scripts.set('ask', [{ tools: [['ask_user_question', { questions: [question] }]] }, { text: '好的，蓝色。' }])
  const before = questions.length
  const sessionId = await run('ask', '问我喜欢的颜色。')
  const asked = questions.slice(before)
  assert.equal(asked.length, 1, main.stderr.slice(-2000))
  assert.equal(asked[0].sessionId, sessionId)
  assert.equal(typeof asked[0].requestId, 'string')
  assert.equal(asked[0].questions[0].id, 'q1')
  assert.deepEqual(asked[0].questions[0].options.map(o => o.label), ['红', '蓝'])
  assert.ok(results(sessionId).some(r => r.includes('蓝')), results(sessionId).join('\n'))
})

test('plugin-guard: read-only shell runs, a write outside HOME asks, the hardline blocks without asking', async () => {
  const outside = join(root, 'outside.txt')
  scripts.set('shell', [{ tools: [['bash', { command: 'ls /', description: 'list' }]] }, { tools: [['bash', { command: `touch ${outside}`, description: 'touch' }]] }, { tools: [['bash', { command: 'rm -rf /sdcard', description: 'wipe' }]] }, { text: '完成。' }])
  approvalAnswers.push('rejected')
  const before = approvals.length
  const sessionId = await run('shell', '跑三条命令。')
  assert.deepEqual(approvals.slice(before).map(a => [a.params.toolName, a.params.sessionId]), [['bash', sessionId]], 'only the touch asked')
  const res = notes.filter(n => n.method === 'session.event' && n.params.sessionId === sessionId && n.params.event.type === 'tool/result').map(n => n.params.event.data.message)
  assert.equal(res.length, 3)
  assert.equal(res[0].isError, false, JSON.stringify(res[0]))
  assert.equal(res[1].isError, true)
  assert.match(JSON.stringify(res[2].content), /HARDLINE_BLOCKED/)
})
