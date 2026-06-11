/**
 * Pins the chat wire-contract mapping (the closed C8 flag): the backend
 * ChatResponse record is {message, toolCalls: ToolCallTrace[], inputTokens,
 * outputTokens} — the assistant bubble renders `message` (NOT `reply`) and
 * tool pills render each trace's `toolName` (rendering the trace OBJECT as a
 * JSX child unmounted the entire SPA pre-fix).
 */
import { describe, expect, it, beforeEach, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from '@/app/theme';
import { AmbientChatBar } from '@/features/canvas/AmbientChatBar';
import * as chatApi from '@/api/endpoints/chat';
import type { ApiResponse } from '@/shared/types/api';
import type { BrandingConfig } from '@/types/branding';

vi.mock('@/api/endpoints/chat', async () => {
  const actual = await vi.importActual<typeof chatApi>('@/api/endpoints/chat');
  return { ...actual, sendChat: vi.fn() };
});

const sendChat = chatApi.sendChat as unknown as ReturnType<typeof vi.fn>;

function envelope(data: chatApi.ChatResponse): ApiResponse<chatApi.ChatResponse> {
  return {
    data,
    meta: {
      timestamp: '2026-06-11T00:00:00Z',
      requestId: 'test',
      version: '0.1.0',
      personaLevel: 'L3_ANALYST',
    },
    errors: null,
  };
}

const TEST_BRAND: BrandingConfig = {
  id: '00000000-0000-0000-0000-000000000001',
  domainId: 'd-1',
  appDisplayName: 'Substrate',
  tagline: null,
  colorTokensLight: { background: '0 0% 100%', primary: '217 91% 60%' },
  colorTokensDark: { background: '240 10% 3.9%', primary: '217 91% 60%' },
  fontBody: 'system-ui, sans-serif',
  fontHeading: 'system-ui, sans-serif',
  fontMono: 'ui-monospace, monospace',
  spacingTokens: null, radiusTokens: null, shadowTokens: null,
  logoPrimaryBlobKey: null, logoMarkBlobKey: null,
  logoLightBlobKey: null, logoDarkBlobKey: null,
  faviconBlobKey: null, loginSplashBlobKey: null,
  terminologyOverrides: {},
  emailSenderAddress: null, emailSenderName: null, emailReplyTo: null,
  emailHeaderHtml: null, emailFooterHtml: null, emailPrimaryColor: null,
  legalFooterText: null, privacyPolicyUrl: null, termsOfServiceUrl: null,
  customCssUrl: null,
  status: 'ACTIVE',
  createdAt: '2026-06-11T00:00:00Z',
  updatedAt: '2026-06-11T00:00:00Z',
  createdBy: 'test',
  version: 0,
};

beforeEach(() => {
  sendChat.mockReset();
  // jsdom does not implement scrollIntoView (the bar auto-scrolls the
  // conversation on each message append).
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
});

function renderBar(onSignal = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <ThemeProvider brandingOverride={TEST_BRAND}>
        <AmbientChatBar domainId="d-1" onSignal={onSignal} />
      </ThemeProvider>
    </QueryClientProvider>,
  );
  return onSignal;
}

describe('AmbientChatBar wire-contract mapping', () => {
  it('renders data.message as the assistant reply and toolName pills for trace objects', async () => {
    sendChat.mockResolvedValue(
      envelope({
        message: 'Your biggest risk is the Glacier Core dependency.',
        toolCalls: [
          { toolName: 'find_findings', arguments: { domainId: 'd-1' }, result: '[]', latencyMs: 42 },
        ],
        inputTokens: 10,
        outputTokens: 20,
      }),
    );
    const onSignal = renderBar();

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'What is my biggest risk?' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    await waitFor(() =>
      expect(
        screen.getByText('Your biggest risk is the Glacier Core dependency.'),
      ).toBeInTheDocument(),
    );
    // tool pill renders the trace's toolName, never the object
    expect(screen.getByText('find_findings')).toBeInTheDocument();
    // body carries exactly the backend ChatRequest record fields
    expect(sendChat).toHaveBeenCalledWith({ domainId: 'd-1', message: 'What is my biggest risk?' });
    // the finding tool elicits the findings spotlight signal
    expect(onSignal).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'findings' }),
    );
  });

  it('surfaces envelope errors as the assistant message instead of hanging on typing dots', async () => {
    sendChat.mockResolvedValue({
      data: null,
      meta: {
        timestamp: '2026-06-11T00:00:00Z',
        requestId: 'test',
        version: '0.1.0',
        personaLevel: 'L3_ANALYST',
      },
      errors: [{ code: 'LLM_UNAVAILABLE', message: 'No API key configured', field: null }],
    } as unknown as ApiResponse<chatApi.ChatResponse>);
    renderBar();

    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'hello' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ask' }));

    await waitFor(() =>
      expect(screen.getByText('No API key configured')).toBeInTheDocument(),
    );
  });
});
