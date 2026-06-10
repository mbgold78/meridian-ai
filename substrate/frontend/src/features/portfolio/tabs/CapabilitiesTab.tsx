import { useMemo, useState } from 'react';
import { CaretDown, CaretRight, FileText } from '@phosphor-icons/react';
import { buildCapabilityChildren } from '@/fixtures/beacon';
import type { BeaconCapability, BeaconSubsidiary } from '@/fixtures/beacon';
import { cn } from '@/lib/utils';

/**
 * Capabilities tab — read-only mock of the Capability Designer's
 * two-pane tree-and-detail layout, scoped to one subsidiary's
 * capability model.
 *
 * Design decision (Stage 4 spec, Option B): the live designer at
 * `/designer/capabilities` is built against the real domain model;
 * embedding it inside the fixture-only Beacon surfaces would require an
 * adapter layer that their value (showing the substrate-thesis through
 * polished views) doesn't justify. A read-only mock is the simpler
 * path. The live designer remains accessible outside these surfaces.
 *
 * The visual structure intentionally mirrors the live designer
 * (32-row tree on the left, scrolling detail card on the right) so a
 * viewer who later opens the standard product recognizes the surface.
 */
export function CapabilitiesTab({ sub }: { sub: BeaconSubsidiary }) {
  const childrenMap = useMemo(() => buildCapabilityChildren(sub), [sub]);
  const rootCapabilities = childrenMap.get(null) ?? [];
  const [selectedId, setSelectedId] = useState<string | null>(
    rootCapabilities[0]?.id ?? null,
  );
  const selected = sub.capabilities.find((c) => c.id === selectedId) ?? null;

  return (
    <section aria-label="Capabilities">
      <p className="mb-6 text-[13px] leading-[18px] text-foreground-muted">
        This is a read-only view of {sub.name}'s capability model. The full
        editor is available outside the demo.
      </p>
      <div className="grid grid-cols-1 gap-6 md:grid-cols-[320px_1fr]">
        <CapabilityTreePane
          rootCapabilities={rootCapabilities}
          childrenMap={childrenMap}
          selectedId={selectedId}
          onSelect={setSelectedId}
        />
        <CapabilityDetailPane sub={sub} selected={selected} childrenMap={childrenMap} />
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ */
/*  Left pane — tree                                                   */
/* ------------------------------------------------------------------ */

interface CapabilityTreePaneProps {
  rootCapabilities: BeaconCapability[];
  childrenMap: Map<string | null, BeaconCapability[]>;
  selectedId: string | null;
  onSelect: (id: string) => void;
}

function CapabilityTreePane({
  rootCapabilities,
  childrenMap,
  selectedId,
  onSelect,
}: CapabilityTreePaneProps) {
  return (
    <nav
      aria-label="Capability tree"
      className="rounded-md border border-border bg-surface"
    >
      <header className="flex items-baseline justify-between border-b border-border px-3 py-2">
        <span className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
          Capability Model
        </span>
        <span
          className="text-[11px] text-foreground-subtle"
          style={{ fontFeatureSettings: '"tnum"' }}
        >
          {childrenMap.get(null)?.length ?? 0} root ·{' '}
          {Array.from(childrenMap.values()).reduce((s, b) => s + b.length, 0)} total
        </span>
      </header>
      <ul role="tree" className="py-1">
        {rootCapabilities.map((cap) => (
          <CapabilityTreeNode
            key={cap.id}
            cap={cap}
            childrenMap={childrenMap}
            selectedId={selectedId}
            onSelect={onSelect}
            level={0}
          />
        ))}
      </ul>
    </nav>
  );
}

interface CapabilityTreeNodeProps {
  cap: BeaconCapability;
  childrenMap: Map<string | null, BeaconCapability[]>;
  selectedId: string | null;
  onSelect: (id: string) => void;
  level: number;
}

function CapabilityTreeNode({
  cap,
  childrenMap,
  selectedId,
  onSelect,
  level,
}: CapabilityTreeNodeProps) {
  const children = childrenMap.get(cap.id) ?? [];
  const hasChildren = children.length > 0;
  const [open, setOpen] = useState(true);
  const isSelected = cap.id === selectedId;

  return (
    <li role="treeitem" aria-selected={isSelected} aria-expanded={hasChildren ? open : undefined}>
      <div
        className={cn(
          'flex h-8 cursor-pointer select-none items-center gap-1.5 border-l-2 pr-3 text-[13px] transition-colors duration-100 hover:bg-surface',
          isSelected
            ? 'border-l-primary bg-surface-raised text-foreground'
            : 'border-l-transparent text-foreground',
        )}
        style={{ paddingLeft: `${level * 16 + 8}px` }}
        onClick={() => onSelect(cap.id)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault();
            onSelect(cap.id);
          }
        }}
        tabIndex={0}
      >
        {hasChildren ? (
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              setOpen((v) => !v);
            }}
            aria-label={open ? 'Collapse' : 'Expand'}
            className="inline-flex h-4 w-4 items-center justify-center text-foreground-muted hover:text-foreground"
          >
            {open ? (
              <CaretDown size={12} weight="regular" aria-hidden />
            ) : (
              <CaretRight size={12} weight="regular" aria-hidden />
            )}
          </button>
        ) : (
          <span className="inline-block h-4 w-4" aria-hidden />
        )}
        <FileText
          size={14}
          weight="regular"
          aria-hidden
          className={cn(
            'shrink-0',
            isSelected ? 'text-foreground' : 'text-foreground-muted',
          )}
        />
        <span className="truncate" title={cap.name}>
          {cap.name}
        </span>
      </div>
      {hasChildren && open && (
        <ul role="group">
          {children.map((child) => (
            <CapabilityTreeNode
              key={child.id}
              cap={child}
              childrenMap={childrenMap}
              selectedId={selectedId}
              onSelect={onSelect}
              level={level + 1}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

/* ------------------------------------------------------------------ */
/*  Right pane — detail                                                */
/* ------------------------------------------------------------------ */

interface CapabilityDetailPaneProps {
  sub: BeaconSubsidiary;
  selected: BeaconCapability | null;
  childrenMap: Map<string | null, BeaconCapability[]>;
}

function CapabilityDetailPane({ sub, selected, childrenMap }: CapabilityDetailPaneProps) {
  if (!selected) {
    return (
      <article
        className="flex min-h-[260px] items-center justify-center rounded-md border border-border bg-surface p-8 text-center"
        aria-label="No capability selected"
      >
        <p className="max-w-xs text-[14px] leading-[20px] text-foreground-muted">
          Select a capability to view its details.
        </p>
      </article>
    );
  }

  const counts = collectRelatedCounts(selected, sub, childrenMap);
  const directChildren = childrenMap.get(selected.id) ?? [];

  return (
    <article
      className="rounded-md border border-border bg-surface-raised p-6"
      aria-label={`${selected.name} detail`}
    >
      <header>
        <p className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
          Capability · Level {selected.level}
        </p>
        <h3 className="mt-1 text-[22px] font-semibold leading-[28px] -tracking-[0.005em] text-foreground">
          {selected.name}
        </h3>
        {selected.description ? (
          <p className="mt-2 text-[14px] leading-[20px] text-foreground">
            {selected.description}
          </p>
        ) : (
          <p className="mt-2 text-[14px] leading-[20px] text-foreground-muted">
            No description authored. The standard product surfaces an inline
            editor here for the assigned capability owner.
          </p>
        )}
      </header>

      <dl className="mt-6 grid grid-cols-3 gap-4">
        <DetailStat label="Processes" value={counts.processes} />
        <DetailStat label="Systems" value={counts.systems} />
        <DetailStat label="Open findings" value={counts.findings} />
      </dl>

      {directChildren.length > 0 && (
        <section className="mt-6 border-t border-border pt-6">
          <h4 className="text-[13px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
            Direct children
          </h4>
          <ul className="mt-3 flex flex-col gap-2">
            {directChildren.map((child) => (
              <li
                key={child.id}
                className="rounded-md border border-border bg-surface px-3 py-2 text-[14px] text-foreground"
              >
                {child.name}
              </li>
            ))}
          </ul>
        </section>
      )}
    </article>
  );
}

interface DetailStatProps {
  label: string;
  value: number;
}

function DetailStat({ label, value }: DetailStatProps) {
  return (
    <div className="rounded-md border border-border bg-surface p-4">
      <div className="text-[11px] font-medium uppercase tracking-[0.05em] text-foreground-muted">
        {label}
      </div>
      <div
        className="mt-2 text-[18px] font-semibold leading-[24px] text-foreground"
        style={{ fontFeatureSettings: '"tnum"' }}
      >
        {value}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

function collectDescendants(
  rootId: string,
  childrenMap: Map<string | null, BeaconCapability[]>,
): Set<string> {
  const out = new Set<string>();
  const stack = [rootId];
  while (stack.length > 0) {
    const id = stack.pop()!;
    for (const child of childrenMap.get(id) ?? []) {
      if (!out.has(child.id)) {
        out.add(child.id);
        stack.push(child.id);
      }
    }
  }
  return out;
}

function collectRelatedCounts(
  cap: BeaconCapability,
  sub: BeaconSubsidiary,
  childrenMap: Map<string | null, BeaconCapability[]>,
): { processes: number; systems: number; findings: number } {
  const descendants = collectDescendants(cap.id, childrenMap);
  const ids = new Set([cap.id, ...descendants]);

  const procs = sub.processes.filter((p) => ids.has(p.capabilityId));
  const procIds = new Set(procs.map((p) => p.id));
  const sysIds = new Set(procs.flatMap((p) => p.systemIds));
  const findings = sub.findings.filter(
    (f) =>
      (f.capabilityId && ids.has(f.capabilityId)) ||
      (f.processId && procIds.has(f.processId)),
  );

  return { processes: procs.length, systems: sysIds.size, findings: findings.length };
}
