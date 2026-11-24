// plugin-guard observations.js (#76): what an agent has read survives an engine restart. The real
// fs-observation-policy (dsh) on a fake event bus: a read recorded in the session log is replayed when
// the session's agent is created again, so `edit` passes; a file changed since stays unobserved.
//   node --test js/test/observations.test.mjs        (DSH_STAGE_NM as for the other stage tests)
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { OBSERVED_TYPE, recordedObservations, setupObservations } from '../packages/plugin-guard/observations.js'

const AGENT = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const NM = resolve(process.env.DSH_STAGE_NM ?? join(AGENT, 'toolchains/out/engine-build/stage/node_modules'))
const policy = await import(pathToFileURL(join(NM, '@deepseek-ai/dsh-fs-observation-policy/lib/index.js')).href)

/** A minimal Cordis-like bus: `on` collects listeners, `emit` calls them, `serial` awaits them. */
function bus() {
  const listeners = new Map()
  return {
    on(name, fn) { (listeners.get(name) ?? listeners.set(name, []).get(name)).push(fn) },
    emit(name, ...args) { for (const fn of listeners.get(name) ?? []) fn(...args) },
    async serial(name, ...args) { for (const fn of listeners.get(name) ?? []) await fn(...args) },
    first(name, ...args) { return listeners.get(name)[0](...args) },
    effect() {},
  }
}

/** A session log: what a resumed process reads back, shared by the old and the new session object. */
function sessionOver(log, id = 's1') {
  return { id, get seq() { return log.length }, eventAt: seq => log[seq] }
}

test('a read survives a new agent for the same session; a changed file does not', async () => {
  const ctx = bus()
  const versions = new Map([['/home/a.txt', 'v1'], ['/home/b.txt', 'v1']])
  const fs = { resolve: async path => ({ targetKey: path, displayPath: path }), stat: async t => versions.has(t.targetKey) ? { type: 'file', version: versions.get(t.targetKey) } : undefined }
  const log = []
  const appendRecord = (_session, type, data) => { log.push({ type, ignorable: true, data }) }
  policy.apply(ctx)
  setupObservations(ctx, { fs: () => fs, appendRecord })

  const before = { session: sessionOver(log) }
  const target = path => ({ targetKey: path, displayPath: path })
  // The model reads both files (dsh-tool-fs emits fs/observed with the tool execution as actor).
  ctx.emit('fs/observed', target('/home/a.txt'), { kind: 'present', version: 'v1' }, { agent: before })
  ctx.emit('fs/observed', target('/home/b.txt'), { kind: 'present', version: 'v1' }, { agent: before })
  ctx.emit('fs/observed', target('/home/a.txt'), { kind: 'present', version: 'v1' }, { agent: before }) // re-read: nothing new
  assert.equal(log.length, 2)
  assert.deepEqual(recordedObservations(sessionOver(log)).map(o => o.key), ['/home/b.txt', '/home/a.txt'])

  // The engine restarts; b.txt is changed meanwhile. The resumed session is a new object.
  versions.set('/home/b.txt', 'v2')
  const after = { session: sessionOver(log) }
  await assert.rejects(ctx.first('fs/edit-intent', target('/home/a.txt'), { agent: after }), e => e.code === 'FS_NOT_OBSERVED')
  await ctx.serial('agent/created', { agent: after, source: 'resume' })
  assert.deepEqual(await ctx.first('fs/edit-intent', target('/home/a.txt'), { agent: after }), { version: 'v1' })
  await assert.rejects(ctx.first('fs/edit-intent', target('/home/b.txt'), { agent: after }), e => e.code === 'FS_NOT_OBSERVED')
  assert.equal(log.length, 2, 'replayed observations are not recorded again')
  assert.ok(log.every(e => e.type === OBSERVED_TYPE))
})
