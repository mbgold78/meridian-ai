import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CaretLeft, Plus, Sparkle } from '@phosphor-icons/react';
import {
  getCapabilities,
  reparentCapability,
  updateCapabilitySequence,
} from '@/api/endpoints';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Separator } from '@/components/ui/separator';
import { Button } from '@/components/ui/button';
import { useTerminology } from '@/app/theme';
import { useWorkspace } from '@/shared/hooks/useWorkspace';
import { CapabilityTree } from './CapabilityTree';
import { CapabilityDetailPane } from './CapabilityDetailPane';
import { CreateCapabilityDialog } from './CreateCapabilityDialog';
import { AiDraftsDialog } from './AiDraftsDialog';
import { useCapabilityDesignerStore } from './store';

const TREE_WIDTH_PX = 320;

/**
 * Capability Designer page.
 *
 * Two-pane layout:
 *   - Left (fixed {@code TREE_WIDTH_PX}): CapabilityTree (react-arborist).
 *   - Right (flex): CapabilityDetailPane — inline edit for displayName
 *     and description, read-only for everything else.
 *
 * Header actions:
 *   - Create — opens {@link CreateCapabilityDialog}.
 *   - Draft from Universe — opens {@link AiDraftsDialog}.
 *
 * Drag semantics:
 *   - Drag-to-new-parent persists via PATCH /capabilities/:id/parent.
 *   - Drag within the same parent persists via
 *     PATCH /capabilities/:id/sequence; the backend cascades the
 *     sibling-group rewrite.
 *
 * Domain scope comes from {@link useWorkspace} — the explicit workspace
 * resolution policy that replaced homepoc's first-domain grab
 * (port map §2.5 step 6 / S14).
 *
 * Terminology flows through {@link useTerminology} — a workspace that
 * renames Capability → Service sees matching labels in every header
 * button and empty state.
 */
export function CapabilityDesigner() {
  const { t } = useTerminology();
  const qc = useQueryClient();
  const selectedId = useCapabilityDesignerStore((s) => s.selectedCapabilityId);

  const workspace = useWorkspace();
  const domainId = workspace.domainId ?? undefined;

  const capsQuery = useQuery({
    queryKey: ['capabilities', domainId],
    queryFn: () => getCapabilities(domainId as string),
    enabled: !!domainId,
  });

  const [createOpen, setCreateOpen] = useState(false);
  const [aiOpen, setAiOpen] = useState(false);

  const treePaneRef = useRef<HTMLDivElement | null>(null);
  const [treeHeight, setTreeHeight] = useState<number>(480);

  useEffect(() => {
    if (!treePaneRef.current) return;
    const el = treePaneRef.current;
    const measure = () => setTreeHeight(el.clientHeight || 480);
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const reparent = useMutation({
    mutationFn: ({ id, newParentId }: { id: string; newParentId: string | null }) =>
      reparentCapability(id, newParentId),
    onSuccess: () => {
      if (domainId) qc.invalidateQueries({ queryKey: ['capabilities', domainId] });
    },
  });

  const reorder = useMutation({
    mutationFn: ({ id, newSequence }: { id: string; newSequence: number }) =>
      updateCapabilitySequence(id, newSequence),
    onSuccess: () => {
      if (domainId) qc.invalidateQueries({ queryKey: ['capabilities', domainId] });
    },
  });

  const handleReparent = useCallback(
    (id: string, newParentId: string | null) => {
      reparent.mutate({ id, newParentId });
    },
    [reparent],
  );

  const handleReorder = useCallback(
    (id: string, newSequence: number) => {
      reorder.mutate({ id, newSequence });
    },
    [reorder],
  );

  const label = t('capability', 'singular');

  return (
    <div className="flex h-full flex-col bg-background text-foreground">
      <header className="flex items-center gap-4 px-6 py-4 border-b border-border">
        <Link
          to="/"
          className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-sm font-medium text-muted-foreground hover:bg-accent hover:text-accent-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          aria-label="Back to canvas"
        >
          <CaretLeft size={16} aria-hidden />
          <span>Canvas</span>
        </Link>
        <Separator orientation="vertical" className="h-6" />
        <h1 className="text-lg font-semibold tracking-tight">
          {`${label} Designer`}
        </h1>
        <div className="ml-auto flex items-center gap-2">
          <span className="text-xs text-muted-foreground mr-2">
            {capsQuery.data
              ? `${capsQuery.data.data.length} ${t('capability', capsQuery.data.data.length === 1 ? 'singular' : 'plural').toLowerCase()}`
              : ''}
          </span>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setAiOpen(true)}
            disabled={!domainId}
          >
            <Sparkle size={16} aria-hidden />
            Draft from Universe
          </Button>
          <Button
            size="sm"
            onClick={() => setCreateOpen(true)}
            disabled={!domainId}
          >
            <Plus size={16} aria-hidden />
            {`Create ${label}`}
          </Button>
        </div>
      </header>

      <div className="flex flex-1 min-h-0">
        <aside
          ref={treePaneRef}
          className="border-r border-border bg-card"
          style={{ width: TREE_WIDTH_PX }}
        >
          {(workspace.status === 'error' || capsQuery.isError) && (
            <div className="p-4 text-sm text-destructive">
              Failed to load {t('capability', 'plural').toLowerCase()}.
            </div>
          )}
          {(workspace.status === 'loading' || capsQuery.isLoading) && (
            <div className="p-4 text-sm text-muted-foreground">Loading…</div>
          )}
          {workspace.status === 'empty' && (
            <div className="p-4 text-sm text-muted-foreground">
              No workspace yet — ingest data to create your first domain.
            </div>
          )}
          {capsQuery.data && (
            <CapabilityTree
              capabilities={capsQuery.data.data}
              width={TREE_WIDTH_PX}
              height={treeHeight}
              onReparent={handleReparent}
              onReorder={handleReorder}
            />
          )}
        </aside>

        <Separator orientation="vertical" className="shrink-0" />

        <section className="flex-1 min-w-0">
          <ScrollArea className="h-full">
            {domainId ? (
              <CapabilityDetailPane domainId={domainId} />
            ) : (
              <div className="p-8 text-sm text-muted-foreground">
                Waiting for workspace…
              </div>
            )}
          </ScrollArea>
        </section>
      </div>

      {domainId && capsQuery.data && (
        <CreateCapabilityDialog
          open={createOpen}
          onOpenChange={setCreateOpen}
          domainId={domainId}
          capabilities={capsQuery.data.data}
          defaultParentId={selectedId}
        />
      )}

      {domainId && (
        <AiDraftsDialog
          open={aiOpen}
          onOpenChange={setAiOpen}
          domainId={domainId}
        />
      )}
    </div>
  );
}
