import fs from 'node:fs';
import path from 'node:path';
import { EXIT, PipelineError } from './errors.js';
import { fromPipelineRoot } from './paths.js';

/**
 * Image-reference link check (DOC-1 S3; plan §6 `:docs:assemble` link check,
 * acceptance A3 "links resolve").
 *
 * Scans the markdown roots listed in config.assemble.linkCheck (files or
 * directories, pipeline-root-relative) for image references in BOTH styles the
 * manual uses:
 *   1. markdown links/images:  [text](path/to/file.png)  /  ![alt](file.png)
 *   2. plain backtick paths:   `path/to/file.png`
 * Every reference is resolved relative to the file that contains it and must
 * exist on disk. The manual's references point into the pipeline's capture
 * output (build/captures — produced by docsCapture, which docsAssemble depends
 * on), so a re-pointed-but-never-produced or renamed-spec-output reference
 * fails here, naming file, line, and the resolved path (exit 5).
 *
 * External URLs (http/https) are skipped; only image extensions are checked —
 * full manual/guide cross-reference checking matures with the S5 assembler.
 */
const IMAGE_EXT = /\.(png|jpe?g|gif|svg|webp)$/i;
const MD_LINK = /!?\[[^\]]*\]\(([^)\s]+)\)/g;
const BACKTICK = /`([^`\n]+)`/g;

export function checkImageLinks({ config, log }) {
  const roots = config.assemble?.linkCheck ?? [];
  if (roots.length === 0) {
    log('link-check: no roots configured (assemble.linkCheck) — skipped');
    return { files: 0, refs: 0, broken: [] };
  }

  const mdFiles = [];
  for (const root of roots) {
    const abs = fromPipelineRoot(root);
    if (!fs.existsSync(abs)) {
      throw new PipelineError(EXIT.ASSEMBLE, `DOCS-ASSEMBLE FAIL link-check root not found: ${abs}`);
    }
    if (fs.statSync(abs).isDirectory()) mdFiles.push(...walkMarkdown(abs));
    else mdFiles.push(abs);
  }

  let refs = 0;
  const broken = [];
  for (const file of mdFiles.sort()) {
    const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
    lines.forEach((line, i) => {
      for (const target of extractImageRefs(line)) {
        refs++;
        const resolved = path.resolve(path.dirname(file), target);
        if (!fs.existsSync(resolved)) {
          broken.push({ file, line: i + 1, target, resolved });
        }
      }
    });
  }

  if (broken.length > 0) {
    for (const b of broken) {
      console.error(
        `DOCS-ASSEMBLE FAIL link-check file=${rel(b.file)} line=${b.line} ref=${b.target} resolved=${b.resolved} reason=missing`,
      );
    }
    throw new PipelineError(
      EXIT.ASSEMBLE,
      `DOCS-ASSEMBLE FAIL link-check ${broken.length} broken image reference(s) across ${mdFiles.length} file(s)`,
    );
  }
  log(`link-check: ${refs} image reference(s) across ${mdFiles.length} markdown file(s) — all resolve`);
  return { files: mdFiles.length, refs, broken };
}

/**
 * Both reference styles. Markdown links are extracted first and removed from
 * the line before the backtick scan, so a backticked filename used as a link
 * LABEL ([`01-x.png`](real/path/01-x.png)) is not mistaken for a standalone
 * path reference — only the link target is checked.
 */
export function extractImageRefs(line) {
  const out = new Set();
  const remainder = line.replace(MD_LINK, (whole, target) => {
    const t = stripFragment(target);
    if (isCheckable(t)) out.add(t);
    return '';
  });
  for (const m of remainder.matchAll(BACKTICK)) {
    const t = stripFragment(m[1].trim());
    if (isCheckable(t)) out.add(t);
  }
  return [...out];
}

function isCheckable(target) {
  if (!IMAGE_EXT.test(target)) return false;
  if (/^[a-z]+:\/\//i.test(target) || target.startsWith('data:')) return false;
  return true;
}

function stripFragment(t) {
  return t.split('#')[0];
}

function walkMarkdown(dir) {
  const out = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walkMarkdown(p));
    else if (e.name.endsWith('.md')) out.push(p);
  }
  return out;
}

function rel(abs) {
  return path.relative(fromPipelineRoot('.'), abs).split(path.sep).join('/');
}
