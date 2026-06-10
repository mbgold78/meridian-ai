import '@testing-library/jest-dom';
import { vi, afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

/**
 * jsdom doesn't implement window.matchMedia. ThemeProvider reads it for
 * dark-mode system preference, so we polyfill with a stub that returns
 * "no dark preference" and honors addEventListener/removeEventListener.
 */
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
});

/**
 * jsdom doesn't implement ResizeObserver. The Capability Designer's tree
 * pane uses it to measure available height; react-arborist touches it too.
 * Stub with no-op methods so construction doesn't throw.
 */
class NoopResizeObserver {
  observe() { /* noop */ }
  unobserve() { /* noop */ }
  disconnect() { /* noop */ }
}
(globalThis as unknown as { ResizeObserver: typeof NoopResizeObserver }).ResizeObserver =
  (globalThis as unknown as { ResizeObserver?: typeof NoopResizeObserver }).ResizeObserver
  ?? NoopResizeObserver;

// React Testing Library unmounts between tests when this runs per test.
afterEach(() => {
  cleanup();
  // Clean CSS vars so one test's overrides don't bleed into the next.
  const html = document.documentElement;
  const names: string[] = [];
  for (let i = 0; i < html.style.length; i++) {
    const n = html.style.item(i);
    if (n.startsWith('--')) names.push(n);
  }
  names.forEach((n) => html.style.removeProperty(n));
  html.classList.remove('dark');
  window.localStorage.clear();
});
