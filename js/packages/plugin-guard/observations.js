// Read-before-edit across engine restarts (#76): dsh's fs-observation-policy keeps what each agent
// has read (`fs/observed`: target + file version) in memory, keyed by the live session object. On
// the phone the engine process is short-lived (idle stop after 5 min, restarts for plugins, routes,
// proxy, crashes), and a resumed conversation starts with nothing observed: the model, which sees
// its earlier `read` in the history, gets FS_NOT_OBSERVED on its next `edit` / `write`.
//
// Each present observation is kept as an ignorable session record
// (`plugin:android-guard/fs-observed`, never model-visible); when an agent is created again for a
// session (resume), the observations of its log are replayed for every file whose current version
// is still the recorded one. A file changed since (by the user, a shell command, another app) stays
// unobserved, so the policy's freshness guarantee is unchanged.

export const OBSERVED_TYPE = 'plugin:android-guard/fs-observed'
/** Distinct files restored per agent (newest first); older ones are re-read on demand. */
export const RESTORE_LIMIT = 200

let defaultAppend
async function appendRecord(session, type, data) {
  defaultAppend ??= import('@deepseek-ai/dsh-session').then(m => m.appendPluginRecord).catch(() => null)
  const append = await defaultAppend
  if (typeof append === 'function') append(session, type, data)
}

/** The newest recorded observation per file in [session]'s log, newest first, at most [limit]. */
export function recordedObservations(session, limit = RESTORE_LIMIT) {
  const out = new Map()
  try {
    for (let seq = Number(session.seq) - 1; seq >= 0 && out.size < limit; seq -= 1) {
      const event = session.eventAt(seq)
      if (event?.type !== OBSERVED_TYPE || event.ignorable !== true) continue
      const { key, path, version } = event.data ?? {}
      if (typeof key !== 'string' || typeof path !== 'string' || typeof version !== 'string' || out.has(key)) continue
      out.set(key, { key, path, version })
    }
  } catch {}
  return [...out.values()]
}

/**
 * Wires the recording and the restore. `fs()` returns dsh's fs service (resolve / stat), or
 * undefined while it is not available; `appendRecord` is injectable for tests.
 */
export function setupObservations(ctx, { fs = () => ctx.get('fs'), appendRecord: append = appendRecord, log = () => {} } = {}) {
  /** session → (targetKey → version) last recorded, so a re-read of an unchanged file adds nothing. */
  const recorded = new WeakMap()
  const recordedOf = session => {
    let map = recorded.get(session)
    if (map === undefined) { map = new Map(); recorded.set(session, map) }
    return map
  }

  ctx.on('fs/observed', (target, observation, actor) => {
    const session = actor?.agent?.session
    if (session === undefined || observation?.kind !== 'present' || typeof observation.version !== 'string') return
    const key = String(target?.targetKey ?? '')
    const path = String(target?.displayPath ?? '')
    if (key === '' || path === '') return
    const map = recordedOf(session)
    if (map.get(key) === observation.version) return
    map.set(key, observation.version)
    Promise.resolve(append(session, OBSERVED_TYPE, { key, path, version: observation.version }))
      .catch(error => log('warn', `fs observation not recorded: ${error?.message ?? error}`))
  })

  /** Replays [agent]'s still-current observations; returns how many. */
  const restore = async agent => {
    const session = agent?.session
    const service = fs()
    if (session === undefined || service === undefined) return 0
    const map = recordedOf(session)
    let restored = 0
    for (const { key, path, version } of recordedObservations(session)) {
      if (map.has(key)) continue
      try {
        const target = await service.resolve(path)
        if (String(target.targetKey) !== key) continue
        const info = await service.stat(target)
        if (info?.type !== 'file' || info.version !== version) continue
        map.set(key, version)
        ctx.emit('fs/observed', target, { kind: 'present', version }, { agent })
        restored += 1
      } catch {}
    }
    return restored
  }

  ctx.on('agent/created', async ({ agent }) => {
    const restored = await restore(agent)
    if (restored > 0) log('info', `restored ${restored} file observation(s) for ${agent.session?.id}`)
  })

  return { restore }
}
