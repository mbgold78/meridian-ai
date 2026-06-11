/**
 * apiClient interceptor behavior in both auth modes (Phase 5 Stage 2):
 *
 *  anonymous (default runtime state) — pre-Phase-5 wire shape byte-for-byte:
 *  X-Persona-Level from localStorage (default L3_ANALYST), no Authorization,
 *  401s pass through with no side effects.
 *
 *  oidc — Bearer token replaces the persona header as the identity source
 *  (S9 severed); 401 notifies the registered re-auth handler and still
 *  rejects to the caller.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { apiClient } from '@/api/client';
import {
  registerUnauthorizedHandler,
  resetAuthRuntime,
  setAccessToken,
  setAuthMode,
} from '@/auth/runtime';

let captured: InternalAxiosRequestConfig | null = null;
const originalAdapter = apiClient.defaults.adapter;

function installOkAdapter() {
  apiClient.defaults.adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    captured = config;
    return { data: {}, status: 200, statusText: 'OK', headers: {}, config };
  };
}

function install401Adapter() {
  apiClient.defaults.adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
    captured = config;
    const response = {
      data: {},
      status: 401,
      statusText: 'Unauthorized',
      headers: {},
      config,
    } as AxiosResponse;
    throw new AxiosError('Request failed with status code 401', 'ERR_BAD_REQUEST', config, null, response);
  };
}

beforeEach(() => {
  captured = null;
});

afterEach(() => {
  apiClient.defaults.adapter = originalAdapter;
  resetAuthRuntime();
});

describe('apiClient — anonymous mode (default)', () => {
  it('attaches X-Persona-Level (L3_ANALYST default) and no Authorization header', async () => {
    installOkAdapter();
    await apiClient.get('/domains');
    expect(captured?.headers['X-Persona-Level']).toBe('L3_ANALYST');
    expect(captured?.headers['Authorization']).toBeUndefined();
  });

  it('reads the persona level from the localStorage key shared with PersonaSwitcher', async () => {
    localStorage.setItem('meridian-persona-level', 'L1_EXECUTIVE');
    installOkAdapter();
    await apiClient.get('/domains');
    expect(captured?.headers['X-Persona-Level']).toBe('L1_EXECUTIVE');
  });

  it('passes a 401 through unchanged without invoking any re-auth handler', async () => {
    const handler = vi.fn();
    registerUnauthorizedHandler(handler); // even if registered, anonymous mode never calls it
    install401Adapter();
    await expect(apiClient.get('/domains')).rejects.toMatchObject({
      response: { status: 401 },
    });
    expect(handler).not.toHaveBeenCalled();
  });
});

describe('apiClient — oidc mode', () => {
  it('attaches the Bearer token and does NOT send the persona header (S9 severed)', async () => {
    localStorage.setItem('meridian-persona-level', 'L1_EXECUTIVE'); // must be ignored
    setAuthMode('oidc');
    setAccessToken('tok-123');
    installOkAdapter();
    await apiClient.get('/domains');
    expect(captured?.headers['Authorization']).toBe('Bearer tok-123');
    expect(captured?.headers['X-Persona-Level']).toBeUndefined();
  });

  it('sends neither header before a token exists (the provider gates rendering anyway)', async () => {
    setAuthMode('oidc');
    installOkAdapter();
    await apiClient.get('/domains');
    expect(captured?.headers['Authorization']).toBeUndefined();
    expect(captured?.headers['X-Persona-Level']).toBeUndefined();
  });

  it('notifies the re-auth handler on 401 and still rejects to the caller', async () => {
    const handler = vi.fn();
    setAuthMode('oidc');
    setAccessToken('tok-stale');
    registerUnauthorizedHandler(handler);
    install401Adapter();
    await expect(apiClient.get('/domains')).rejects.toMatchObject({
      response: { status: 401 },
    });
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('does not notify the re-auth handler on non-401 failures', async () => {
    const handler = vi.fn();
    setAuthMode('oidc');
    setAccessToken('tok-123');
    registerUnauthorizedHandler(handler);
    apiClient.defaults.adapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
      const response = {
        data: {},
        status: 500,
        statusText: 'Internal Server Error',
        headers: {},
        config,
      } as AxiosResponse;
      throw new AxiosError('boom', 'ERR_BAD_RESPONSE', config, null, response);
    };
    await expect(apiClient.get('/domains')).rejects.toMatchObject({
      response: { status: 500 },
    });
    expect(handler).not.toHaveBeenCalled();
  });
});
