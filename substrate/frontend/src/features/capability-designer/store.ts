import { create } from 'zustand';

/**
 * Client-side ephemeral state for the Capability Designer. Intentionally
 * minimal:
 *
 *   - {@code selectedCapabilityId}: the row the detail pane renders, or
 *     {@code null} when the empty-state is shown.
 *   - {@code expandedIds}: which tree nodes are expanded.
 *
 * Server data (the capability list itself) lives in React Query, not here.
 */
interface CapabilityDesignerState {
  selectedCapabilityId: string | null;
  expandedIds: Set<string>;

  select: (id: string | null) => void;
  setExpanded: (id: string, expanded: boolean) => void;
  replaceExpanded: (next: Set<string>) => void;
}

export const useCapabilityDesignerStore = create<CapabilityDesignerState>((set) => ({
  selectedCapabilityId: null,
  expandedIds: new Set(),

  select: (id) => set({ selectedCapabilityId: id }),
  setExpanded: (id, expanded) =>
    set((s) => {
      const next = new Set(s.expandedIds);
      if (expanded) next.add(id);
      else next.delete(id);
      return { expandedIds: next };
    }),
  replaceExpanded: (next) => set({ expandedIds: next }),
}));
