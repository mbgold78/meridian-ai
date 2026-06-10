import { apiClient } from '../client';
import type { ApiResponse, Domain } from '@/shared/types/api';

export async function getDomains(): Promise<ApiResponse<Domain[]>> {
  const { data } = await apiClient.get<ApiResponse<Domain[]>>('/domains');
  return data;
}

export async function getDomain(id: string): Promise<ApiResponse<Domain>> {
  const { data } = await apiClient.get<ApiResponse<Domain>>(`/domains/${id}`);
  return data;
}
