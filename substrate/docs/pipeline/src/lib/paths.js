import path from 'node:path';
import { fileURLToPath } from 'node:url';

/** Absolute path of the pipeline node project (substrate/docs/pipeline). */
export const PIPELINE_ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
);

export const SPECS_DIR = path.join(PIPELINE_ROOT, 'specs');
export const CONFIG_FILE = path.join(PIPELINE_ROOT, 'pipeline.config.yaml');

/** Resolve a config-relative path against the pipeline root. */
export function fromPipelineRoot(rel) {
  return path.resolve(PIPELINE_ROOT, rel);
}
