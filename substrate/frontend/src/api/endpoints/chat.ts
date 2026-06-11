import { apiClient } from '../client';
import { getAccessToken, getAuthMode } from '@/auth/runtime';
import type { ApiResponse } from '@/shared/types/api';

export interface ChatRequest {
  domainId: string;
  message: string;
  /**
   * Stream transport only (anonymous mode): rides the EventSource URL as the
   * `personaLevel` query param. NOT part of the POST /chat body — the sync
   * wire carries persona in the X-Persona-Level header (anonymous) or the
   * bearer token (oidc); `sendChat` strips it from the body.
   */
  personaLevel?: string;
}

/**
 * One tool invocation trace — mirrors the backend `ToolCallTrace` record
 * verbatim ({toolName, arguments, result, latencyMs}).
 */
export interface ToolCallTrace {
  toolName: string;
  arguments: Record<string, unknown> | null;
  result: string | null;
  latencyMs: number;
}

/**
 * Wire contract of POST /api/v1/chat — mirrors the backend `ChatResponse`
 * record verbatim: `message` (NOT `reply`), `toolCalls` as ToolCallTrace
 * OBJECTS (not strings), no latencyMs at the top level (it lives per tool
 * call). The pre-fix shape here was the unclosed C8 contract mismatch: the
 * assistant bubble never rendered (`reply` is not on the wire) and rendering
 * a trace object as a JSX child unmounted the whole SPA.
 */
export interface ChatResponse {
  message: string;
  toolCalls: ToolCallTrace[];
  inputTokens: number;
  outputTokens: number;
}

export async function sendChat(request: ChatRequest): Promise<ApiResponse<ChatResponse>> {
  // Body = exactly the backend ChatRequest record (domainId, message);
  // persona travels in the header/token, never the body (Jackson would
  // silently drop it anyway — keep the wire honest).
  const { data } = await apiClient.post<ApiResponse<ChatResponse>>('/chat', {
    domainId: request.domainId,
    message: request.message,
  });
  return data;
}

/**
 * SSE chat stream. EventSource bypasses the axios interceptors (browser
 * EventSource cannot set custom headers), so identity travels in the URL:
 *
 *  - anonymous mode: persona as the `personaLevel` query param —
 *    AnonymousIdentitySource accepts it ONLY on the configured stream paths
 *    (the SseTokenBearerTokenResolver mirror).
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
