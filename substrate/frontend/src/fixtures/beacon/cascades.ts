/**
 * Cascade fixtures for the Beacon Industries demo.
 *
 * A cascade is the substrate's most distinctive governance primitive: an
 * L0 critique that propagates through the chain of command, with each
 * level either absorbing the work, refining the critique and passing it
 * down, or declining with documented reasoning passed back up. The L0
 * originator retains visibility into every level's decision without
 * having to walk the chain for permission.
 *
 * Stage 6 governance-2 introduced the first in-flight cascade
 * (`c-vendor-cost`); Block 5 added two more so the "Cascades you've
 * issued" grid renders multiple coherent examples spanning bottom-of-
 * chain states (declined-pending, cascaded-still-out at L2,
 * cascaded-still-out at L1) across distinct subsidiary contexts. All
 * originate from the Operating Partner.
 */

import type { BeaconCascade } from './types';

export const BEACON_CASCADES: BeaconCascade[] = [
  {
    id: 'c-vendor-cost',
    originatedBy: 'Operating Partner',
    originatedAt: '2026-04-08',
    originalCritique:
      "Vendor costs across healthcare subsidiaries grew 14% YoY against a 6% benchmark. Identify what's still being paid for that AI-leverage has obsoleted. Surface the depopulation candidates.",
    acceptanceCriteria:
      "Each affected subsidiary returns either (a) a depopulation plan with named contracts and Q3 target dates, or (b) a Decline with documented reasoning for why the contracts remain necessary.",
    currentState: 'in-flight',
    affectedSubsidiarySlug: 'curasync-clinical',
    levels: [
      {
        level: 0,
        ownerLabel: 'Operating Partner',
        state: 'cascaded',
        refinedCritique:
          "Vendor cost growth across healthcare subs is 14% YoY. Identify obsolete contracts and depopulation candidates.",
        timestamp: '2026-04-08',
      },
      {
        level: 1,
        ownerLabel: 'Curasync COO',
        state: 'cascaded',
        refinedCritique:
          "We have three vendor contracts in HIM and front-office stack that may overlap with our coding-suggestion agent and eligibility-verifier scope. Have IT and HIM evaluate.",
        timestamp: '2026-04-11',
      },
      {
        level: 2,
        ownerLabel: 'Curasync HIM Director',
        state: 'declined',
        declineReasoning:
          "Vendor contract 4127 (HIM transcription) cannot be retired this quarter. We do not yet have HIPAA validation on the coding-suggestion agent in production mode; shadow-mode promotion is gated on Compliance review (target Q4 2026). The vendor remains required until then.",
        timestamp: '2026-04-22',
      },
    ],
  },
  {
    id: 'c-tribal-succession',
    originatedBy: 'Operating Partner',
    originatedAt: '2026-04-12',
    originalCritique:
      "Eight single-owner processes are flagged across the portfolio with no documented succession. Each affected subsidiary identifies named successor candidates against the bench within 30 days, or returns a documented Decline explaining why succession is structurally blocked.",
    acceptanceCriteria:
      "Each affected subsidiary returns either (a) a named successor candidate per flagged process with onboarding timeline, or (b) a Decline citing the structural barrier (regulated-role pool too thin, acquired-process consolidation pending, or methodology gap).",
    currentState: 'in-flight',
    affectedSubsidiarySlug: 'sentari-lab',
    levels: [
      {
        level: 0,
        ownerLabel: 'Operating Partner',
        state: 'cascaded',
        refinedCritique:
          "Tribal-knowledge concentration is the highest-frequency finding across the portfolio. Sub COOs identify successor candidates per flagged process or document a structural Decline.",
        timestamp: '2026-04-12',
      },
      {
        level: 1,
        ownerLabel: 'Sentari COO',
        state: 'cascaded',
        refinedCritique:
          "Specimen routing and pathologist review are both single-owner with no documented backup. Lab Operations Director to scope a 6-month cross-train rotation against the bench-layout knowledge base, name two candidates per process by end of month.",
        timestamp: '2026-04-17',
      },
      {
        level: 2,
        ownerLabel: 'Sentari Lab Operations Director',
        state: 'cascaded',
        refinedCritique:
          "Cross-train rotation scoped against current MT roster. Two candidates identified for specimen routing; pathologist review still gated on credentialing pathway. Bench-layout knowledge capture sessions starting next week with senior MT.",
        timestamp: '2026-04-24',
      },
    ],
  },
  {
    id: 'c-tellix-velocity',
    originatedBy: 'Operating Partner',
    originatedAt: '2026-04-09',
    originalCritique:
      "Tellix has been in Plug-In phase for 142 days against the 90-day target. Three other subs in Plug-In moved within the window. Either name a structural blocker that justifies the over-run or commit to a phase-completion date in the next 30 days.",
    acceptanceCriteria:
      "Tellix returns either (a) a phase-completion commitment with named owner and target date within 30 days, or (b) a Decline documenting the structural blocker (regulated-process inventory still inflight, capacity constraint with named hire pending, or methodology gap requiring Beacon support).",
    currentState: 'in-flight',
    affectedSubsidiarySlug: 'tellix-sensors',
    levels: [
      {
        level: 0,
        ownerLabel: 'Operating Partner',
        state: 'cascaded',
        refinedCritique:
          "Tellix Plug-In over-run is 52 days past target with no named blocker on the dashboard. COO returns either a completion commitment or a documented structural Decline.",
        timestamp: '2026-04-09',
      },
      {
        level: 1,
        ownerLabel: 'Tellix COO',
        state: 'cascaded',
        refinedCritique:
          "Capability inventory is 80% complete; the remaining 20% sits in Engineering's custom-spec translation work where two senior engineers carry the tribal knowledge. Engineering Director to scope a 4-week capture sprint and return a Plug-In completion date.",
        timestamp: '2026-04-19',
      },
    ],
  },
];

const cascadesById: Map<string, BeaconCascade> = new Map(
  BEACON_CASCADES.map((c) => [c.id, c]),
);

/**
 * Look up a cascade by id. Returns undefined when the id isn't known —
 * callers should treat missing as a 404 in the demo router.
 */
export function getCascade(id: string): BeaconCascade | undefined {
  return cascadesById.get(id);
}
