/**
 * TypeScript type mirroring the backend Capability entity
 * ({@code common/src/main/java/com/meridian/substrate/common/domain/Capability.java}).
 * Stays in sync with the backend DTO shape; new fields land here when the
 * backend adds them. Schema source:
 * {@code db/migration/common/V2__canonical_model.sql} (the substrate squash —
 * the capabilities family, including {@code sequence_order}, is present from V2).
 */

export type CapabilityStatus = 'DRAFT' | 'ACTIVE' | 'DEPRECATED';

export interface Capability {
  id: string;
  domainId: string;
  parentCapabilityId: string | null;
  name: string;
  displayName: string;
  description: string | null;
  level: number;
  status: CapabilityStatus;
  sourcePackId: string | null;
  aiGenerated: boolean;
  metadata: Record<string, unknown> | null;

  /**
   * 0-based position within the sibling group (capabilities sharing the
   * same {@code domainId} + {@code parentCapabilityId}). Drives tree
   * render order; the backend rewrites this as a contiguous cascade on
   * reorder.
   */
  sequenceOrder: number;

  createdAt: string;
  updatedAt: string;
  createdBy: string;
  version: number;
}
