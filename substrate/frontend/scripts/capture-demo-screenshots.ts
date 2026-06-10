/**
 * Beacon dashboard screenshot bundler (ported from homepoc, chunk C9).
 *
 * Boots a headless Chromium against a running app at BASE_URL, walks
 * every portfolio + baseline route across two viewport sizes, and writes
 * full-page screenshots to a timestamped directory under
 * `demo-screenshots/` at the substrate root.
 *
 * Assumptions:
 *   - The app is already running at BASE_URL (default http://localhost:8080).
 *     The script does not boot Spring Boot or Vite — that responsibility
 *     stays with the developer so the bundle pipeline is explicit. See
 *     `frontend/scripts/README.md` for the rebuild dance that has to
 *     happen before screenshots reflect a frontend change.
 *   - The app router mounts PORTFOLIO_ROUTES (chunk C8 wires App.tsx);
 *     until then the portfolio routes return the SPA fallback.
 *   - Playwright + the Chromium browser are installed
 *     (npm install -D @playwright/test && npx playwright install chromium).
 */

import { chromium, type Browser, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { BEACON_SUBSIDIARIES } from '../src/fixtures/beacon/index.ts';

const BASE_URL = process.env.SCREENSHOT_BASE_URL ?? 'http://localhost:8080';

interface Viewport {
  label: string;
  width: number;
  height: number;
}

const VIEWPORTS: Viewport[] = [
  { label: 'desktop', width: 1440, height: 900 },
  { label: 'mobile', width: 390, height: 844 },
];

interface RouteSpec {
  /** URL path joined onto BASE_URL. */
  path: string;
  /** Filename-safe label for the captured PNG. */
  fileLabel: string;
}

const FIRST_SUBSIDIARY_SLUG = BEACON_SUBSIDIARIES[0].slug;
const SUBSIDIARY_TABS = ['capabilities', 'environment', 'plan', 'operations'] as const;

const ROUTES: RouteSpec[] = [
  { path: '/portfolio', fileLabel: 'portfolio-landing' },
  { path: '/portfolio/dashboard', fileLabel: 'portfolio-operating-partner' },
  ...SUBSIDIARY_TABS.map(
    (tab): RouteSpec => ({
      path: `/portfolio/subsidiary/${FIRST_SUBSIDIARY_SLUG}?tab=${tab}`,
      fileLabel: `portfolio-subsidiary-${FIRST_SUBSIDIARY_SLUG}-${tab}`,
    }),
  ),
  { path: '/portfolio/cascade/c-vendor-cost', fileLabel: 'portfolio-cascade-c-vendor-cost' },
  {
    path: '/portfolio/contract/co-tribal-knowledge-depopulation',
    fileLabel: 'portfolio-contract-co-tribal-knowledge-depopulation',
  },
  { path: '/', fileLabel: 'baseline-home' },
  { path: '/designer/capabilities', fileLabel: 'baseline-capability-designer' },
];

/**
 * Filesystem-safe ISO timestamp. Windows rejects ':' in filenames, so
 * normalise to '-' across the board to avoid platform-specific bugs.
 */
function isoStampForFilesystem(): string {
  return new Date().toISOString().replace(/:/g, '-').replace(/\..+/, 'Z');
}

async function captureRoute(page: Page, route: RouteSpec, outDir: string): Promise<void> {
  const url = `${BASE_URL}${route.path}`;
  console.log(`  → ${url}`);
  await page.goto(url, { waitUntil: 'networkidle', timeout: 30_000 });

  // Belt-and-braces: wait a beat for any post-load animation/skeleton to settle.
  await page.waitForTimeout(750);

  const file = `${outDir}/${route.fileLabel}.png`;
  await page.screenshot({ path: file, fullPage: true });
  console.log(`     ${file}`);
}

async function captureViewport(
  browser: Browser,
  viewport: Viewport,
  bundleRoot: string,
): Promise<number> {
  const outDir = resolve(bundleRoot, viewport.label);
  await mkdir(outDir, { recursive: true });

  const ctx = await browser.newContext({
    viewport: { width: viewport.width, height: viewport.height },
    deviceScaleFactor: 1,
  });
  const page = await ctx.newPage();
  console.log(`\n[${viewport.label} ${viewport.width}x${viewport.height}]`);

  let captured = 0;
  for (const route of ROUTES) {
    await captureRoute(page, route, outDir);
    captured += 1;
  }

  await ctx.close();
  return captured;
}

async function main(): Promise<void> {
  const scriptDir = dirname(fileURLToPath(import.meta.url));
  const substrateRoot = resolve(scriptDir, '..', '..');
  const bundleRoot = resolve(substrateRoot, 'demo-screenshots', isoStampForFilesystem());

  console.log(`Beacon dashboard screenshot bundler`);
  console.log(`  base URL : ${BASE_URL}`);
  console.log(`  bundle   : ${bundleRoot}`);
  console.log(`  routes   : ${ROUTES.length}, viewports: ${VIEWPORTS.length}`);

  const browser = await chromium.launch();
  let total = 0;
  try {
    for (const viewport of VIEWPORTS) {
      total += await captureViewport(browser, viewport, bundleRoot);
    }
  } finally {
    await browser.close();
  }

  console.log(`\nDone. ${total} screenshot${total === 1 ? '' : 's'} written to:`);
  console.log(`  ${bundleRoot}`);
}

main().catch((err) => {
  console.error('\nScreenshot capture failed:');
  console.error(err);
  process.exit(1);
});
