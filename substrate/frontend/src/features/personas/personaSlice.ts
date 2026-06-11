import { createSlice, PayloadAction } from '@reduxjs/toolkit';

export type PersonaLevel = 'L1_EXECUTIVE' | 'L2_DOMAIN_LEAD' | 'L3_ANALYST' | 'L4_SME' | 'L5_AUDITOR' | 'L6_DATA_OWNER';

const PERSONA_LEVELS: readonly PersonaLevel[] = [
  'L1_EXECUTIVE', 'L2_DOMAIN_LEAD', 'L3_ANALYST', 'L4_SME', 'L5_AUDITOR', 'L6_DATA_OWNER',
];

/**
 * The localStorage key shared with the axios client (`api/client.ts`) and
 * PersonaSwitcher — the SINGLE persona source of truth in anonymous mode.
 */
export const PERSONA_STORAGE_KEY = 'meridian-persona-level';

interface PersonaState {
  level: PersonaLevel;
  id: string;
}

/**
 * Boot hydration from localStorage, falling back to the SAME default the
 * axios client sends on the wire (`L3_ANALYST`). The pre-fix slice pinned
 * `L1_EXECUTIVE` and never hydrated, so on every fresh session (and any
 * reload after a non-L1 selection) the switcher DISPLAYED one persona while
 * every request EXECUTED as another — display and wire must share one
 * derivation.
 */
function initialLevel(): PersonaLevel {
  try {
    const stored = window.localStorage.getItem(PERSONA_STORAGE_KEY);
    if (stored && (PERSONA_LEVELS as readonly string[]).includes(stored)) {
      return stored as PersonaLevel;
    }
  } catch {
    /* storage unavailable (SSR, privacy mode) — fall through to the default */
  }
  return 'L3_ANALYST';
}

const initialState: PersonaState = {
  level: initialLevel(),
  id: 'default',
};

const personaSlice = createSlice({
  name: 'persona',
  initialState,
  reducers: {
    setPersona(state, action: PayloadAction<PersonaState>) {
      state.level = action.payload.level;
      state.id = action.payload.id;
    },
    setPersonaLevel(state, action: PayloadAction<PersonaLevel>) {
      state.level = action.payload;
    },
  },
});

export const { setPersona, setPersonaLevel } = personaSlice.actions;
export default personaSlice.reducer;
