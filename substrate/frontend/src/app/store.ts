import { configureStore } from '@reduxjs/toolkit';
import personaReducer from '../features/personas/personaSlice';

/**
 * Redux store — persona state only. homepoc also registered chatSlice and
 * boardSlice; both were dropped in the port together with their orphaned
 * feature surfaces (port map §2.5 step 5 / step 10). Server state lives in
 * React Query; UI-only designer state lives in the zustand store under
 * features/capability-designer.
 */
export const store = configureStore({
  reducer: {
    persona: personaReducer,
  },
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
