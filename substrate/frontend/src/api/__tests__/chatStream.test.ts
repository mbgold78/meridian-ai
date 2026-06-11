/**
 * EventSource token transport (docs/auth.md §6): EventSource cannot set
 * headers, so under oidc the access token rides the RFC 6750 §2.3
 * `access_token` query parameter — accepted by the backend ONLY on the
 * configured stream paths (default /api/v1/chat/stream). Anonymous mode keeps
 * the pre-Phase-5 URL byte-for-byte.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { streamChat } from '@/api/endpoints/chat';
import { resetAuthRuntime, setAccessToken, setAuthMode } from '@/auth/runtime';

class FakeEventSource {
  static lastUrl: string | null = null;
  url: string;
  constructor(url: string) {
    this.url = url;
    FakeEventSource.lastUrl = url;
  }
  close() {
    /* noop */
  }
}

beforeEach(() => {
  FakeEventSource.lastUrl = null;
  vi.stubGlobal('EventSource', FakeEventSource);
});

afterEach(() => {
  vi.unstubAllGlobals();
  resetAuthRuntime();
});

describe('streamChat — anonymous mode (default)', () => {
  it('builds the pre-Phase-5 URL: persona query param, no access_token', () => {
    streamChat({ domainId: 'd-1', message: 'hello', personaLevel: 'L1_EXECUTIVE' });
    const url = new URL(FakeEventSource.lastUrl!, 'http://localhost');
    expect(url.pathname).toBe('/api/v1/chat/stream');
    expect(url.searchParams.get('domainId')).toBe('d-1');
    expect(url.searchParams.get('personaLevel')).toBe('L1_EXECUTIVE');
    expect(url.searchParams.has('access_token')).toBe(false);
  });
});

describe('streamChat — oidc mode', () => {
  it('appends the RFC 6750 access_token query parameter on the stream path', () => {
    setAuthMode('oidc');
    setAccessToken('tok-sse');
    streamChat({ domainId: 'd-1', message: 'hello' });
    const url = new URL(FakeEventSource.lastUrl!, 'http://localhost');
    expect(url.pathname).toBe('/api/v1/chat/stream');
    expect(url.searchParams.get('access_token')).toBe('tok-sse');
  });

  it('omits access_token when no token is held yet', () => {
    setAuthMode('oidc');
    streamChat({ domainId: 'd-1', message: 'hello' });
    const url = new URL(FakeEventSource.lastUrl!, 'http://localhost');
    expect(url.searchParams.has('access_token')).toBe(false);
  });
});
