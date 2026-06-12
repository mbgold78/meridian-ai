#!/usr/bin/env node
/**
 * DOC-1 S6 — plan §6 enforcement #2: the SOFT path-diff hint.
 *
 * The HARD gate is docsVerify (enforcement #1): break a documented selector
 * and the gate fails mechanically, naming topic + spec. This script is the
 * advisory layer on top: when a PR touches frontend source but no capture
 * spec, it warns (GitHub `::warning::` annotations) listing the guide topics
 * whose specs reference selectors that appear in the touched files — so the
 * author updates topic + spec in the SAME PR instead of after a red gate.
 *
 *   node scripts/spec-path-hint.mjs --base=origin/main
 *
 * Behavior contract (CONTRIBUTING.md "How the rule is enforced"):
 *   - hint, never a gate: ALWAYS exits 0, even on internal errors;
 *   - silent when no substrate/frontend/src/** file changed vs the merge-base;
 *   - silent when the PR also touches substrate/docs/pipeline/specs/**
 *     (the author already did the right thing);
 *   - otherwise: generates the testid -> spec -> topic mapping from the spec
 *     files (written to build/selector-registry.json for inspection) and
 *     scans BOTH sides of each touched frontend file (merge-base content for
 *     removed/renamed selectors, HEAD content for current ones).
 */
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import yaml from 'js-yaml';

const pipelineRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const SPECS_DIR = path.join(pipelineRoot, 'specs');

function git(repoRoot, args, { allowFail = false } = {}) {
  try {
    return execFileSync('git', ['-C', repoRoot, ...args], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  } catch (e) {
    if (allowFail) return null;
    throw e;
  }
}

function main() {
  const eqForm = process.argv.find((a) => a.startsWith('--base='));
  const flagIdx = process.argv.indexOf('--base');
  const base = eqForm
    ? eqForm.slice('--base='.length)
    : (flagIdx >= 0 && process.argv[flagIdx + 1]) ? process.argv[flagIdx + 1] : 'origin/main';

  const repoRoot = git(pipelineRoot, ['rev-parse', '--show-toplevel']).trim();
  const mergeBase = git(repoRoot, ['merge-base', base, 'HEAD']).trim();
  const changed = git(repoRoot, ['diff', '--name-only', `${mergeBase}..HEAD`])
    .split('\n').map((l) => l.trim()).filter(Boolean);

  const frontendChanged = changed.filter((f) => f.startsWith('substrate/frontend/src/'));
  const specsChanged = changed.some((f) => f.startsWith('substrate/docs/pipeline/specs/'));
  if (frontendChanged.length === 0) {
    console.log('spec-path-hint: no substrate/frontend/src/** change vs merge-base — nothing to hint.');
    return;
  }
  if (specsChanged) {
    console.log('spec-path-hint: PR touches substrate/docs/pipeline/specs/** alongside frontend source — rule satisfied, no hint.');
    return;
  }

  // testid/data-attr -> [{spec, topic, status}] from the spec FILES (raw-text
  // selector extraction: selectors live in waits/preSteps/capture/annotations
  // and inside comments would over-match, so restrict to bracketed selectors).
  const registry = {};
  const add = (token, entry) => {
    (registry[token] ??= []).push(entry);
  };
  for (const f of fs.readdirSync(SPECS_DIR).filter((n) => /\.ya?ml$/.test(n) && !n.startsWith('selector-registry'))) {
    const raw = fs.readFileSync(path.join(SPECS_DIR, f), 'utf8');
    let doc;
    try {
      doc = yaml.load(raw);
    } catch {
      continue; // schema problems are docsVerify's job, not the hint's
    }
    if (!doc?.id) continue;
    const entry = { spec: doc.id, topic: doc.topic ?? '?', status: doc.status ?? 'active', file: `specs/${f}` };
    const seen = new Set();
    for (const m of raw.matchAll(/\[data-testid="([A-Za-z0-9_-]+)"\]/g)) {
      const t = `data-testid="${m[1]}"`;
      if (!seen.has(t)) { seen.add(t); add(t, entry); }
    }
    for (const m of raw.matchAll(/\[(data-(?!testid)[a-z0-9-]+)[\]=]/g)) {
      if (!seen.has(m[1])) { seen.add(m[1]); add(m[1], entry); }
    }
  }
  const jsonOut = path.join(pipelineRoot, 'build', 'selector-registry.json');
  fs.mkdirSync(path.dirname(jsonOut), { recursive: true });
  fs.writeFileSync(jsonOut, JSON.stringify(registry, null, 2));

  let hints = 0;
  for (const file of frontendChanged) {
    const headContent = fs.existsSync(path.join(repoRoot, file))
      ? fs.readFileSync(path.join(repoRoot, file), 'utf8') : '';
    // Merge-base side too: a REMOVED/renamed selector is the dangerous case.
    const baseContent = git(repoRoot, ['show', `${mergeBase}:${file}`], { allowFail: true }) ?? '';
    const haystack = headContent + '\n' + baseContent;
    for (const [token, entries] of Object.entries(registry)) {
      const needle = token.startsWith('data-testid=') ? token : `${token}=`;
      if (!haystack.includes(needle)) continue;
      for (const e of entries.filter((x) => x.status === 'active')) {
        hints++;
        console.log(
          `::warning title=docs capture spec may need an update::${file} touches '${token}' — referenced by spec=${e.spec} topic=${e.topic} (${e.file}). ` +
          `Update the task topic + capture spec in this PR; if the selector breaks, the docs-verify gate fails mechanically (DOCS-VERIFY FAIL topic=... spec=... selector=...).`,
        );
      }
    }
  }
  if (hints === 0) {
    console.log(`spec-path-hint: ${frontendChanged.length} frontend file(s) changed, none overlap a documented selector — no hint.`);
  } else {
    console.log(`spec-path-hint: ${hints} hint(s) emitted (advisory only — the gate is docsVerify).`);
  }
}

try {
  main();
} catch (e) {
  // Hint, never a gate: surface the problem, exit clean.
  console.log(`::warning title=spec-path-hint errored (advisory step)::${String(e?.message ?? e).split('\n')[0]}`);
}
process.exit(0);
