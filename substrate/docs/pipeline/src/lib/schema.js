import fs from 'node:fs';
import path from 'node:path';
import yaml from 'js-yaml';
import { schemaFailure } from './errors.js';

/**
 * Capture-spec schema v1 (plan §3). Hand-rolled validation: precise error
 * messages naming spec id + guide-topic backlink (the regression-gate
 * behavior) beat a generic validator's output.
 */

const TOP_KEYS = new Set([
  'id', 'title', 'topic', 'route', 'resolve', 'persona', 'localStorage',
  'viewport', 'waits', 'preSteps', 'capture', 'annotations', 'output',
  'determinism', 'liveModeExempt',
]);
const PERSONAS = new Set(['L1_EXECUTIVE', 'L3_ANALYST']);
const DETERMINISM = new Set(['static', 'seeded', 'animated']);
const PRESTEP_KINDS = new Set(['click', 'fill', 'hover', 'waitSelector']);

/** Selector patterns accepted as a live-mode assertion (plan §3: mandatory). */
const LIVE_MARKER_RE = /data-[a-z-]*(source|provenance)[a-z-]*\s*=\s*["']live["']/;

function fail(errors, msg) {
  errors.push(msg);
}

function isPlainObject(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function isNonEmptyString(v) {
  return typeof v === 'string' && v.length > 0;
}

/**
 * Validate one parsed YAML document. Returns the list of problems (empty when
 * valid); normalizes defaults (output, offsets) onto the spec object.
 */
export function validateSpec(spec, fileName) {
  const errors = [];
  if (!isPlainObject(spec)) {
    return [`${fileName}: spec must be a YAML mapping`];
  }
  for (const k of Object.keys(spec)) {
    if (!TOP_KEYS.has(k)) fail(errors, `unknown top-level key '${k}'`);
  }
  if (!isNonEmptyString(spec.id) || !/^[a-z0-9]+(-[a-z0-9]+)*$/.test(spec.id)) {
    fail(errors, `'id' must be a kebab-case string (got ${JSON.stringify(spec.id)})`);
  }
  if (!isNonEmptyString(spec.title)) fail(errors, `'title' is required`);
  if (!isNonEmptyString(spec.topic)) fail(errors, `'topic' (guide-topic backlink) is required`);
  if (!isNonEmptyString(spec.route) || !spec.route.startsWith('/')) {
    fail(errors, `'route' must be a string starting with '/'`);
  }
  if (!PERSONAS.has(spec.persona)) {
    fail(errors, `'persona' must be one of ${[...PERSONAS].join('|')}`);
  }
  if (!DETERMINISM.has(spec.determinism)) {
    fail(errors, `'determinism' must be one of ${[...DETERMINISM].join('|')}`);
  }

  if (spec.resolve !== undefined) {
    if (!Array.isArray(spec.resolve)) fail(errors, `'resolve' must be a list`);
    else spec.resolve.forEach((r, i) => {
      if (!isPlainObject(r) || !isNonEmptyString(r.var) || !isNonEmptyString(r.get) || !isNonEmptyString(r.pick)) {
        fail(errors, `resolve[${i}] needs {var, get, pick}`);
      }
    });
  }

  if (spec.localStorage !== undefined) {
    if (!isPlainObject(spec.localStorage)) fail(errors, `'localStorage' must be a mapping`);
    else for (const [k, v] of Object.entries(spec.localStorage)) {
      if (typeof v !== 'string') fail(errors, `localStorage['${k}'] must be a string (quote YAML scalars)`);
    }
  }

  if (spec.viewport !== undefined) {
    const v = spec.viewport;
    if (!isPlainObject(v) || !Number.isInteger(v.width) || !Number.isInteger(v.height)) {
      fail(errors, `'viewport' must be {width: int, height: int}`);
    }
  }

  // waits — required, ordered, two kinds (api | selector)
  if (!Array.isArray(spec.waits) || spec.waits.length === 0) {
    fail(errors, `'waits' must be a non-empty list`);
  } else {
    spec.waits.forEach((w, i) => {
      if (!isPlainObject(w)) return fail(errors, `waits[${i}] must be a mapping`);
      if (w.api !== undefined) {
        const a = w.api;
        if (!isPlainObject(a) || !isNonEmptyString(a.get) || !isPlainObject(a.until) || !isNonEmptyString(a.until.path)) {
          return fail(errors, `waits[${i}].api needs {get, until: {path, equals|min}, timeoutMs?}`);
        }
        if (!('equals' in a.until) && !('min' in a.until)) {
          fail(errors, `waits[${i}].api.until needs 'equals' or 'min'`);
        }
      } else if (w.selector !== undefined) {
        if (!isNonEmptyString(w.selector)) fail(errors, `waits[${i}].selector must be a string`);
        if ((w.attr === undefined) !== (w.equals === undefined)) {
          fail(errors, `waits[${i}]: 'attr' and 'equals' come together`);
        }
      } else {
        fail(errors, `waits[${i}] must be an 'api:' or 'selector:' wait`);
      }
    });
  }

  // Live-mode assertion (plan §3): every spec needs one, or an explicit,
  // reasoned exemption (surfaces with no data-source marker yet).
  const hasLive = Array.isArray(spec.waits) && spec.waits.some(
    (w) => isNonEmptyString(w?.selector) && LIVE_MARKER_RE.test(w.selector),
  );
  if (!hasLive && !isNonEmptyString(spec.liveModeExempt)) {
    fail(errors, `no live-mode assertion in 'waits' (selector matching ${LIVE_MARKER_RE}) and no 'liveModeExempt: <reason>'`);
  }

  if (spec.preSteps !== undefined) {
    if (!Array.isArray(spec.preSteps)) fail(errors, `'preSteps' must be a list`);
    else spec.preSteps.forEach((s, i) => {
      if (!isPlainObject(s)) return fail(errors, `preSteps[${i}] must be a mapping`);
      const kinds = Object.keys(s).filter((k) => PRESTEP_KINDS.has(k));
      if (kinds.length !== 1) {
        return fail(errors, `preSteps[${i}] must be exactly one of ${[...PRESTEP_KINDS].join('|')}`);
      }
      const kind = kinds[0];
      if (kind === 'fill') {
        if (!isPlainObject(s.fill) || !isNonEmptyString(s.fill.selector) || typeof s.fill.value !== 'string') {
          fail(errors, `preSteps[${i}].fill needs {selector, value}`);
        }
      } else if (!isNonEmptyString(s[kind])) {
        fail(errors, `preSteps[${i}].${kind} must be a selector string`);
      }
    });
  }

  if (!isPlainObject(spec.capture) || !['fullPage', 'element'].includes(spec.capture.mode)) {
    fail(errors, `'capture' must be {mode: fullPage|element}`);
  } else if (spec.capture.mode === 'element' && !isNonEmptyString(spec.capture.selector)) {
    fail(errors, `capture.mode=element needs capture.selector`);
  }

  if (spec.annotations !== undefined) {
    if (!Array.isArray(spec.annotations)) fail(errors, `'annotations' must be a list`);
    else spec.annotations.forEach((a, i) => {
      if (!isPlainObject(a) || !Number.isInteger(a.n) || a.n < 1 || a.n > 99) {
        return fail(errors, `annotations[${i}] needs integer n in 1..99`);
      }
      if (!isNonEmptyString(a.anchor)) fail(errors, `annotations[${i}].anchor (selector) is required`);
      if (!isNonEmptyString(a.text)) fail(errors, `annotations[${i}].text is required`);
      if (a.offset !== undefined && (!isPlainObject(a.offset) || typeof a.offset.x !== 'number' || typeof a.offset.y !== 'number')) {
        fail(errors, `annotations[${i}].offset must be {x, y}`);
      }
      a.offset = a.offset ?? { x: 0, y: 0 };
    });
  }

  if (spec.output !== undefined && (!isNonEmptyString(spec.output) || !spec.output.endsWith('.png'))) {
    fail(errors, `'output' must be a .png filename`);
  }
  spec.output = spec.output ?? `${spec.id}.png`;

  return errors;
}

/**
 * Load + schema-validate every spec under specsDir (*.yaml, excluding the
 * selector registry). Throws a regression-gate PipelineError on the first
 * invalid spec, naming spec id + topic backlink.
 */
export function loadSpecs(specsDir, { only } = {}) {
  const files = fs.readdirSync(specsDir)
    .filter((f) => (f.endsWith('.yaml') || f.endsWith('.yml')) && !f.startsWith('selector-registry'))
    .sort();
  const specs = [];
  const seenIds = new Set();
  for (const f of files) {
    const raw = fs.readFileSync(path.join(specsDir, f), 'utf8');
    let doc;
    try {
      doc = yaml.load(raw);
    } catch (e) {
      throw schemaFailure(f, undefined, `YAML parse error: ${e.message}`);
    }
    const errors = validateSpec(doc, f);
    if (errors.length > 0) {
      throw schemaFailure(doc?.id ?? f, doc?.topic, errors.join('; '));
    }
    if (seenIds.has(doc.id)) {
      throw schemaFailure(doc.id, doc.topic, `duplicate spec id (in ${f})`);
    }
    seenIds.add(doc.id);
    doc.sourceFile = f;
    specs.push(doc);
  }
  if (only) {
    const filtered = specs.filter((s) => s.id === only);
    if (filtered.length === 0) {
      throw schemaFailure(only, undefined, `--spec=${only} matches no spec in ${specsDir}`);
    }
    return filtered;
  }
  return specs;
}
