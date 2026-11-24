// plugin-guard translate.js (#15): `android/reasoning/translate` over dsh's translator service.
// Pure parts on a fake service; then dsh's real Translator (cordis Context) against fake Bing / Google
// endpoints on node:http: long text in order-preserving pieces, Google when Bing cannot be reached,
// a malformed answer reported by code without trying further.
//   node --test js/test/translate.test.mjs        (DSH_STAGE_NM as for the other stage tests)
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { METHOD, apply, chunks, translateReasoning } from '../packages/plugin-guard/translate.js'

const AGENT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const NM = resolve(process.env.DSH_STAGE_NM ?? join(AGENT, 'toolchains/out/engine-build/stage/node_modules'))

class FakeError extends Error { constructor(code) { super(code); this.code = code } }

/** A translator service that upper-cases, failing for the providers in [down] with [code]. */
function fakeTranslator({ max = 4000, down = [], code = 'TRANSLATION_REQUEST_FAILED' } = {}) {
  const calls = []
  return {
    calls,
    maxTextChars: max,
    resolve: r => ({ ...r, sourceLanguage: 'auto' }),
    translate: async spec => {
      calls.push(spec)
      if (down.includes(spec.provider)) throw new FakeError(code)
      return spec.text.toUpperCase()
    },
  }
}

test('chunks: pieces within the limit, at paragraph, then line, then space breaks; they join back', () => {
  const text = 'aaaa bbbb\n\ncccc dddd\neeee ffff gggg'
  for (const max of [2, 5, 9, 12, 40]) {
    const parts = chunks(text, max)
    assert.equal(parts.join(''), text)
    assert.ok(parts.every(p => p.length <= max), `max ${max}: ${JSON.stringify(parts)}`)
  }
  assert.deepEqual(chunks('aaaa bbbb\n\ncccc dddd', 15), ['aaaa bbbb\n\n', 'cccc dddd'])
  assert.deepEqual(chunks('aaaa\nbbbb cccc', 12), ['aaaa\n', 'bbbb cccc'])
  assert.deepEqual(chunks('', 10), [''])
  // A surrogate pair is never cut.
  assert.deepEqual(chunks('ab😀cd', 3), ['ab', '😀c', 'd'])
})

test('translates piece by piece, keeping the whitespace between pieces', async () => {
  const tr = fakeTranslator({ max: 12 })
  const r = await translateReasoning(tr, { text: 'first part\n\nsecond one\n', targetLanguage: 'zh-CN' })
  assert.deepEqual(r, { text: 'FIRST PART\n\nSECOND ONE\n', provider: 'bing' })
  assert.deepEqual(tr.calls.map(c => c.text), ['first part', 'second one'])
  assert.ok(tr.calls.every(c => c.targetLanguage === 'zh-CN' && c.provider === 'bing' && c.sessionId === undefined))
})

test('Google when Bing cannot be reached; an error code when neither can', async () => {
  assert.deepEqual(await translateReasoning(fakeTranslator({ down: ['bing'] }), { text: 'x', targetLanguage: 'ru' }), { text: 'X', provider: 'google' })
  const r = await translateReasoning(fakeTranslator({ down: ['bing', 'google'], code: 'TRANSLATION_TIMEOUT' }), { text: 'x', targetLanguage: 'ru' })
  assert.equal(r.error.code, 'TRANSLATION_TIMEOUT')
})

test('a malformed answer is reported without trying the next provider', async () => {
  const tr = fakeTranslator({ down: ['bing', 'google'], code: 'TRANSLATION_INVALID_RESPONSE' })
  const r = await translateReasoning(tr, { text: 'x', targetLanguage: 'en' })
  assert.equal(r.error.code, 'TRANSLATION_INVALID_RESPONSE')
  assert.deepEqual(tr.calls.map(c => c.provider), ['bing'])
})

test('bad params fail the request; the method is registered under its name only', async () => {
  await assert.rejects(translateReasoning(fakeTranslator(), { text: 'x', targetLanguage: 'en; rm' }), TypeError)
  await assert.rejects(translateReasoning(fakeTranslator(), { targetLanguage: 'en' }), TypeError)
  const resolvers = []
  apply({ translator: fakeTranslator(), on: (event, fn) => { assert.equal(event, 'sdk-extensions/resolve'); resolvers.push(fn) } })
  assert.equal(resolvers[0]('android/other'), undefined)
  assert.deepEqual(await resolvers[0](METHOD)({ text: 'hi', targetLanguage: 'en' }), { text: 'HI', provider: 'bing' })
})

/** A fake endpoint; [answer] maps (url, body) → [status, json]. */
async function endpoint(answer) {
  const seen = []
  const server = createServer((req, res) => {
    let body = ''
    req.on('data', d => { body += d })
    req.on('end', () => {
      const url = new URL(req.url, 'http://x')
      seen.push({ url, body })
      const [status, json] = answer(url, body)
      res.writeHead(status, { 'content-type': 'application/json' }).end(JSON.stringify(json))
    })
  })
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  return { url: `http://127.0.0.1:${server.address().port}/t`, seen, close: () => server.close() }
}

async function realTranslator(config) {
  const { Context } = await import(pathToFileURL(join(NM, '@deepseek-ai/cordis/lib/index.js')).href)
  const Translator = (await import(pathToFileURL(join(NM, '@deepseek-ai/dsh-experimental-translator/lib/index.js')).href)).default
  const ctx = new Context()
  ctx.plugin(Translator, config)
  for (let i = 0; i < 100 && ctx.get('translator') === undefined; i++) await new Promise(r => setTimeout(r, 10))
  return ctx.get('translator')
}

test('dsh translator: Bing in pieces, in order, to zh-Hans', async () => {
  const bing = await endpoint((url, body) => [200, [{ translations: [{ text: `<${url.searchParams.get('to')}:${JSON.parse(body)[0]}>` }] }]])
  try {
    const tr = await realTranslator({ bingEndpoint: bing.url, maxTextChars: 20 })
    const text = 'one two three four\n\nfive six seven eight nine'
    const r = await translateReasoning(tr, { text, targetLanguage: 'zh' })
    assert.deepEqual(r, { text: '<zh-Hans:one two three four>\n\n<zh-Hans:five six seven> <zh-Hans:eight nine>', provider: 'bing' })
    assert.equal(bing.seen.length, 3)
  } finally { bing.close() }
})

test('dsh translator: Google when Bing refuses; the error code when the answer is malformed', async () => {
  const bing = await endpoint(() => [503, {}])
  const google = await endpoint((_url, body) => [200, [[[`[${new URLSearchParams(body).get('tl')}] ${new URLSearchParams(body).get('q')}`]]]])
  const broken = await endpoint(() => [200, { nope: true }])
  try {
    const tr = await realTranslator({ bingEndpoint: bing.url, googleEndpoint: google.url })
    assert.deepEqual(await translateReasoning(tr, { text: 'Привет', targetLanguage: 'en' }), { text: '[en] Привет', provider: 'google' })
    const tr2 = await realTranslator({ bingEndpoint: broken.url, googleEndpoint: google.url })
    const r = await translateReasoning(tr2, { text: 'x', targetLanguage: 'en' })
    assert.equal(r.error.code, 'TRANSLATION_INVALID_RESPONSE')
    assert.ok(!r.error.message.includes('x '), 'no source text in the message')
  } finally { bing.close(); google.close(); broken.close() }
})

test('engine: the android profile mounts the translator and answers android/reasoning/translate', async () => {
  const { spawn } = await import('node:child_process')
  const { mkdirSync, mkdtempSync, writeFileSync } = await import('node:fs')
  const native = resolve(process.env.DSH_NATIVE_DIR ?? resolve(NM, '../../host-native'))
  const bing = await endpoint((url, body) => [200, [{ translations: [{ text: `${url.searchParams.get('to')}: ${JSON.parse(body)[0]}` }] }]])
  const root = mkdtempSync(join(process.env.TMPDIR ?? join(AGENT, 'toolchains/tmp'), 'translate-'))
  const dshHome = join(root, 'dsh-home'); const home = join(root, 'home'); const tmp = join(root, 'tmp')
  for (const d of [join(dshHome, 'android'), home, tmp]) mkdirSync(d, { recursive: true })
  // The translator row's config, as a user layer could set it: endpoints on the fake server.
  writeFileSync(join(dshHome, 'android/app-layer.patch.yml'), `- id: translator\n  config: ${JSON.stringify({ bingEndpoint: bing.url, googleEndpoint: bing.url })}\n`)
  const env = { PATH: process.env.PATH, LANG: 'C.UTF-8', DSH_HOME: dshHome, HOME: home, TMPDIR: tmp, SHELL: '/bin/sh', DSH_NATIVE_DIR: native, DSH_TELEMETRY_DISABLED: '1' }
  const child = spawn(process.execPath, ['--expose-internals', '--require', join(NM, '@dsh-android/bundle-android/android-env.cjs'), join(NM, '@dsh-android/bundle-android/main.mjs')], { cwd: '/', env, stdio: ['pipe', 'pipe', 'pipe'] })
  let stderr = ''
  child.stderr.setEncoding('utf8')
  child.stderr.on('data', d => { stderr += d })
  let buf = ''
  const pending = new Map()
  child.stdout.setEncoding('utf8')
  child.stdout.on('data', d => {
    buf += d
    for (let n; (n = buf.indexOf('\n')) >= 0;) {
      const line = buf.slice(0, n); buf = buf.slice(n + 1)
      let m; try { m = JSON.parse(line) } catch { continue }
      if (m.method === undefined && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id) }
    }
  })
  const request = (method, params) => new Promise(r => { const id = pending.size + 1 + Math.random(); pending.set(id, r); child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n') })
  try {
    const timeout = new Promise((_, reject) => setTimeout(() => reject(new Error(`timeout\n${stderr.slice(-3000)}`)), 60_000).unref())
    const res = await Promise.race([request('android/reasoning/translate', { text: 'Let me think.', targetLanguage: 'ru' }), timeout])
    assert.deepEqual(res.result, { text: 'ru: Let me think.', provider: 'bing' }, JSON.stringify(res.error ?? res) + stderr.slice(-2000))
  } finally {
    child.kill('SIGKILL')
    bing.close()
  }
})
