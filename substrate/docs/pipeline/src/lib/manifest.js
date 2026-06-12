import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

export function sha256(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex');
}

/**
 * Build a deterministic manifest over a directory's PNGs: sorted filenames,
 * LF-only canonical JSON, no timestamps/ports (anything env-dependent would
 * defeat the byte-compare gate).
 */
export function buildManifest(dir) {
  const files = {};
  for (const f of fs.readdirSync(dir).filter((f) => f.endsWith('.png')).sort()) {
    files[f] = sha256(fs.readFileSync(path.join(dir, f)));
  }
  const manifest = { schema: 'doc1-capture-manifest/v1', files };
  const canonical = JSON.stringify(manifest, null, 2) + '\n';
  return { manifest, canonical, aggregate: sha256(canonical) };
}

export function writeManifest(dir) {
  const m = buildManifest(dir);
  fs.writeFileSync(path.join(dir, 'manifest.json'), m.canonical);
  return m;
}

/** Compare two manifests; returns [] when byte-identical per D-A. */
export function diffManifests(a, b) {
  const diffs = [];
  const names = new Set([...Object.keys(a.manifest.files), ...Object.keys(b.manifest.files)]);
  for (const n of [...names].sort()) {
    const ha = a.manifest.files[n];
    const hb = b.manifest.files[n];
    if (ha !== hb) diffs.push({ file: n, runA: ha ?? '(missing)', runB: hb ?? '(missing)' });
  }
  return diffs;
}
