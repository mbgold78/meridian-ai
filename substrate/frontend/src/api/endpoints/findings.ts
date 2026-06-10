import { apiClient } from '../client';
import type { ApiResponse, Finding } from '@/shared/types/api';

export async function getFindings(domainId?: string): Promise<ApiResponse<Finding[]>> {
  const { data } = await apiClient.get<ApiResponse<Finding[]>>('/ecosystem/findings', {
    params: domainId ? { domainId } : undefined,
  });
  return data;
}
