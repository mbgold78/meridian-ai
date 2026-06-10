import { apiClient } from '../client';
import type { ApiResponse } from '@/shared/types/api';
import type { Capability, CapabilityStatus } from '@/types/capability';

/**
 * List all capabilities for a domain. Backend returns a flat array;
 * the Capability Designer builds the tree client-side via
 * {@code parentCapabilityId}.
 */
export async function getCapabilities(
  domainId: string,
): Promise<ApiResponse<Capability[]>> {
  const { data } = await apiClient.get<ApiResponse<Capability[]>>(
    '/capabilities',
    { params: { domainId } },
  );
  return data;
}

export async function getCapability(
  id: string,
): Promise<ApiResponse<Capability>> {
  const { data } = await apiClient.get<ApiResponse<Capability>>(`/capabilities/${id}`);
  return data;
}

export interface CapabilityCreateRequest {
  domainId: string;
  parentCapabilityId: string | null;
  name: string;
  displayName: string;
  description: string | null;
  level: number | null;
  status: CapabilityStatus | null;
  aiGenerated: boolean;
}

export async function createCapability(
  req: CapabilityCreateRequest,
): Promise<ApiResponse<Capability>> {
  const { data } = await apiClient.post<ApiResponse<Capability>>('/capabilities', req);
  return data;
}

export interface CapabilityUpdateRequest {
  displayName?: string | null;
  description?: string | null;
  status?: CapabilityStatus | null;
  renameRationale?: string | null;
}

export async function updateCapability(
  id: string,
  req: CapabilityUpdateRequest,
): Promise<ApiResponse<Capability>> {
  const { data } = await apiClient.patch<ApiResponse<Capability>>(`/capabilities/${id}`, req);
  return data;
}

/**
 * Move a capability under a new parent (or to root with
 * {@code newParentId: null}). Dedicated endpoint so the frontend drag
 * handler can explicitly express "move to root" without being confused
 * with "leave unchanged" in a partial PATCH.
 */
export async function reparentCapability(
  id: string,
  newParentId: string | null,
): Promise<ApiResponse<Capability>> {
  const { data } = await apiClient.patch<ApiResponse<Capability>>(
    `/capabilities/${id}/parent`,
    { newParentId },
  );
  return data;
}

/**
 * Set the capability's 0-based position within its current sibling group.
 * Backend rewrites the whole group as a contiguous cascade so there are
 * no gaps or duplicates afterward. Target values outside
 * {@code [0, siblingCount - 1]} are clamped server-side.
 */
export async function updateCapabilitySequence(
  id: string,
  newSequence: number,
): Promise<ApiResponse<Capability>> {
  const { data } = await apiClient.patch<ApiResponse<Capability>>(
    `/capabilities/${id}/sequence`,
    { newSequence },
  );
  return data;
}
