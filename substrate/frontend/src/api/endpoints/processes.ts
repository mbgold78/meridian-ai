import { apiClient } from '../client';
import type { ApiResponse, Process } from '@/shared/types/api';

export async function getProcesses(domainId?: string): Promise<ApiResponse<Process[]>> {
  const { data } = await apiClient.get<ApiResponse<Process[]>>('/processes', {
    params: domainId ? { domainId } : undefined,
  });
  return data;
}

export async function getProcess(id: string): Promise<ApiResponse<Process>> {
  const { data } = await apiClient.get<ApiResponse<Process>>(`/processes/${id}`);
  return data;
}
