import { apiClient } from '../client';
import type { ApiResponse, ReductionThesis, AssistantStatus } from '@/shared/types/api';

export async function getThesis(domainId: string): Promise<ApiResponse<ReductionThesis>> {
  const { data } = await apiClient.get<ApiResponse<ReductionThesis>>('/intelligence/thesis', {
    params: { domainId },
  });
  return data;
}

export async function getAssistantStatus(domainId: string): Promise<ApiResponse<AssistantStatus>> {
  const { data } = await apiClient.get<ApiResponse<AssistantStatus>>('/intelligence/assistant', {
    params: { domainId },
  });
  return data;
}
