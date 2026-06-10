import { apiClient } from '../client';
import type { ApiResponse, SystemEntity } from '@/shared/types/api';

export async function getSystems(domainId?: string): Promise<ApiResponse<SystemEntity[]>> {
  const { data } = await apiClient.get<ApiResponse<SystemEntity[]>>('/systems', {
    params: domainId ? { domainId } : undefined,
  });
  return data;
}

export async function getSystem(id: string): Promise<ApiResponse<SystemEntity>> {
  const { data } = await apiClient.get<ApiResponse<SystemEntity>>(`/systems/${id}`);
  return data;
}
