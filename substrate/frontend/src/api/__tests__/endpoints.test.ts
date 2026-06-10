/**
 * API-layer pins for the two protocol fixes made during the port:
 *   1. The endpoint barrel re-exports EVERY module — homepoc omitted
 *      dna/evidence/intelligence/sources.
 *   2. getCostSummary calls GET /llm/cost-summary (the real backend path;
 *      homepoc's frontend called a nonexistent /cost/summary).
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as endpoints from '@/api/endpoints';
import { apiClient } from '@/api/client';
import type { ApiMeta } from '@/shared/types/api';

const META: ApiMeta = {
  timestamp: '2026-06-09T00:00:00Z',
  requestId: 'test-request',
  version: 'v1',
  personaLevel: 'L3_ANALYST',
};

afterEach(() => {
  vi.restoreAllMocks();
});

describe('endpoint barrel', () => {
  it('exposes the modules homepoc forgot to re-export (dna, evidence, intelligence, sources)', () => {
    expect(endpoints.getDna).toBeTypeOf('function');
    expect(endpoints.getEvidenceForEntity).toBeTypeOf('function');
    expect(endpoints.getEvidenceForDomain).toBeTypeOf('function');
    expect(endpoints.getThesis).toBeTypeOf('function');
    expect(endpoints.getAssistantStatus).toBeTypeOf('function');
    expect(endpoints.probeSource).toBeTypeOf('function');
  });

  it('still exposes the originally-barreled modules', () => {
    expect(endpoints.getDomains).toBeTypeOf('function');
    expect(endpoints.getSystems).toBeTypeOf('function');
    expect(endpoints.getEcosystemGraph).toBeTypeOf('function');
    expect(endpoints.getFindings).toBeTypeOf('function');
    expect(endpoints.getDecisions).toBeTypeOf('function');
    expect(endpoints.sendChat).toBeTypeOf('function');
    expect(endpoints.streamChat).toBeTypeOf('function');
    expect(endpoints.getTransformationPlan).toBeTypeOf('function');
    expect(endpoints.getOrganizationMembers).toBeTypeOf('function');
    expect(endpoints.getProcesses).toBeTypeOf('function');
    expect(endpoints.getCostSummary).toBeTypeOf('function');
    expect(endpoints.getActiveBrandingConfig).toBeTypeOf('function');
    expect(endpoints.getCapabilities).toBeTypeOf('function');
    expect(endpoints.generateAi).toBeTypeOf('function');
  });
});

describe('wire-path pins', () => {
  it('getCostSummary hits GET /llm/cost-summary (CostController lives under /api/v1/llm)', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: {
        data: { totalInputTokens: 0, totalOutputTokens: 0, totalCostEstimate: 0, requestCount: 0 },
        meta: META,
        errors: null,
      },
    });

    await endpoints.getCostSummary();

    expect(get).toHaveBeenCalledWith('/llm/cost-summary');
  });

  it('getFindings hits GET /ecosystem/findings (findings ride the ecosystem controller)', async () => {
    const get = vi.spyOn(apiClient, 'get').mockResolvedValue({
      data: { data: [], meta: META, errors: null },
    });

    await endpoints.getFindings('some-domain');

    expect(get).toHaveBeenCalledWith('/ecosystem/findings', { params: { domainId: 'some-domain' } });
  });
});
