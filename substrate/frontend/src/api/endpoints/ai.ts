import { apiClient } from '../client';
import type { ApiResponse } from '@/shared/types/api';

/**
 * TypeScript mirror of the backend DraftEntity DTO from Phase 2A.
 * {@code proposedFields} is the allowlisted schema fragment (Capability
 * shape for now); Stage D renders them read-only — Phase 2B-2 will turn
 * them into a commit flow.
 */
export interface DraftEntity {
  entityType: string;
  proposedFields: Record<string, unknown>;
  evidenceIds: string[];
  assertionId: string;
}

export interface GenerateResponse {
  drafts: DraftEntity[];
  filteredCount: number;
  explanation: string | null;
}

export type GenerativeSurface =
  | 'CAPABILITY_DESIGNER'
  | 'ENVIRONMENT_DESIGNER'
  | 'PLAN_DESIGNER'
  | 'ANALYST_QUERY';

export interface GenerateRequest {
  domainId: string;
  surface: GenerativeSurface;
  entityType: string;
  readerIds: string[];
}

export async function generate(
  req: GenerateRequest,
): Promise<ApiResponse<GenerateResponse>> {
  const { data } = await apiClient.post<ApiResponse<GenerateResponse>>(
    '/ai/generative',
    req,
  );
  return data;
}
