// Row facts for the plugin list (#80/#88): which rows have a browser face the phone cannot show,
// and which rows' config can be edited as a form without a schema.
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

/** The package a Loader row's module names (`@scope/pkg/sub` → `@scope/pkg`), or undefined for a path or a `scheme:` name. */
export function packageOfModule(spec) {
  if (typeof spec !== 'string' || spec === '' || spec.startsWith('.') || spec.startsWith('/') || /^[a-z]+:/i.test(spec)) return undefined
  const parts = spec.split('/')
  if (spec.startsWith('@')) return parts.length >= 2 && parts[1] ? `${parts[0]}/${parts[1]}` : undefined
  return parts[0]
}

/** `dsh.client.platform` of a package manifest ('web'), or undefined. */
export function clientPlatform(manifest) {
  const v = manifest?.dsh?.client?.platform
  return typeof v === 'string' ? v : undefined
}

/**
 * `dsh.client.platform` of the package a row's [moduleName] names (#80/#88), for when the engine's `pluginPackages`
 * lookup is not up: such a package has a browser face — pages, panels, a settings card — that only the web client
 * renders; the phone has no web client, so that part never shows. Looked up in the profile's node_modules, then the way node resolves from [fromDir] (pnpm keeps a
 * bundle's dependencies beside it, not hoisted). Undefined when the package has none or is not found.
 */
export function clientPlatformOf(moduleName, profileDir, fromDir) {
  const pkg = packageOfModule(moduleName)
  if (pkg === undefined) return undefined
  const read = f => { try { return clientPlatform(JSON.parse(readFileSync(f, 'utf8'))) ?? null } catch { return undefined } }
  const top = read(join(profileDir, 'node_modules', pkg, 'package.json'))
  if (top !== undefined) return top ?? undefined
  let dir = fromDir
  for (let i = 0; dir && i < 40; i++) {
    const v = read(join(dir, 'node_modules', pkg, 'package.json'))
    if (v !== undefined) return v ?? undefined
    const up = dirname(dir)
    if (up === dir) break
    dir = up
  }
  return undefined
}

/** A row config the app can edit as a form without a schema: a plain object with at least one key. */
export function hasPlainConfig(config) {
  return config !== null && typeof config === 'object' && !Array.isArray(config) && Object.keys(config).length > 0
}
