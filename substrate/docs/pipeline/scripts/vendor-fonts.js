/**
 * One-shot vendoring of the Google Fonts assets index.html references
 * (plan §5 gap 1). Fetches the css2 stylesheet with a Chromium UA (so we get
 * the woff2/unicode-range form the captured browser would request), downloads
 * every referenced fonts.gstatic.com file, and writes:
 *
 *   assets/fonts/fonts.css           — stylesheet served for /css2* requests
 *   assets/fonts/font-manifest.json  — gstatic pathname -> local file map
 *   assets/fonts/*.woff2             — the font binaries (committed)
 *
 * Re-run only when frontend/index.html changes its font families.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fromPipelineRoot } from '../src/lib/paths.js';

const CSS2_URL =
  'https://fonts.googleapis.com/css2?family=Inter:wght@100..900&family=Instrument+Serif:ital@0;1&family=JetBrains+Mono:wght@400;500;600;700&display=swap';
const CHROME_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

const outDir = fromPipelineRoot('assets/fonts');
fs.mkdirSync(outDir, { recursive: true });

const cssRes = await fetch(CSS2_URL, { headers: { 'user-agent': CHROME_UA } });
if (!cssRes.ok) throw new Error(`css2 fetch failed: HTTP ${cssRes.status}`);
const css = await cssRes.text();

const urls = [...new Set([...css.matchAll(/url\((https:\/\/fonts\.gstatic\.com[^)]+)\)/g)].map((m) => m[1]))];
console.log(`css2 OK — ${urls.length} unique font files referenced`);

const files = {};
for (const u of urls) {
  const { pathname } = new URL(u);
  const local = pathname.replace(/^\/+/, '').replaceAll('/', '_');
  const res = await fetch(u, { headers: { 'user-agent': CHROME_UA } });
  if (!res.ok) throw new Error(`font fetch failed (${res.status}): ${u}`);
  fs.writeFileSync(path.join(outDir, local), Buffer.from(await res.arrayBuffer()));
  files[pathname] = local;
  console.log(`  vendored ${pathname} -> ${local}`);
}

fs.writeFileSync(path.join(outDir, 'fonts.css'), css);
fs.writeFileSync(
  path.join(outDir, 'font-manifest.json'),
  JSON.stringify({ source: CSS2_URL, files }, null, 2) + '\n',
);
console.log(`wrote fonts.css + font-manifest.json (${Object.keys(files).length} files) to ${outDir}`);
