import { apiClient } from '../client';
import type { ApiResponse, DesignDecision, HashChainVerification, ImpactAnalysisResult } from '@/shared/types/api';

export async function getDecisions(domainId?: string): Promise<ApiResponse<DesignDecision[]>> {
  const { data } = await apiClient.get<ApiResponse<DesignDecision[]>>('/design/decisions', {
    params: domainId ? { domainId } : undefined,
  });
  return data;
}

export async function createDecision(decision: Partial<DesignDecision>): Promise<ApiResponse<DesignDecision>> {
  const { data } = await apiClient.post<ApiResponse<DesignDecision>>('/design/decisions', decision);
  return data;
}

export async function analyzeImpact(request: { description: string; domainId: string }): Promise<ApiResponse<ImpactAnalysisResult>> {
  const { data } = await apiClient.post<ApiResponse<ImpactAnalysisResult>>('/design/impact-analysis', request);
  return data;
}

export async function getDecisionLog(domainId: string): Promise<ApiResponse<DesignDecision[]>> {
  const { data } = await apiClient.get<ApiResponse<DesignDecision[]>>('/decision-log', {
    params: { domainId },
  });
  return data;
}

export async function verifyDecisionLog(domainId: string): Promise<ApiResponse<HashChainVerification>> {
  const { data } = await apiClient.get<ApiResponse<HashChainVerification>>('/decision-log/verify', {
    params: { domainId },
  });
  return data;
}
