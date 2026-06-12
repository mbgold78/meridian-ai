import fs from 'node:fs';
import path from 'node:path';
import { installFontRoutes } from './fonts.js';
import { annotatePng } from './annotate.js';
import { pollApi } from './apiPoll.js';
import { evalJsonPath } from './jsonpath.js';
import { EXIT, PipelineError, selectorFailure } from './errors.js';

/** Kill CSS-driven motion for `determinism: animated` scenes (plan §5 gap 2). */
const ANIMATION_KILL_CSS =
  '*, *::before, *::after { animation: none !important; transition: none !important; caret-color: transparent !important; }';

/**
 * Execute one capture spec against a running app (plan §3 semantics):
 * resolve -> context (persona via addInitScript, P-5) -> route -> waits
 * (api/selector) -> preSteps -> fonts.ready -> quiescence + settle-poll ->
 * capture -> annotation compositing. Returns the list of files written.
 */
export async function runSpec({ browser, spec, config, baseUrl, outDir, warnings, log }) {
  // --- resolve: per-boot UUIDs via API (P-3 — never hardcoded) -------------
  // Resolved vars substitute into: the route, LATER resolve `get` paths
  // (chaining — e.g. domain id -> cascade list), and localStorage values
  // (e.g. the P-5 workspace key, whose value is a per-boot domain UUID that
  // must never be hardcoded in a spec).
  const vars = {};
  const substitute = (s) => s.replace(/\{([a-zA-Z_]+)\}/g, (m, name) => (name in vars ? String(vars[name]) : m));
  let route = spec.route;
  if (spec.resolve) {
    for (const r of spec.resolve) {
      const getPath = substitute(r.get);
      const res = await fetch(baseUrl + getPath, { signal: AbortSignal.timeout(15000) });
      if (!res.ok) {
        throw new PipelineError(EXIT.ENV, `spec=${spec.id} resolve var=${r.var}: GET ${getPath} -> HTTP ${res.status}`);
      }
      const value = evalJsonPath(r.pick, await res.json());
      if (value === undefined || value === null) {
        throw new PipelineError(EXIT.ENV, `spec=${spec.id} resolve var=${r.var}: pick ${r.pick} yielded nothing`);
      }
      vars[r.var] = value;
    }
    route = substitute(route);
  }
  if (/\{[a-zA-Z_]+\}/.test(route)) {
    throw new PipelineError(EXIT.ENV, `spec=${spec.id}: route still has unresolved placeholders: ${route}`);
  }

  // --- browser context ------------------------------------------------------
  const ctxCfg = config.context;
  const context = await browser.newContext({
    viewport: spec.viewport ?? ctxCfg.viewport,
    deviceScaleFactor: ctxCfg.deviceScaleFactor ?? 1,
    locale: ctxCfg.locale,
    timezoneId: ctxCfg.timezoneId,
    colorScheme: ctxCfg.colorScheme ?? 'dark',
    reducedMotion: ctxCfg.reducedMotion ?? 'reduce',
  });
  try {
    await installFontRoutes(context, config.fonts ?? {}, warnings);

    // Persona + extra storage pre-seeds BEFORE any app script runs (P-5):
    // sets the rendered chip and the X-Persona-Level wire header atomically.
    const storageEntries = [
      [config.storage.personaKey, spec.persona],
      ...Object.entries(spec.localStorage ?? {}).map(([k, v]) => [k, substitute(v)]),
    ];
    await context.addInitScript((entries) => {
      for (const [k, v] of entries) localStorage.setItem(k, v);
    }, storageEntries);

    // Track in-flight app API calls so a late fetch can never land between
    // the settle frames (predicate-based quiescence, not a sleep) — and track
    // FAILED app API calls: the frontend's `retry: false` data hooks settle on
    // fixture/fallback UI after a single transient failure (plan §5 gap 3,
    // agentData.ts idiom), which would silently capture degraded-but-stable
    // pixels. A capture run with any failed app API request is INVALID.
    let inflight = 0;
    const apiFailures = [];
    context.on('request', (req) => {
      if (req.url().startsWith(baseUrl + '/api/')) inflight++;
    });
    context.on('requestfinished', async (req) => {
      if (!req.url().startsWith(baseUrl + '/api/')) return;
      inflight = Math.max(0, inflight - 1);
      const status = (await req.response())?.status() ?? 0;
      if (status >= 400) apiFailures.push(`HTTP ${status} ${req.url()}`);
    });
    context.on('requestfailed', (req) => {
      if (!req.url().startsWith(baseUrl + '/api/')) return;
      inflight = Math.max(0, inflight - 1);
      apiFailures.push(`${req.failure()?.errorText ?? 'failed'} ${req.url()}`);
    });

    const page = await context.newPage();
    const selectorTimeout = config.selectorTimeoutMs ?? 30000;

    await page.goto(baseUrl + route, { waitUntil: 'domcontentloaded', timeout: 60000 });

    if (spec.determinism === 'animated') {
      await page.addStyleTag({ content: ANIMATION_KILL_CSS });
    }

    // --- waits (ordered) ----------------------------------------------------
    for (const w of spec.waits) {
      if (w.api) {
        await pollApi(baseUrl, { ...w.api, get: substitute(w.api.get) }, { label: `spec=${spec.id}`, log });
      } else {
        try {
          await page.waitForSelector(w.selector, { state: 'visible', timeout: w.timeoutMs ?? selectorTimeout });
          if (w.attr) {
            await page.waitForFunction(
              ({ sel, attr, equals }) => document.querySelector(sel)?.getAttribute(attr) === equals,
              { sel: w.selector, attr: w.attr, equals: String(w.equals) },
              { timeout: w.timeoutMs ?? selectorTimeout },
            );
          }
        } catch {
          throw selectorFailure(spec, w.selector + (w.attr ? `[${w.attr}="${w.equals}"]` : ''), 'wait failed');
        }
      }
    }

    // --- preSteps -----------------------------------------------------------
    for (const s of spec.preSteps ?? []) {
      const [kind] = Object.keys(s).filter((k) => ['click', 'fill', 'hover', 'waitSelector'].includes(k));
      const sel = kind === 'fill' ? s.fill.selector : s[kind];
      try {
        if (kind === 'click') await page.click(sel, { timeout: selectorTimeout });
        else if (kind === 'fill') await page.fill(sel, s.fill.value, { timeout: selectorTimeout });
        else if (kind === 'hover') await page.hover(sel, { timeout: selectorTimeout });
        else await page.waitForSelector(sel, { state: 'visible', timeout: selectorTimeout });
      } catch {
        throw selectorFailure(spec, sel, `preStep ${kind} failed`);
      }
    }

    // --- pre-capture determinism gates --------------------------------------
    await page.evaluate(() => document.fonts.ready.then(() => undefined));

    // SMIL freeze (S2 finding, `determinism: animated`): SVG <animateMotion
    // repeatCount="indefinite"> (the canvas FlowEdge flow dots) is neither a
    // CSS animation (kill stylesheet) nor a Web Animation (reducedMotion) —
    // it rides the SVG document clock. pauseAnimations() stops that clock and
    // setCurrentTime(0) pins every SMIL element to its t=0 pose, identical on
    // every run and boot. Done after waits/preSteps so the SVGs exist.
    if (spec.determinism === 'animated') {
      await page.evaluate(() => {
        for (const svg of document.querySelectorAll('svg')) {
          if (typeof svg.pauseAnimations === 'function') {
            svg.pauseAnimations();
            svg.setCurrentTime(0);
          }
        }
      });
    }

    const quiesceDeadline = Date.now() + (config.settle?.quiescenceTimeoutMs ?? 15000);
    while (inflight > 0) {
      if (Date.now() > quiesceDeadline) {
        throw new PipelineError(EXIT.ENV, `spec=${spec.id}: ${inflight} API request(s) still in flight at quiescence timeout`);
      }
      await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(undefined))));
    }

    // --- settle-poll capture (plan §5): consecutive identical shots at rAF
    // spacing (3 by default — stricter than the plan's minimum of 2), each
    // accepted only while the API tracker shows zero in-flight requests.
    const takeShot = async () => {
      if (spec.capture.mode === 'element') {
        const loc = page.locator(spec.capture.selector).first();
        try {
          return await loc.screenshot({ timeout: selectorTimeout });
        } catch {
          throw selectorFailure(spec, spec.capture.selector, 'capture element not found');
        }
      }
      return page.screenshot({ fullPage: true, timeout: selectorTimeout });
    };

    const needIdentical = config.settle?.consecutiveIdentical ?? 3;
    const maxAttempts = config.settle?.maxAttempts ?? 40;
    let shot = await takeShot();
    let identical = 1;
    let stable = false;
    for (let i = 0; i < maxAttempts; i++) {
      await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r(undefined)))));
      if (inflight > 0) {
        identical = 0; // a request fired mid-settle — wait it out and restart
        while (inflight > 0 && Date.now() < quiesceDeadline) {
          await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(undefined))));
        }
      }
      const next = await takeShot();
      identical = shot.equals(next) ? identical + 1 : 1;
      shot = next;
      if (identical >= needIdentical) {
        stable = true;
        break;
      }
    }
    if (!stable) {
      throw new PipelineError(
        EXIT.DETERMINISM,
        `spec=${spec.id}: page never settled (${maxAttempts} attempts) — unkilled animation or polling UI; consider determinism: animated`,
      );
    }

    // Expected, deterministic non-2xx responses (schema `allowedApiFailures`)
    // are downgraded to visible warnings; everything else stays a hard fail
    // (the retry:false data hooks would freeze fallback UI into the PNG).
    const allowed = spec.allowedApiFailures ?? [];
    const unexpected = [];
    for (const f of [...new Set(apiFailures)]) {
      const rule = allowed.find((a) => f.includes(a.match));
      if (rule) warnings.push(`spec=${spec.id} expected API failure (${rule.reason}): ${f}`);
      else unexpected.push(f);
    }
    if (unexpected.length > 0) {
      throw new PipelineError(
        EXIT.ENV,
        `spec=${spec.id}: ${unexpected.length} app API request(s) failed during capture — the retry:false data hooks would freeze fallback UI into the PNG. ` +
          unexpected.join('; '),
      );
    }

    // --- annotations: anchor positions resolved AT CAPTURE TIME --------------
    const written = [];
    fs.mkdirSync(outDir, { recursive: true });
    const basePath = path.join(outDir, spec.output);
    fs.writeFileSync(basePath, shot);
    written.push(spec.output);

    if (spec.annotations?.length) {
      // Capture-root offset: fullPage coords are document coords; element
      // crops are relative to the captured element's box.
      const rootRect = spec.capture.mode === 'element'
        ? await page.evaluate((sel) => {
            const el = document.querySelector(sel);
            if (!el) return null;
            const r = el.getBoundingClientRect();
            return { x: r.x + window.scrollX, y: r.y + window.scrollY };
          }, spec.capture.selector)
        : { x: 0, y: 0 };
      if (!rootRect) throw selectorFailure(spec, spec.capture.selector, 'capture root vanished before annotation');

      const callouts = [];
      for (const a of spec.annotations) {
        const pos = await page.evaluate((sel) => {
          const el = document.querySelector(sel);
          if (!el) return null;
          const r = el.getBoundingClientRect();
          return { x: r.x + window.scrollX, y: r.y + window.scrollY };
        }, a.anchor);
        if (!pos) throw selectorFailure(spec, a.anchor, `annotation n=${a.n} anchor not found`);
        log(`spec=${spec.id} annotation n=${a.n} anchor rect x=${pos.x} y=${pos.y}`);
        callouts.push({ n: a.n, cx: pos.x - rootRect.x + a.offset.x, cy: pos.y - rootRect.y + a.offset.y });
      }
      const annotatedName = spec.output.replace(/\.png$/, '.annotated.png');
      fs.writeFileSync(path.join(outDir, annotatedName), annotatePng(shot, callouts));
      written.push(annotatedName);
    }

    log(`spec=${spec.id} captured ${written.join(', ')}`);
    return written;
  } finally {
    await context.close();
  }
}
