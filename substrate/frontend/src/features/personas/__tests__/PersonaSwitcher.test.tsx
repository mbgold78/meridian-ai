/**
 * PersonaSwitcher mode rendering:
 *  - anonymous (default context, no AuthProvider needed): the interactive
 *    dropdown, unchanged — selection updates redux + the localStorage key the
 *    api client reads.
 *  - oidc: persona comes from verified claims — read-only identity chip with
 *    display label, derived persona and a sign-out action; no switching.
 */
import { fireEvent, render, screen } from '@testing-library/react';
import { Provider } from 'react-redux';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { store } from '@/app/store';
import { setPersonaLevel } from '@/features/personas/personaSlice';
import { PersonaSwitcher } from '@/features/personas/PersonaSwitcher';
import { AuthContext, type AuthContextValue } from '@/auth/AuthContext';

function renderAnonymous() {
  return render(
    <Provider store={store}>
      <PersonaSwitcher />
    </Provider>,
  );
}

function renderOidc(overrides: Partial<AuthContextValue> = {}) {
  const value: AuthContextValue = {
    mode: 'oidc',
    status: 'authenticated',
    identityLabel: 'Dev Admin',
    personaLevel: 'ADMIN',
    signIn: vi.fn(),
    signOut: vi.fn(),
    ...overrides,
  };
  render(
    <Provider store={store}>
      <AuthContext.Provider value={value}>
        <PersonaSwitcher />
      </AuthContext.Provider>
    </Provider>,
  );
  return value;
}

beforeEach(() => {
  store.dispatch(setPersonaLevel('L1_EXECUTIVE'));
});

describe('PersonaSwitcher — anonymous mode (default, unchanged)', () => {
  it('renders the interactive dropdown for the current store persona', () => {
    renderAnonymous();
    const trigger = screen.getByRole('button', { name: /executive/i });
    expect(trigger).toBeInTheDocument();
    expect(screen.queryByTestId('persona-identity')).toBeNull();
  });

  it('selecting a persona updates redux and the localStorage key the api client reads', () => {
    renderAnonymous();
    fireEvent.click(screen.getByRole('button', { name: /executive/i }));
    fireEvent.click(screen.getByRole('button', { name: /architect/i }));
    expect(store.getState().persona.level).toBe('L3_ANALYST');
    expect(localStorage.getItem('meridian-persona-level')).toBe('L3_ANALYST');
  });
});

describe('PersonaSwitcher — oidc mode (read-only identity)', () => {
  it('renders the identity label and claim-derived persona instead of the switcher', () => {
    renderOidc();
    const chip = screen.getByTestId('persona-identity');
    expect(chip).toHaveTextContent('Dev Admin');
    expect(chip).toHaveTextContent('Administrator'); // ADMIN level, friendly label
    // No dropdown: the persona options are not reachable.
    expect(screen.queryByRole('button', { name: /architect/i })).toBeNull();
    fireEvent.click(chip);
    expect(screen.queryByText(/technical architecture analysis/i)).toBeNull();
  });

  it('does not write the persona localStorage key (identity is claim-derived)', () => {
    renderOidc();
    expect(localStorage.getItem('meridian-persona-level')).toBeNull();
  });

  it('renders known L-levels with their existing labels', () => {
    renderOidc({ personaLevel: 'L5_AUDITOR' });
    expect(screen.getByTestId('persona-identity')).toHaveTextContent('Auditor');
  });

  it('sign-out invokes the auth signOut', () => {
    const value = renderOidc();
    fireEvent.click(screen.getByRole('button', { name: /sign out/i }));
    expect(value.signOut).toHaveBeenCalledTimes(1);
  });
});
