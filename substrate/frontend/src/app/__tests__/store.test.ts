/**
 * Store wiring test — pins the C7 state decision: personaSlice kept,
 * chatSlice/boardSlice dropped with their orphaned feature surfaces.
 */
import { describe, expect, it } from 'vitest';
import { store } from '@/app/store';
import { setPersona, setPersonaLevel } from '@/features/personas/personaSlice';

describe('redux store', () => {
  it('registers exactly the persona slice (chat/board dropped in the port)', () => {
    const state = store.getState() as Record<string, unknown>;
    expect(Object.keys(state)).toEqual(['persona']);
  });

  it('persona reducer handles setPersonaLevel and setPersona', () => {
    store.dispatch(setPersonaLevel('L5_AUDITOR'));
    expect(store.getState().persona.level).toBe('L5_AUDITOR');

    store.dispatch(setPersona({ level: 'L3_ANALYST', id: 'analyst-7' }));
    expect(store.getState().persona).toEqual({ level: 'L3_ANALYST', id: 'analyst-7' });
  });
});
