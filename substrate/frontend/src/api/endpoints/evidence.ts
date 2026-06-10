import { apiClient } from '../client';
import type { ApiResponse, EvidenceChainEntry } from '@/shared/types/api';

export async function getEvidenceForEntity(entityId: string): Promise<ApiResponse<EvidenceChainEntry[]>> {
  const { data } = await apiClient.get<ApiResponse<EvidenceChainEntry[]>>('/evidence/entity', {
    params: { entityId },
  });
  return data;
}

export async function getEvidenceForDomain(domainId: string): Promise<ApiResponse<EvidenceChainEntry[]>> {
  const { data } = await apiClient.get<ApiResponse<EvidenceChainEntry[]>>('/evidence/domain', {
    params: { domainId },
  });
  return data;
}
