// S5 evidence (verify-transcript.txt deviation 1): the debug harness that
// bisected the coo-finding-detail "never settled" failure. Reproduces the
// ORIGINAL expanded-row pose (click + canvas spotlight) and reports WHERE
// pixels keep changing (bbox of diff between consecutive shots) — output
// showed continuous motion in the canvas region (the SMIL flow-dot layer
// re-created by the 8s spotlight-clear rebuild). Run FROM docs/pipeline/
// (relative imports) against a seeded boot on :8123.
import fs from 'node:fs';
import yaml from 'js-yaml';
import { chromium } from '@playwright/test';
import { PNG } from 'pngjs';
import { installFontRoutes } from './src/lib/fonts.js';

const baseUrl = 'http://127.0.0.1:8123';
const config = yaml.load(fs.readFileSync('pipeline.config.yaml', 'utf8'));

const browser = await chromium.launch({ args: config.browser.args });
const ctx = await browser.newContext({
  viewport: config.context.viewport,
  deviceScaleFactor: 1,
  locale: 'en-US',
  timezoneId: 'UTC',
  colorScheme: 'dark',
  reducedMotion: 'reduce',
});
await installFontRoutes(ctx, config.fonts, []);
const res = await fetch(baseUrl + '/api/v1/domains');
const domains = (await res.json()).data;
const domainId = domains.find((d) => d.name === 'Operational Risk').id;
await ctx.addInitScript((entries) => {
  for (const [k, v] of entries) localStorage.setItem(k, v);
}, [['meridian-persona-level', 'L1_EXECUTIVE'], ['substrate-canvas-welcomed-v1', '1'], ['substrate-workspace-domain-id', domainId]]);

const page = await ctx.newPage();
await page.goto(baseUrl + '/?view=risks', { waitUntil: 'domcontentloaded' });
await page.addStyleTag({ content: '*, *::before, *::after { animation: none !important; transition: none !important; caret-color: transparent !important; }' });
await page.waitForFunction(() => document.querySelector('[data-testid="living-canvas"]')?.getAttribute('data-canvas-ready') === 'true', null, { timeout: 30000 });
await page.waitForSelector('[data-testid="canvas-finding-row"]', { state: 'visible' });
await page.click('[data-testid="canvas-finding-row"] button');
await page.waitForSelector('[data-testid="canvas-finding-detail"]', { state: 'visible' });
await page.evaluate(() => document.fonts.ready.then(() => undefined));
await page.evaluate(() => {
  for (const svg of document.querySelectorAll('svg')) {
    if (typeof svg.pauseAnimations === 'function') { svg.pauseAnimations(); svg.setCurrentTime(0); }
  }
});

let prev = PNG.sync.read(await page.screenshot({ fullPage: true }));
for (let i = 0; i < 25; i++) {
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r(undefined)))));
  const cur = PNG.sync.read(await page.screenshot({ fullPage: true }));
  let minX = 1e9, minY = 1e9, maxX = -1, maxY = -1, count = 0;
  for (let y = 0; y < cur.height; y++) {
    for (let x = 0; x < cur.width; x++) {
      const idx = (cur.width * y + x) * 4;
      if (cur.data[idx] !== prev.data[idx] || cur.data[idx + 1] !== prev.data[idx + 1] || cur.data[idx + 2] !== prev.data[idx + 2]) {
        count++;
        if (x < minX) minX = x; if (x > maxX) maxX = x;
        if (y < minY) minY = y; if (y > maxY) maxY = y;
      }
    }
  }
  console.log(`shot ${i}: changed=${count}${count ? ` bbox=(${minX},${minY})-(${maxX},${maxY})` : ''}`);
  prev = cur;
}
await browser.close();
