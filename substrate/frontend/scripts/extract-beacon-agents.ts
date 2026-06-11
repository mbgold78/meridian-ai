/**
 * Beacon agent-fixture extraction (engine-spec agents.md §15; ADR-003 D18).
 *
 * Reads the nine per-subsidiary fixture modules and emits
 * `app/src/main/resources/demopack/beacon-agents.json` — the static input
 * `AgentDemoSeeder` replays through the REAL agent lifecycle services
 * (never row inserts). Strings land VERBATIM (contracts §14.1 discipline).
 *
 * FIXTURE TRUTH: C:/Users/mbgol/dev/meridian-homepoc/frontend/src/demo/beacon/
 * subsidiaries/*.ts (9 files, ~70 agents; `agents.ts` does NOT exist — known
 * doc drift corrected in ADR-003). The substrate copies under
 * `src/fixtures/beacon/subsidiaries/` were diffed byte-identical against the
 * homepoc files on 2026-06-11; this script reads the substrate copies so the
 * extraction is reproducible inside this repo.
 *
 * Run from `substrate/frontend`:  npx tsx scripts/extract-beacon-agents.ts
 */
import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { CREATIVE_SUBSIDIARIES } from '../src/fixtures/beacon/subsidiaries/creative';
import { EDUCATION_SUBSIDIARIES } from '../src/fixtures/beacon/subsidiaries/education';
import { FINANCIAL_SUBSIDIARIES } from '../src/fixtures/beacon/subsidiaries/financial';
import { GOVERNMENT_SUBSIDIARIES } from '../src/fixtures/beacon/subsidiaries/government';
import { HEALTHCARE_SUBSIDIARIES } from '../src/fixtures/beacon/subsidiaries/healthcare';
import { INSTRUMENTATION_SUBSIDIARIES } from '../src/fixtures/beacon/subsidiaries/instrumentation';
import { INSURANCE_SUBSIDIARIES } from '../src/fixtures/beacon/subsidiaries/insurance';
import { LEGAL_SUBSIDIARIES } from '../src/fixtures/beacon/subsidiaries/legal';
import { LOGISTICS_SUBSIDIARIES } from '../src/fixtures/beacon/subsidiaries/logistics';
import type { BeaconSubsidiary } from '../src/fixtures/beacon/types';

// Canonical fixture ordering (mirrors src/fixtures/beacon/index.ts).
const ALL: BeaconSubsidiary[] = [
  ...HEALTHCARE_SUBSIDIARIES,
  ...GOVERNMENT_SUBSIDIARIES,
  ...INSURANCE_SUBSIDIARIES,
  ...INSTRUMENTATION_SUBSIDIARIES,
  ...FINANCIAL_SUBSIDIARIES,
  ...LEGAL_SUBSIDIARIES,
  ...LOGISTICS_SUBSIDIARIES,
  ...EDUCATION_SUBSIDIARIES,
  ...CREATIVE_SUBSIDIARIES,
];

const out = {
  _source:
    'meridian-homepoc/frontend/src/demo/beacon/subsidiaries/*.ts (fixture truth; ' +
    'substrate copies verified byte-identical) via scripts/extract-beacon-agents.ts',
  _copiedAt: '2026-06-11',
  subsidiaries: ALL.map((s) => ({
    slug: s.slug,
    name: s.name,
    description: s.description,
    agents: s.agents.map((a) => ({
      id: a.id,
      name: a.name,
      role: a.role,
      status: a.status,
      recentActivity: a.recentActivity,
      adherence: a.adherence,
      capabilityId: a.capabilityId ?? null,
      deployedBy: a.deployedBy ?? null,
      deployedAt: a.deployedAt ?? null,
      deploymentCriteria: a.deploymentCriteria ?? null,
      oversightChain: a.oversightChain ?? null,
    })),
  })),
};

const here = dirname(fileURLToPath(import.meta.url));
const target = resolve(here, '../../app/src/main/resources/demopack/beacon-agents.json');
writeFileSync(target, JSON.stringify(out, null, 2) + '\n');

const agentCount = ALL.reduce((n, s) => n + s.agents.length, 0);
const byStatus = ALL.flatMap((s) => s.agents).reduce<Record<string, number>>((acc, a) => {
  acc[a.status] = (acc[a.status] ?? 0) + 1;
  return acc;
}, {});
console.log(`Wrote ${target}`);
console.log(`${ALL.length} subsidiaries, ${agentCount} agents`, byStatus);
