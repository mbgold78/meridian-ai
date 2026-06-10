import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { createCapability } from '@/api/endpoints';
import type { Capability } from '@/types/capability';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useTerminology } from '@/app/theme';
import { useCapabilityDesignerStore } from './store';

interface CreateCapabilityDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  domainId: string;
  capabilities: Capability[];
  /** Optional pre-selected parent (e.g. currently-selected row). */
  defaultParentId?: string | null;
}

/**
 * Create-capability form in a shadcn Dialog. Required: displayName. The
 * internal {@code name} is auto-derived from displayName (lowercase,
 * whitespace → dash) and editable. Parent is an optional dropdown of
 * existing capabilities. Description is optional.
 *
 * <p>On submit: POST /api/v1/capabilities, invalidate the capabilities
 * query so the tree re-renders, and set the Zustand store's selection
 * to the newly-created row so the detail pane shows it immediately.
 *
 * <p>Port note: homepoc carried {@code text-slate-900} overrides on the
 * dialog labels to fight a legacy body color; substrate's DialogContent
 * resets {@code text-foreground} itself, so the labels are token-clean.
 */
export function CreateCapabilityDialog({
  open,
  onOpenChange,
  domainId,
  capabilities,
  defaultParentId = null,
}: CreateCapabilityDialogProps) {
  const { t } = useTerminology();
  const qc = useQueryClient();
  const select = useCapabilityDesignerStore((s) => s.select);

  const [displayName, setDisplayName] = useState('');
  const [name, setName] = useState('');
  const [nameDirty, setNameDirty] = useState(false);
  const [description, setDescription] = useState('');
  const [parentId, setParentId] = useState<string | null>(defaultParentId);
  const [error, setError] = useState<string | null>(null);

  // Reset state each time the dialog opens.
  useEffect(() => {
    if (open) {
      setDisplayName('');
      setName('');
      setNameDirty(false);
      setDescription('');
      setParentId(defaultParentId);
      setError(null);
    }
  }, [open, defaultParentId]);

  // Auto-derive internal name from displayName while the name field is
  // pristine. Once the user edits name manually (nameDirty), stop tracking.
  const derivedName = useMemo(() => toInternalName(displayName), [displayName]);
  useEffect(() => {
    if (!nameDirty) setName(derivedName);
  }, [derivedName, nameDirty]);

  const create = useMutation({
    mutationFn: createCapability,
    onSuccess: (resp) => {
      qc.invalidateQueries({ queryKey: ['capabilities', domainId] });
      select(resp.data.id);
      onOpenChange(false);
    },
    onError: (err: unknown) => {
      setError(err instanceof Error ? err.message : 'Failed to create');
    },
  });

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    const trimmedDisplay = displayName.trim();
    const trimmedName = name.trim();
    if (trimmedDisplay.length === 0) {
      setError(`${t('capability', 'singular')} name is required`);
      return;
    }
    if (trimmedName.length === 0) {
      setError('Internal identifier is required');
      return;
    }
    const parent = capabilities.find((c) => c.id === parentId) ?? null;
    const level = parent ? parent.level + 1 : 0;
    create.mutate({
      domainId,
      parentCapabilityId: parentId,
      name: trimmedName,
      displayName: trimmedDisplay,
      description: description.trim() || null,
      level,
      status: 'DRAFT',
      aiGenerated: false,
    });
  };

  const label = t('capability', 'singular');

  return (
    // Form dialogs must NOT dismiss on outside click — that would discard
    // unsaved user input with no confirmation. Explicit dismiss paths stay
    // (Cancel button, X close, Escape). Display-only dialogs like
    // AiDraftsDialog keep the Radix default since they hold no user data.
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent onInteractOutside={(e) => e.preventDefault()}>
        <form onSubmit={handleSubmit}>
          <DialogHeader>
            <DialogTitle>{`Create ${label}`}</DialogTitle>
            <DialogDescription>
              {`New ${label.toLowerCase()} opens in DRAFT status. You can edit all fields afterward.`}
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4 py-4">
            <div className="space-y-1.5">
              <label htmlFor="create-cap-display" className="text-sm font-medium">
                Name
              </label>
              <Input
                id="create-cap-display"
                autoFocus
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                placeholder={`e.g. Customer Onboarding`}
              />
              <p className="text-xs text-muted-foreground">
                Customer-facing name. Shown in the tree and all reports.
              </p>
            </div>

            <div className="space-y-1.5">
              <label htmlFor="create-cap-name" className="text-sm font-medium">
                Internal identifier
              </label>
              <Input
                id="create-cap-name"
                value={name}
                onChange={(e) => {
                  setName(e.target.value);
                  setNameDirty(true);
                }}
                placeholder="auto-derived from name"
                className="font-mono text-xs"
              />
              <p className="text-xs text-muted-foreground">
                Stable identifier used in code and logs. Defaults from the name;
                override if you need a specific slug.
              </p>
            </div>

            <div className="space-y-1.5">
              <label htmlFor="create-cap-parent" className="text-sm font-medium">
                {`Parent ${label.toLowerCase()}`}
              </label>
              <select
                id="create-cap-parent"
                value={parentId ?? ''}
                onChange={(e) => setParentId(e.target.value || null)}
                className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <option value="">{`(none — top-level ${label.toLowerCase()})`}</option>
                {capabilities.map((c) => (
                  <option key={c.id} value={c.id}>
                    {'—'.repeat(c.level)} {c.displayName}
                  </option>
                ))}
              </select>
            </div>

            <div className="space-y-1.5">
              <label htmlFor="create-cap-desc" className="text-sm font-medium">
                Description
              </label>
              <textarea
                id="create-cap-desc"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                rows={3}
                className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
            </div>

            {error && (
              <p className="text-sm text-destructive" role="alert">
                {error}
              </p>
            )}
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="ghost"
              onClick={() => onOpenChange(false)}
              disabled={create.isPending}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? 'Creating…' : `Create ${label}`}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function toInternalName(displayName: string): string {
  return displayName
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}
