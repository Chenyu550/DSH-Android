// Patches 14/16 (llm-service-lite names the transport failure's cause and target) and 15 (dsh-http-proxy
// matches Android exclusion-list globs such as `192.168.*`). Runs against a built stage tree:
//   node --test js/test/provider-network.test.mjs
//   DSH_STAGE_NM=<WORK_DIR>/stage/node_modules node --test js/test/provider-network.test.mjs
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { connect as netConnect, createServer } from 'node:net'
import { createServer as createHttpServer, request as httpRequest } from 'node:http'
import { networkInterfaces } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const AGENT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const NM = resolve(process.env.DSH_STAGE_NM ?? join(AGENT, 'toolchains/out/engine-build/stage/node_modules'))
const lite = await import(pathToFileURL(join(NM, '@snnh/dsh-llm-service-lite/lib/index.js')).href)
const proxy = await import(pathToFileURL(join(NM, '@deepseek-ai/dsh-http-proxy/lib/index.js')).href)
const { classifyTransport, describeTransportCause } = lite

const coded = (message, props) => Object.assign(new Error(message), props)
const fetchFailed = cause => new TypeError('fetch failed', { cause })

test('ECONNREFUSED names code, host:port and a gloss; stays TRANSPORT', () => {
  const e = classifyTransport(fetchFailed(coded('connect ECONNREFUSED 192.168.1.5:8080', { code: 'ECONNREFUSED', address: '192.168.1.5', port: 8080 })),
    false, 'http://192.168.1.5:8080/v1')
  assert.equal(e.code, 'TRANSPORT')
  assert.equal(e.message, 'llm-service-lite: provider transport failed: ECONNREFUSED 192.168.1.5:8080 (connection refused)')
})

test('AggregateError (happy eyeballs) uses the first coded attempt', () => {
  const agg = Object.assign(new AggregateError([
    coded('connect ETIMEDOUT 10.0.0.2:443', { code: 'ETIMEDOUT', address: '10.0.0.2', port: 443 }),
    coded('connect ENETUNREACH ::1', { code: 'ENETUNREACH' }),
  ], ''), { code: 'ETIMEDOUT' })
  // The address actually tried is named when it is not the endpoint's own (patch 24).
  assert.equal(describeTransportCause(fetchFailed(agg), 'https://gw.example:8443/v1'), 'ETIMEDOUT gw.example:8443 at 10.0.0.2:443 (connect timed out)')
})

test('target falls back to the cause address (IPv6 bracketed) and default ports', () => {
  assert.equal(describeTransportCause(fetchFailed(coded('x', { code: 'EHOSTUNREACH', address: 'fe80::1', port: 80 }))), 'EHOSTUNREACH [fe80::1]:80 (host unreachable)')
  assert.equal(describeTransportCause(fetchFailed(coded('x', { code: 'ENOTFOUND' })), 'https://api.example.com/v1'), 'ENOTFOUND api.example.com:443 (DNS lookup failed)')
})

test('TLS certificate errors keep their code and message', () => {
  for (const code of ['UNABLE_TO_VERIFY_LEAF_SIGNATURE', 'DEPTH_ZERO_SELF_SIGNED_CERT', 'ERR_TLS_CERT_ALTNAME_INVALID', 'SELF_SIGNED_CERT_IN_CHAIN']) {
    const d = describeTransportCause(fetchFailed(coded('unable to verify the first certificate', { code })), 'https://192.168.1.5:8443/v1')
    assert.equal(d, `${code} 192.168.1.5:8443 (TLS certificate rejected: unable to verify the first certificate)`)
  }
})

test('header timeout, uncoded causes, nothing known', () => {
  assert.equal(describeTransportCause(fetchFailed(coded('Headers Timeout Error', { code: 'UND_ERR_HEADERS_TIMEOUT' })), 'http://h:1/v1'), 'UND_ERR_HEADERS_TIMEOUT h:1 (no response headers in time)')
  assert.equal(describeTransportCause(fetchFailed(new Error('unexpected redirect')), 'http://h:1/v1'), 'h:1 (unexpected redirect)')
  // patch 16: no cause at all still names the error itself
  assert.equal(describeTransportCause(new TypeError('fetch failed')), '(TypeError: fetch failed)')
  assert.equal(classifyTransport(new TypeError('fetch failed')).message, 'llm-service-lite: provider transport failed: (TypeError: fetch failed)')
})

test('never leaks the URL path, query or credentials', () => {
  const secret = 'sk-test-SECRET123456'
  const d = describeTransportCause(fetchFailed(coded(`request to https://user:${secret}@api.example.com/v1/chat?key=${secret} failed`, { code: 'ECONNRESET' })),
    `https://user:${secret}@api.example.com/v1?key=${secret}`)
  assert.ok(!d.includes(secret), d)
  assert.equal(d, 'ECONNRESET api.example.com:443 (connection reset)')
  const u = describeTransportCause(fetchFailed(new Error(`bad https://api.example.com/v1?key=${secret}`)), undefined)
  assert.equal(u, '(bad https://api.example.com)')
})

test('retryable shapes: bare codes', () => {
  for (const code of ['EHOSTUNREACH', 'ENETUNREACH', 'UND_ERR_HEADERS_TIMEOUT', 'ECONNREFUSED']) assert.equal(classifyTransport(coded('x', { code })).code, 'TRANSPORT', code)
  const nf = classifyTransport(coded('getaddrinfo ENOTFOUND x', { code: 'ENOTFOUND' }), false, 'https://x/v1')
  assert.equal(nf.code, 'UNKNOWN')
  assert.equal(nf.message, 'llm-service-lite: provider request failed: ENOTFOUND x:443 (DNS lookup failed)')
  assert.equal(classifyTransport(fetchFailed(coded('x', { code: 'ECONNREFUSED' })), true).code, 'ABORTED')
})

test('real undici failure: refused loopback port', async () => {
  const srv = createServer(); await new Promise(r => srv.listen(0, '127.0.0.1', r))
  const { port } = srv.address(); await new Promise(r => srv.close(r))
  const base = `http://127.0.0.1:${port}/v1`
  const error = await fetch(`${base}/chat/completions`, { method: 'POST' }).then(() => null, e => e)
  const e = classifyTransport(error, false, base)
  assert.equal(e.code, 'TRANSPORT')
  assert.equal(e.message, `llm-service-lite: provider transport failed: ECONNREFUSED 127.0.0.1:${port} (connection refused)`)
})

test('patch 15: Android exclusion globs bypass the proxy', async () => {
  const env = new Map(Object.entries({ HTTPS_PROXY: 'http://10.9.9.9:3128', HTTP_PROXY: 'http://10.9.9.9:3128', NO_PROXY: '192.168.*,*.lan,10.*,corp.example' }).map(([k, v]) => [k, { value: v }]))
  const saved = { ...process.env }
  const dispose = await proxy.installProxyFromEnvironment(env, () => {})
  try {
    const route = u => proxy.proxyRouteFor(new URL(u)).proxied
    assert.equal(route('http://192.168.1.5:8080/v1'), false)
    assert.equal(route('http://10.1.2.3/'), false)
    assert.equal(route('http://pc.lan/'), false)
    assert.equal(route('https://a.corp.example/'), false)
    assert.equal(route('http://127.0.0.1:1/'), false)
    assert.equal(route('https://api.openai.com/v1'), true)
    assert.equal(route('http://192.169.1.5/'), true)
    assert.equal(route('http://1192.168.1.5.example/'), true)
  } finally {
    await dispose()
    for (const k of Object.keys(process.env)) if (!(k in saved)) delete process.env[k]
    Object.assign(process.env, saved)
  }
})

// #75 (patch 24): a proxy nothing listens on (a capture / VPN app's local proxy left configured after
// it stopped) is skipped like Android's HTTP stack does, instead of failing every request.
test('patch 24: refused proxy named in the cause', () => {
  const saved = process.env.HTTPS_PROXY
  process.env.HTTPS_PROXY = 'http://127.0.0.1:9000'
  try {
    const refused = fetchFailed(coded('connect ECONNREFUSED 127.0.0.1:9000', { code: 'ECONNREFUSED', address: '127.0.0.1', port: 9000 }))
    assert.equal(describeTransportCause(refused, 'https://ollama.com/v1'), 'ECONNREFUSED ollama.com:443 via proxy 127.0.0.1:9000 (connection refused)')
  } finally { if (saved === undefined) delete process.env.HTTPS_PROXY; else process.env.HTTPS_PROXY = saved }
})

const lanAddress = Object.values(networkInterfaces()).flat().find(a => a?.family === 'IPv4' && !a.internal)?.address

test('patch 24: a dead proxy falls back to a direct connection; a live one is used', { skip: lanAddress === undefined && 'no non-loopback IPv4 address' }, async () => {
  const target = createHttpServer((_req, res) => res.end('direct'))
  await new Promise(r => target.listen(0, '0.0.0.0', r))
  const url = `http://${lanAddress}:${target.address().port}/`
  const dead = createServer(); await new Promise(r => dead.listen(0, '127.0.0.1', r))
  const deadPort = dead.address().port; await new Promise(r => dead.close(r))
  // A live proxy: absolute-form requests are answered by it, CONNECT tunnels are relayed.
  let proxied = 0
  const live = createHttpServer((req, res) => {
    proxied += 1
    const up = httpRequest(req.url, { method: req.method, headers: req.headers }, r => { res.writeHead(r.statusCode, r.headers); r.pipe(res) })
    req.pipe(up)
  })
  live.on('connect', (req, socket, head) => {
    proxied += 1
    const [host, port] = req.url.split(':')
    const up = netConnect(Number(port), host, () => { socket.write('HTTP/1.1 200 Connection Established\r\n\r\n'); up.write(head); up.pipe(socket); socket.pipe(up) })
    up.on('error', () => socket.destroy())
  })
  await new Promise(r => live.listen(0, '127.0.0.1', r))
  const saved = { ...process.env }
  const run = async proxyUrl => {
    const env = new Map(Object.entries({ HTTPS_PROXY: proxyUrl, HTTP_PROXY: proxyUrl }).map(([k, v]) => [k, { value: v }]))
    const dispose = await proxy.installProxyFromEnvironment(env, () => {})
    try {
      assert.equal(proxy.proxyRouteFor(new URL(url)).proxied, true)
      return await (await fetch(url)).text()
    } finally {
      await dispose()
      for (const k of Object.keys(process.env)) if (!(k in saved)) delete process.env[k]
      Object.assign(process.env, saved)
    }
  }
  try {
    assert.equal(await run(`http://127.0.0.1:${deadPort}`), 'direct')
    assert.equal(proxied, 0)
    assert.equal(await run(`http://127.0.0.1:${live.address().port}`), 'direct')
    assert.ok(proxied > 0, 'the live proxy carried the request')
  } finally {
    live.closeAllConnections(); target.closeAllConnections()
    await new Promise(r => live.close(r)); await new Promise(r => target.close(r))
  }
})
