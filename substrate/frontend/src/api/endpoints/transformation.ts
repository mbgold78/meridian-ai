import { apiClient } from '../client';
import type { ApiResponse, TransformationPlan } from '@/shared/types/api';

export async function getTransformationPlan(domainId?: string): Promise<ApiResponse<TransformationPlan>> {
  const { data } = await apiClient.get<ApiResponse<TransformationPlan>>('/transformation/plan', {
    params: domainId ? { domainId } : undefined,
  });
  return data;
}
