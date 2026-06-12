#!/usr/bin/env node
/**
 * DOC-1 docs capture pipeline CLI (plan §3/§5/§6).
 *
 *   node src/cli.js capture  [--jar=<path> | --base-url=<url>] [--spec=<id>]
 *   node src/cli.js verify   [--jar=<path> | --base-url=<url>] [--fresh-boots] [--spec=<id>]
 *   node src/cli.js assemble
 *
 * capture  — boots the app (or attaches to --base-url), runs every spec in
 *            specs/, writes PNGs + manifest.json to build/captures/.
 * verify   — runs the FULL capture twice and byte-compares the SHA-256
 *            manifests (D-A: within-platform identity). Default: two passes
 *            against one booted instance; --fresh-boots boots a second fresh
 *            JVM (new H2, new UUIDs) for the second pass, per plan §5.
 * assemble — resolves {{capture:spec-id}} placeholders in guide sources into
 *            build/manual/ (S1 skeleton).
 *
 * Exit codes: see src/lib/errors.js (0 ok / 2 env / 3 regression / 4
 * determinism / 5 assemble).
 */
import fs from 'node:fs';
import path from 'node:path';
import { parseArgs } from 'node:util';
import yaml from 'js-yaml';
import { chromium } from '@playwright/test';
import { CONFIG_FILE, SPECS_DIR, fromPipelineRoot } from './lib/paths.js';
import { loadSpecs } from './lib/schema.js';
import { bootApp } from './lib/boot.js';
import { runSpec } from './lib/capture.js';
import { writeManifest, diffManifests } from './lib/manifest.js';
import { assembleGuides } from './lib/assemble.js';
import { EXIT, PipelineError } from './lib/errors.js';

const log = (m) => console.log(m);

async function main() {
  const { positionals, values } = parseArgs({
    allowPositionals: true,
    options: {
      jar: { type: 'string' },
      'base-url': { type: 'string' },
      spec: { type: 'string' },
      'fresh-boots': { type: 'boolean', default: false },
    },
  });
  const command = positionals[0];
  if (!['capture', 'verify', 'assemble'].includes(command)) {
    throw new PipelineError(EXIT.ENV, `usage: cli.js capture|verify|assemble [--jar=|--base-url=|--spec=|--fresh-boots]`);
  }

  const config = yaml.load(fs.readFileSync(CONFIG_FILE, 'utf8'));
  const specs = loadSpecs(SPECS_DIR, { only: values.spec });
  log(`loaded ${specs.length} capture spec(s) from specs/ — schema OK`);

  if (command === 'assemble') {
    const res = assembleGuides({ config, specs, log });
    log(`DOCS-ASSEMBLE PASS guides=${res.guides} images=${res.images}`);
    return;
  }

  const jarPath = path.resolve(fromPipelineRoot('.'), values.jar ?? config.app.jar);
  const capturesDir = fromPipelineRoot(config.output.capturesDir);
  const warnings = [];

  const withApp = async (label, fn) => {
    if (values['base-url']) return fn(values['base-url'].replace(/\/$/, ''));
    const app = await bootApp({ jarPath, config, logDir: fromPipelineRoot('build'), label });
    log(`booted ${path.basename(jarPath)} on ${app.baseUrl} (log: ${path.relative(fromPipelineRoot('.'), app.logFile)})`);
    try {
      return await fn(app.baseUrl);
    } finally {
      await app.stop();
      log(`stopped app instance '${label}'`);
    }
  };

  const browser = await chromium.launch({ args: config.browser?.args ?? [] });
  try {
    const runPass = async (baseUrl, outDir) => {
      fs.rmSync(outDir, { recursive: true, force: true });
      for (const spec of specs) {
        await runSpec({ browser, spec, config, baseUrl, outDir, warnings, log });
      }
      return writeManifest(outDir);
    };

    if (command === 'capture') {
      const m = await withApp('capture', (baseUrl) => runPass(baseUrl, capturesDir));
      printManifest('CAPTURE', m);
      log(`DOCS-CAPTURE PASS files=${Object.keys(m.manifest.files).length} manifest=${m.aggregate}`);
    } else {
      // verify — two full runs, byte-compared (D-A within-platform identity)
      const dirA = fromPipelineRoot('build/verify/run-a');
      const dirB = fromPipelineRoot('build/verify/run-b');
      let a, b;
      if (values['fresh-boots'] && !values['base-url']) {
        a = await withApp('verify-a', (u) => runPass(u, dirA));
        b = await withApp('verify-b', (u) => runPass(u, dirB));
      } else {
        [a, b] = await withApp('verify', async (u) => [await runPass(u, dirA), await runPass(u, dirB)]);
      }
      printManifest('RUN A', a);
      printManifest('RUN B', b);
      const diffs = diffManifests(a, b);
      if (diffs.length > 0) {
        for (const d of diffs) {
          console.error(`DOCS-VERIFY FAIL determinism file=${d.file} runA=${d.runA} runB=${d.runB}`);
        }
        throw new PipelineError(EXIT.DETERMINISM, `DOCS-VERIFY FAIL ${diffs.length} file(s) differ between runs (mode=${values['fresh-boots'] ? 'fresh-boots' : 'same-boot'})`);
      }
      // Canonical captures = the verified set; assemble consumes these.
      fs.rmSync(capturesDir, { recursive: true, force: true });
      fs.mkdirSync(capturesDir, { recursive: true });
      for (const f of fs.readdirSync(dirB)) fs.copyFileSync(path.join(dirB, f), path.join(capturesDir, f));
      log(`DOCS-VERIFY PASS runs=2 mode=${values['fresh-boots'] ? 'fresh-boots' : 'same-boot'} files=${Object.keys(a.manifest.files).length} manifest=${a.aggregate}`);
    }
  } finally {
    await browser.close();
  }

  if (warnings.length > 0) {
    log(`--- ${warnings.length} warning(s) ---`);
    for (const w of [...new Set(warnings)]) log(`  WARN ${w}`);
  }
}

function printManifest(label, m) {
  log(`--- ${label} manifest (aggregate sha256=${m.aggregate}) ---`);
  for (const [f, h] of Object.entries(m.manifest.files)) log(`  ${h}  ${f}`);
}

main().catch((e) => {
  if (e instanceof PipelineError) {
    console.error(e.message);
    process.exit(e.exitCode);
  }
  console.error(e);
  process.exit(EXIT.ENV);
});
