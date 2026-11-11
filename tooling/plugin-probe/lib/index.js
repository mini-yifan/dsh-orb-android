// @dsh-orb-android/plugin-probe — B0 spike fixture (private, never shipped to users).
//
// Proves the whole path end to end:
//   plugin tgz -> runtime/build-runtime.sh verifies its sha256 and unpacks it under
//   /opt/dsh-go/plugins/<package> -> rtbuild.py registers this file by absolute path in the
//   app overlay -> `dsh web` in the PRoot rootfs imports and applies it.
//
// It registers no tools, routes, presets or clients. The only effects are one stdout line
// (captured by the app's runtime log) and a frozen read-only service marker other plugins can
// inject as `dshOrbPluginProbe`.
import { readFileSync } from 'node:fs'

const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'))

export const name = '@dsh-orb-android/plugin-probe'
export const inject = []

export function apply(ctx) {
  const marker = Object.freeze({
    plugin: pkg.name,
    version: pkg.version,
    health: 'ok',
    loadedAt: new Date().toISOString(),
  })
  if (typeof ctx.provide === 'function') ctx.provide('dshOrbPluginProbe', marker)
  // Stable prefix: runtime/tests/check-plugin-install.sh and the on-device runtime log grep
  // for "[plugin-probe] loaded".
  console.log(`[plugin-probe] loaded ${pkg.name}@${pkg.version} health=${marker.health}`)
}