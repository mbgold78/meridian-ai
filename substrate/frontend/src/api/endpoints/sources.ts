import { apiClient } from '../client';
import type { ApiResponse, ProbeResult } from '@/shared/types/api';

export async function probeSource(url: string): Promise<ProbeResult> {
  const { data } = await apiClient.post<ApiResponse<ProbeResult>>('/sources/probe', { url });
  if (data.errors && data.errors.length > 0) throw new Error(data.errors[0].message);
  return data.data!;
}
