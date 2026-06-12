/**
 * Exit-code contract (plan §6 + no-silent-zero-yield discipline):
 *   0 — success
 *   2 — environment / boot / config failure (jar missing, app died, timeout)
 *   3 — regression-gate failure: spec failed schema OR a selector was not
 *       found. Message format (plan §6):
 *         DOCS-VERIFY FAIL topic=<topic> spec=<id> selector=<selector>
 *   4 — determinism failure: the two capture runs produced different manifests
 *   5 — assemble failure: unknown {{capture:id}} placeholder / missing capture
 */
export const EXIT = {
  OK: 0,
  ENV: 2,
  REGRESSION: 3,
  DETERMINISM: 4,
  ASSEMBLE: 5,
};

export class PipelineError extends Error {
  /**
   * @param {number} exitCode one of EXIT.*
   * @param {string} message  already formatted for the console
   */
  constructor(exitCode, message) {
    super(message);
    this.name = 'PipelineError';
    this.exitCode = exitCode;
  }
}

/** Regression failure in the plan §6 format, naming spec id + topic backlink. */
export function selectorFailure(spec, selector, detail) {
  return new PipelineError(
    EXIT.REGRESSION,
    `DOCS-VERIFY FAIL topic=${spec.topic ?? '?'} spec=${spec.id ?? '?'} selector=${selector}` +
      (detail ? ` (${detail})` : ''),
  );
}

export function schemaFailure(specId, topic, detail) {
  return new PipelineError(
    EXIT.REGRESSION,
    `DOCS-VERIFY FAIL topic=${topic ?? '?'} spec=${specId ?? '?'} schema: ${detail}`,
  );
}
