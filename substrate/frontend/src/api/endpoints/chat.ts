import { apiClient } from '../client';
import type { ApiResponse } from '@/shared/types/api';

export interface ChatRequest {
  domainId: string;
  message: string;
  personaLevel?: string;
}

export interface ChatResponse {
  reply: string;
  toolCalls: string[];
  inputTokens: number;
  outputTokens: number;
  latencyMs: number;
}

export async function sendChat(request: ChatRequest): Promise<ApiResponse<ChatResponse>> {
  const { data } = await apiClient.post<ApiResponse<ChatResponse>>('/chat', request);
  return data;
}

/**
 * SSE chat stream. NOTE: EventSource bypasses the axios persona-header
 * interceptor (browser EventSource cannot set custom headers) — persona
 * travels as a query param here. Route this through an authenticated
 * transport when OIDC lands (Phase 5).
 */
export function streamChat(request: ChatRequest): EventSource {
  const params = new URLSearchParams({
    domainId: request.domainId,
    message: request.message,
    ...(request.personaLevel ? { personaLevel: request.personaLevel } : {}),
  });
  return new EventSource(`/api/v1/chat/stream?${params.toString()}`);
}
