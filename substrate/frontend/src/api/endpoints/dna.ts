import { apiClient } from '../client';
import type { ApiResponse, DnaConfig } from '@/shared/types/api';

export async function getDna(): Promise<ApiResponse<DnaConfig>> {
  const { data } = await apiClient.get<ApiResponse<DnaConfig>>('/dna');
  return data;
}
