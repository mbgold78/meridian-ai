import { apiClient } from '../client';
import { getAccessToken, getAuthMode } from '@/auth/runtime';
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
 * SSE chat stream. EventSource bypasses the axios interceptors (browser
 * EventSource cannot set custom headers), so identity travels in the URL:
 *
 *  - anonymous mode: persona as a query param (pre-Phase-5 behavior verbatim).
 *  - oidc mode: the access token as the RFC 6750 §2.3 `access_token` query
 *    parameter — the transport Stage 1 chose (SseTokenBearerTokenResolver
 *    accepts it ONLY on the configured stream paths, default
 *    /api/v1/chat/stream; docs/auth.md §6).
 */
export function streamChat(request: ChatRequest): EventSource {
  const params = new URLSearchParams({
    domainId: request.domainId,
    message: request.message,
    ...(request.personaLevel ? { personaLevel: request.personaLevel } : {}),
  });
  if (getAuthMode() === 'oidc') {
    const token = getAccessToken();
    if (token) {
      params.set('access_token', token);
    }
  }
  return new EventSource(`/api/v1/chat/stream?${params.toString()}`);
}
