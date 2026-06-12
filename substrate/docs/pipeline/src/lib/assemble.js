import fs from 'node:fs';
import path from 'node:path';
import { EXIT, PipelineError } from './errors.js';
import { fromPipelineRoot } from './paths.js';

/**
 * Assemble step — S1 skeleton (plan §6 `:docs:assemble`; full guide assembly
 * matures in S3-S5). What works NOW:
 *   - walks guide markdown sources (config.assemble.guidesDir, recursive)
 *   - resolves {{capture:<spec-id>}} placeholders into relative image links
 *     (annotated variant when the spec has annotations; `{{capture:<id>:base}}`
 *     forces the unannotated regression artifact) + a numbered callout legend
 *   - copies the referenced PNGs into <outDir>/captures/
 *   - FAILS (exit 5) on an unknown spec id or a missing capture file, naming
 *     the guide file, placeholder, and guide-topic backlink
 */
export function assembleGuides({ config, specs, log }) {
  const guidesDir = fromPipelineRoot(config.assemble.guidesDir);
  const outDir = fromPipelineRoot(config.assemble.outDir);
  const capturesDir = fromPipelineRoot(config.output.capturesDir);
  const specById = new Map(specs.map((s) => [s.id, s]));

  if (!fs.existsSync(guidesDir)) {
    throw new PipelineError(EXIT.ASSEMBLE, `DOCS-ASSEMBLE FAIL guidesDir not found: ${guidesDir}`);
  }
  const guides = walkMarkdown(guidesDir);
  if (guides.length === 0) {
    log(`assemble: no markdown sources under ${guidesDir} — nothing to do`);
    return { guides: 0, images: 0 };
  }

  fs.rmSync(outDir, { recursive: true, force: true });
  const outCaptures = path.join(outDir, 'captures');
  fs.mkdirSync(outCaptures, { recursive: true });

  let imageCount = 0;
  for (const rel of guides) {
    const src = fs.readFileSync(path.join(guidesDir, rel), 'utf8');
    const outFile = path.join(outDir, rel);
    fs.mkdirSync(path.dirname(outFile), { recursive: true });

    const resolved = src.replace(/\{\{capture:([a-z0-9-]+)(:base)?\}\}/g, (_, id, baseOnly) => {
      const spec = specById.get(id);
      if (!spec) {
        throw new PipelineError(
          EXIT.ASSEMBLE,
          `DOCS-ASSEMBLE FAIL guide=${rel} placeholder=${id} reason=unknown-spec`,
        );
      }
      const fileName = !baseOnly && spec.annotations?.length
        ? spec.output.replace(/\.png$/, '.annotated.png')
        : spec.output;
      const capturePath = path.join(capturesDir, fileName);
      if (!fs.existsSync(capturePath)) {
        throw new PipelineError(
          EXIT.ASSEMBLE,
          `DOCS-ASSEMBLE FAIL guide=${rel} placeholder=${id} topic=${spec.topic} reason=missing-capture file=${fileName} (run docsCapture first)`,
        );
      }
      fs.copyFileSync(capturePath, path.join(outCaptures, fileName));
      imageCount++;
      const relLink = path.posix.join(
        ...path.relative(path.dirname(outFile), outCaptures).split(path.sep),
        fileName,
      );
      let block = `![${spec.title}](${relLink})`;
      if (!baseOnly && spec.annotations?.length) {
        block += '\n\n' + spec.annotations.map((a) => `${a.n}. ${a.text}`).join('\n');
      }
      return block;
    });

    fs.writeFileSync(outFile, resolved);
    log(`assemble: ${rel} -> ${path.relative(fromPipelineRoot('.'), outFile)}`);
  }
  return { guides: guides.length, images: imageCount };
}

function walkMarkdown(dir, prefix = '') {
  const out = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const rel = prefix ? `${prefix}${path.sep}${e.name}` : e.name;
    if (e.isDirectory()) out.push(...walkMarkdown(path.join(dir, e.name), rel));
    else if (e.name.endsWith('.md')) out.push(rel);
  }
  return out.sort();
}
