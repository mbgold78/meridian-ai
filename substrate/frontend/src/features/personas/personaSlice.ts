import { createSlice, PayloadAction } from '@reduxjs/toolkit';

export type PersonaLevel = 'L1_EXECUTIVE' | 'L2_DOMAIN_LEAD' | 'L3_ANALYST' | 'L4_SME' | 'L5_AUDITOR' | 'L6_DATA_OWNER';

interface PersonaState {
  level: PersonaLevel;
  id: string;
}

const initialState: PersonaState = {
  level: 'L1_EXECUTIVE',
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
