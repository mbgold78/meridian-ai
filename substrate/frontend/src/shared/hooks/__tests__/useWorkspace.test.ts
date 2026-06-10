/**
 * Unit tests for the pure workspace-resolution policy — the substrate
 * replacement for homepoc's first-domain assumption. Policy order:
 * deploy pin → stored user selection → single domain → first non-demo →
 * first overall → null when empty.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { resolveWorkspaceDomain } from '@/shared/hooks/useWorkspace';
import type { Domain } from '@/shared/types/api';

function makeDomain(id: string, name: string, demo: boolean): Domain {
  return {
    id,
    name,
    description: null,
    taxonomyConfig: null,
    regulatoryContext: null,
    metadata: null,
    demo,
    createdAt: '2026-06-01T00:00:00Z',
    updatedAt: '2026-06-01T00:00:00Z',
  };
}

const demoA = makeDomain('d-demo-a', 'Demo A', true);
const demoB = makeDomain('d-demo-b', 'Demo B', true);
const realA = makeDomain('d-real-a', 'Real A', false);
const realB = makeDomain('d-real-b', 'Real B', false);

afterEach(() => {
  vi.restoreAllMocks();
});

describe('resolveWorkspaceDomain', () => {
  it('returns null for an empty domain list (fresh deploy)', () => {
    expect(resolveWorkspaceDomain([])).toBeNull();
  });

  it('returns the only domain when exactly one exists, even a demo one', () => {
    expect(resolveWorkspaceDomain([demoA])).toBe(demoA);
  });

  it('prefers the first non-demo domain when multiple exist', () => {
    expect(resolveWorkspaceDomain([demoA, realA, realB])).toBe(realA);
  });

  it('falls back to the first domain when all are demo', () => {
    expect(resolveWorkspaceDomain([demoA, demoB])).toBe(demoA);
  });

  it('honors the deploy-time pin above everything else', () => {
    expect(
      resolveWorkspaceDomain([realA, realB], { pinnedDomainId: realB.id, storedDomainId: realA.id }),
    ).toBe(realB);
    // A pin may legitimately target a demo workspace (demo deploys):
    expect(
      resolveWorkspaceDomain([demoA, realA], { pinnedDomainId: demoA.id }),
    ).toBe(demoA);
  });

  it('warns and falls through when the pin matches no domain', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    expect(
      resolveWorkspaceDomain([demoA, realA], { pinnedDomainId: 'd-elsewhere' }),
    ).toBe(realA);
    expect(warn).toHaveBeenCalledTimes(1);
    expect(String(warn.mock.calls[0][0])).toContain('d-elsewhere');
  });

  it('honors a stored user selection', () => {
    expect(
      resolveWorkspaceDomain([realA, demoA, realB], { storedDomainId: realB.id }),
    ).toBe(realB);
  });

  it('ignores a stale stored selection that no longer exists', () => {
    expect(
      resolveWorkspaceDomain([demoA, realA], { storedDomainId: 'd-deleted' }),
    ).toBe(realA);
  });
});
