import { apiClient } from '../client';
import type { ApiResponse, CostSummary } from '@/shared/types/api';

/**
 * Backend path is GET /api/v1/llm/cost-summary (CostController is mapped
 * under /api/v1/llm). homepoc's frontend called a nonexistent
 * /cost/summary — dead code there (zero callers); fixed during the port.
 */
export async function getCostSummary(): Promise<ApiResponse<CostSummary>> {
  const { data } = await apiClient.get<ApiResponse<CostSummary>>('/llm/cost-summary');
  return data;
}
