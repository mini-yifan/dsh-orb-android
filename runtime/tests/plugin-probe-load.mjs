// Loads an unpacked @dsh-orb-android/plugin-probe tree with a mock cordis context and checks
// the contract runtime/tests/check-plugin-install.sh relies on: ESM entry, name, inject, apply,
// one stdout line and a frozen read-only service marker.
//
//   node runtime/tests/plugin-probe-load.mjs /path/to/unpacked/plugin
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'

const dir = resolve(process.argv[2] ?? '')
const pkg = JSON.parse(readFileSync(join(dir, 'package.json'), 'utf8'))
const entry = join(dir, pkg.main ?? 'lib/index.js')
const mod = await import(pathToFileURL(entry).href)

assert.equal(mod.name, pkg.name, 'plugin module name must equal package.json name')
assert.ok(Array.isArray(mod.inject), 'plugin must export inject as an array')
assert.equal(typeof mod.apply, 'function', 'plugin must export apply')

const provided = new Map()
const logs = []
const original = console.log
console.log = (...args) => logs.push(args.join(' '))
try {
  // The real cordis ctx has provide(); the probe only needs that method.
  mod.apply({ provide: (name, value) => provided.set(name, value) })
} finally {
  console.log = original
}

const marker = provided.get('dshOrbPluginProbe')
assert.ok(marker, 'plugin must provide dshOrbPluginProbe')
assert.ok(Object.isFrozen(marker), 'dshOrbPluginProbe must be frozen')
assert.equal(marker.plugin, pkg.name)
assert.equal(marker.version, pkg.version)
assert.equal(marker.health, 'ok')
assert.ok(
  logs.some(line => line.includes(`[plugin-probe] loaded ${pkg.name}@${pkg.version} health=ok`)),
  'plugin must print the stable load line',
)
console.log(`plugin-probe load: ok (${pkg.name}@${pkg.version})`)