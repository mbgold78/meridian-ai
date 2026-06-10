import { useEffect, useState, type ReactNode, type KeyboardEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, PencilSimple, X } from '@phosphor-icons/react';
import {
  getCapabilities,
  updateCapability,
  type CapabilityUpdateRequest,
} from '@/api/endpoints';
import type { Capability } from '@/types/capability';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useTerminology } from '@/app/theme';
import { useCapabilityDesignerStore } from './store';

interface CapabilityDetailPaneProps {
  /** The domain the designer is scoped to; used to look up the selected capability. */
  domainId: string;
}

/**
 * Right-pane view of the currently-selected capability with inline edit
 * for {@code displayName} and {@code description}:
 *
 * <ul>
 *   <li><b>Click-to-edit</b> — the Pencil affordance is always visible
 *       at reduced opacity and brightens to full on row hover / focus.
 *       (Hover-reveal fails for discoverability, screen-reader users,
 *       keyboard users, and touch devices.)</li>
 *   <li><b>Save on explicit action</b> (Check button or Enter for single-
 *       line fields); <b>cancel on Escape</b> or the X button.</li>
 *   <li><b>Empty {@code displayName} is rejected</b> with an inline error
 *       message — the backend's rename history would record a meaningless
 *       transition otherwise.</li>
 *   <li><b>React Query cache is invalidated</b> on mutation success so
 *       the tree + detail pane re-render with the new value.</li>
 * </ul>
 *
 * Terminology flows through {@code t()} — a workspace that renames
 * Capability → Service sees matching labels in the empty state, field
 * labels, and error messages.
 */
export function CapabilityDetailPane({ domainId }: CapabilityDetailPaneProps) {
  const selectedId = useCapabilityDesignerStore((s) => s.selectedCapabilityId);
  const { t } = useTerminology();

  const { data } = useQuery({
    queryKey: ['capabilities', domainId],
    queryFn: () => getCapabilities(domainId),
    enabled: !!domainId,
  });

  const selected = selectedId
    ? data?.data.find((c) => c.id === selectedId)
    : null;

  if (!selected) {
    return (
      <div className="flex h-full items-center justify-center p-8 text-center">
        <div className="max-w-sm">
          <p className="text-lg font-medium">
            {`Select a ${t('capability', 'singular').toLowerCase()} to view its details`}
          </p>
          <p className="mt-1 text-sm text-muted-foreground">
            {`Pick any ${t('capability', 'singular').toLowerCase()} from the tree on the left.`}
          </p>
        </div>
      </div>
    );
  }

  return (
    <ScrollArea className="h-full">
      <div className="p-6 space-y-6 max-w-3xl">
        <DisplayNameEditor capability={selected} domainId={domainId} />

        <div className="text-sm text-muted-foreground font-mono -mt-4">
          {selected.name}
        </div>

        <Separator />

        <DescriptionEditor capability={selected} domainId={domainId} />

        <div className="grid grid-cols-2 gap-6">
          <Field label="Status">
            <StatusBadge status={selected.status} />
          </Field>
          <Field label="Level">
            <span className="text-sm">{selected.level}</span>
          </Field>
          <Field label="AI-generated">
            <span className="text-sm">{selected.aiGenerated ? 'Yes' : 'No'}</span>
          </Field>
          <Field label="Created by">
            <span className="text-sm">{selected.createdBy}</span>
          </Field>
        </div>

        <Separator />

        <div className="text-xs text-muted-foreground font-mono space-y-1">
          <div>id: {selected.id}</div>
          <div>parent: {selected.parentCapabilityId ?? '(root)'}</div>
          <div>domain: {selected.domainId}</div>
          <div>version: {selected.version}</div>
          <div>created: {formatTimestamp(selected.createdAt)}</div>
          <div>updated: {formatTimestamp(selected.updatedAt)}</div>
        </div>
      </div>
    </ScrollArea>
  );
}

function DisplayNameEditor({ capability, domainId }: { capability: Capability; domainId: string }) {
  const { t } = useTerminology();
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(capability.displayName);
  const [error, setError] = useState<string | null>(null);
  const mutation = usePatchCapability(capability.id, domainId, () => setEditing(false));

  // Resync when a different capability becomes selected.
  useEffect(() => {
    setValue(capability.displayName);
    setError(null);
    setEditing(false);
  }, [capability.id, capability.displayName]);

  const commit = () => {
    const trimmed = value.trim();
    if (trimmed.length === 0) {
      setError(`${t('capability', 'singular')} name cannot be empty`);
      return;
    }
    if (trimmed === capability.displayName) {
      setEditing(false);
      return;
    }
    setError(null);
    mutation.mutate({ displayName: trimmed });
  };
  const cancel = () => {
    setValue(capability.displayName);
    setError(null);
    setEditing(false);
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') { e.preventDefault(); commit(); }
    if (e.key === 'Escape') { e.preventDefault(); cancel(); }
  };

  if (!editing) {
    return (
      <header className="group flex items-center gap-2">
        <h2 className="text-xl font-semibold tracking-tight">{capability.displayName}</h2>
        <Button
          variant="ghost"
          size="icon"
          className="h-7 w-7 opacity-40 group-hover:opacity-100 focus-visible:opacity-100 transition-opacity"
          aria-label={`Edit ${t('capability', 'singular').toLowerCase()} name`}
          onClick={() => setEditing(true)}
        >
          <PencilSimple size={14} />
        </Button>
      </header>
    );
  }

  return (
    <div className="space-y-1.5">
      <div className="flex items-center gap-2">
        <Input
          autoFocus
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={onKey}
          disabled={mutation.isPending}
          aria-invalid={!!error}
          aria-label={`${t('capability', 'singular')} name`}
          className="text-xl font-semibold tracking-tight h-11"
        />
        <Button size="icon" onClick={commit} disabled={mutation.isPending} aria-label="Save">
          <Check size={16} />
        </Button>
        <Button variant="ghost" size="icon" onClick={cancel} disabled={mutation.isPending} aria-label="Cancel">
          <X size={16} />
        </Button>
      </div>
      {error && <p className="text-sm text-destructive" role="alert">{error}</p>}
    </div>
  );
}

function DescriptionEditor({ capability, domainId }: { capability: Capability; domainId: string }) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(capability.description ?? '');
  const mutation = usePatchCapability(capability.id, domainId, () => setEditing(false));

  useEffect(() => {
    setValue(capability.description ?? '');
    setEditing(false);
  }, [capability.id, capability.description]);

  const commit = () => {
    const trimmed = value.trim();
    if (trimmed === (capability.description ?? '')) {
      setEditing(false);
      return;
    }
    mutation.mutate({ description: trimmed.length === 0 ? null : trimmed });
  };
  const cancel = () => {
    setValue(capability.description ?? '');
    setEditing(false);
  };
  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Escape') { e.preventDefault(); cancel(); }
    // Enter inserts newline for multi-line description — Save is explicit.
  };

  return (
    <Field label="Description">
      {editing ? (
        <div className="space-y-2">
          <textarea
            autoFocus
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={onKey}
            disabled={mutation.isPending}
            rows={4}
            className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          />
          <div className="flex gap-2">
            <Button size="sm" onClick={commit} disabled={mutation.isPending}>
              <Check size={16} className="mr-1" /> Save
            </Button>
            <Button size="sm" variant="ghost" onClick={cancel} disabled={mutation.isPending}>
              <X size={16} className="mr-1" /> Cancel
            </Button>
          </div>
        </div>
      ) : (
        <div className="group flex items-start gap-2">
          <div className="flex-1">
            {capability.description ? (
              <p className="text-sm whitespace-pre-wrap">{capability.description}</p>
            ) : (
              <p className="text-sm text-muted-foreground italic">No description</p>
            )}
          </div>
          <Button
            variant="ghost"
            size="icon"
            className="h-7 w-7 opacity-40 group-hover:opacity-100 focus-visible:opacity-100 transition-opacity shrink-0"
            onClick={() => setEditing(true)}
            aria-label="Edit description"
          >
            <PencilSimple size={14} />
          </Button>
        </div>
      )}
    </Field>
  );
}

function usePatchCapability(id: string, domainId: string, onSuccess: () => void) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CapabilityUpdateRequest) => updateCapability(id, body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['capabilities', domainId] });
      onSuccess();
    },
  });
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
        {label}
      </div>
      <div className="mt-1">{children}</div>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const toneClass =
    status === 'ACTIVE' ? 'bg-success text-success-foreground'
      : status === 'DEPRECATED' ? 'bg-destructive text-destructive-foreground'
        : 'bg-muted text-muted-foreground';
  return (
    <span
      className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ${toneClass}`}
    >
      {status}
    </span>
  );
}

function formatTimestamp(iso: string): string {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}
