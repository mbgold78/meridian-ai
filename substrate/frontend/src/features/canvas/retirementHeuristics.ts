import type { Process, SystemEntity } from '@/shared/types/api';

/**
 * TODO(S14): demo retirement-wave heuristics — replace with plan-engine data.
 *
 * These functions map system type/status and process classification/risk to
 * a retirement month for the canvas Target/Waves lenses. They are DEMO
 * HEURISTICS inherited from homepoc, extracted here (port-map §2.5 step 7 /
 * fixture seam S14) so the seam is greppable and severable in one place.
 *
 * Why they were kept rather than replaced: the only plan endpoint that
 * exists today (GET /transformation/plan — api/endpoints/transformation.ts)
 * serves the transformation-plan fixture (seam S4) and its TransformationPlan
 * shape carries wave/initiative aggregates only — no per-system or
 * per-process retirement mapping. When the plan engine lands (redesign doc
 * 06), replace both functions with plan-derived retirement data and delete
 * this module.
 */

/** Map system type / status → retirement wave month. null = core, stays. */
export function retirementMonth(system: SystemEntity): number | null {
  const type = (system.systemType ?? '').toLowerCase();
  const status = (system.status ?? '').toLowerCase();
  if (status === 'deprecated') return 4;
  if (type === 'legacy') return 6;
  if (type === 'ancillary') return 11;
  if (type === 'vendor') return 15;
  return null; // core — stays
}

/** Map process classification / risk → retirement wave month. null = stays. */
export function processRetirementMonth(proc: Process): number | null {
  const cls = proc.classification?.toUpperCase() ?? '';
  const risk = proc.riskRating?.toUpperCase() ?? '';
  if (cls === 'TRIBAL_KNOWLEDGE') return 8;
  if (cls === 'BRIDGE') return 12;
  if (risk === 'HIGH' || risk === 'CRITICAL') return 10;
  return null;
}
