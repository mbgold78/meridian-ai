import fs from 'node:fs';
import path from 'node:path';
import { fromPipelineRoot } from './paths.js';

/**
 * Font determinism (plan §5 gap 1): index.html loads Google Fonts from the
 * CDN with display=swap — a network wobble silently captures fallback glyphs.
 * No production change is allowed (self-hosting belongs to the C7 theming
 * chunk), so the RUNNER intercepts both font domains:
 *
 *   fonts.googleapis.com/css2*  -> vendored stylesheet (assets/fonts/fonts.css,
 *                                  fetched once with a Chromium UA so it is the
 *                                  woff2 / unicode-range form Chromium expects)
 *   fonts.gstatic.com/<path>    -> vendored woff2 bytes via font-manifest.json
 *
 * mode=vendored: an un-vendored gstatic path ABORTS the request and records a
 * warning (deterministic miss — re-run scripts/vendor-fonts.js to refresh).
 * mode=block: abort everything (deterministic system-font fallback).
 */
export async function installFontRoutes(context, fontsCfg, warnings) {
  const dir = fromPipelineRoot(fontsCfg.dir ?? 'assets/fonts');
  const mode = fontsCfg.mode ?? 'vendored';

  let css = null;
  let manifest = {};
  if (mode === 'vendored') {
    const cssFile = path.join(dir, 'fonts.css');
    const manifestFile = path.join(dir, 'font-manifest.json');
    if (fs.existsSync(cssFile) && fs.existsSync(manifestFile)) {
      css = fs.readFileSync(cssFile, 'utf8');
      manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8')).files;
    } else {
      warnings.push(`fonts: vendored assets missing under ${dir} — falling back to mode=block (run \`npm run vendor-fonts\`)`);
    }
  }

  await context.route(/^https:\/\/fonts\.googleapis\.com\//, async (route) => {
    const url = new URL(route.request().url());
    if (css !== null && url.pathname.startsWith('/css2')) {
      await route.fulfill({ status: 200, contentType: 'text/css; charset=utf-8', body: css });
    } else {
      if (css !== null) warnings.push(`fonts: unvendored googleapis request aborted: ${url.pathname}`);
      await route.abort('failed');
    }
  });

  await context.route(/^https:\/\/fonts\.gstatic\.com\//, async (route) => {
    const url = new URL(route.request().url());
    const rel = manifest[url.pathname];
    if (rel) {
      await route.fulfill({
        status: 200,
        contentType: 'font/woff2',
        body: fs.readFileSync(path.join(dir, rel)),
      });
    } else {
      if (css !== null) warnings.push(`fonts: unvendored gstatic font aborted: ${url.pathname}`);
      await route.abort('failed');
    }
  });
}
