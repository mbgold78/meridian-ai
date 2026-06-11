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

/**
 * Wire contract = the backend `ImpactAnalysisRequest` record verbatim:
 * {domainId, systemIds, consolidationDescription}. The pre-fix shape sent
 * {description, domainId} — Jackson silently dropped the unknown key, left
 * systemIds null, and every click 500'd on the service's first line.
 */
export async function analyzeImpact(request: {
  domainId: string;
  systemIds: string[];
  consolidationDescription: string;
}): Promise<ApiResponse<ImpactAnalysisResult>> {
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
