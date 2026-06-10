import { apiClient } from '../client';
import type { ApiResponse, EcosystemGraph } from '@/shared/types/api';

export async function getEcosystemGraph(domainId: string): Promise<ApiResponse<EcosystemGraph>> {
  const { data } = await apiClient.get<ApiResponse<EcosystemGraph>>('/ecosystem/graph', {
    params: { domainId },
  });
  return data;
}
