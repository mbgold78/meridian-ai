import { useMemo, type CSSProperties } from 'react';
import { Tree, type NodeApi, type NodeRendererProps, type MoveHandler } from 'react-arborist';
import { CaretRight, CaretDown, Circle, FileText } from '@phosphor-icons/react';
import { cn } from '@/lib/utils';
import type { Capability } from '@/types/capability';
import { buildCapabilityTree, type CapabilityTreeNode } from './buildTree';
import { useCapabilityDesignerStore } from './store';

interface CapabilityTreeProps {
  capabilities: Capability[];
  /** Tree viewport height; parent layout typically passes window/panel height. */
  height: number;
  /** Tree viewport width; matches the fixed left-pane width in the page layout. */
  width: number;
  /**
   * Called when a capability is dropped under a new parent.
   * {@code newParentId} is null when the row is moved to the top level.
   */
  onReparent?: (capabilityId: string, newParentId: string | null) => void;
  /**
   * Called when a capability is reordered within its current sibling
   * group. {@code newSequence} is the 0-based target index; the backend
   * clamps out-of-range values and rewrites the whole sibling group as
   * a contiguous cascade.
   */
  onReorder?: (capabilityId: string, newSequence: number) => void;
}

/**
 * Hierarchical tree of capabilities rendered via react-arborist. Selection
 * and expand-state live in the Zustand store; server data (the flat
 * capability list) flows in as a prop fetched at page level.
 *
 * <p>Keyboard navigation is native to react-arborist: arrow up/down moves
 * focus (also updates selection because the library ties them together
 * when {@code selectionFollowsFocus} is true by default), arrow right
 * expands, arrow left collapses, Enter activates.
 *
 * <p><b>Drag semantics:</b>
 * <ul>
 *   <li>Drag to a new parent → {@code onReparent}, which calls
 *       {@code PATCH /api/v1/capabilities/:id/parent}.</li>
 *   <li>Drag within the same parent → {@code onReorder}, which calls
 *       {@code PATCH /api/v1/capabilities/:id/sequence}. The backend
 *       cascades the sibling-group sequence rewrite.</li>
 * </ul>
 * react-arborist supplies both {@code parentId} and {@code index} on the
 * {@code onMove} payload; we route the drop to the correct endpoint based
 * on whether the parent changed.
 */
export function CapabilityTree({
  capabilities,
  height,
  width,
  onReparent,
  onReorder,
}: CapabilityTreeProps) {
  const selectedId = useCapabilityDesignerStore((s) => s.selectedCapabilityId);
  const select = useCapabilityDesignerStore((s) => s.select);

  const data = useMemo(() => buildCapabilityTree(capabilities), [capabilities]);

  const handleMove: MoveHandler<CapabilityTreeNode> = ({ dragIds, parentId, index }) => {
    for (const id of dragIds) {
      const current = capabilities.find((c) => c.id === id);
      if (!current) continue;
      const nextParent = parentId ?? null;

      if (nextParent !== current.parentCapabilityId) {
        // Parent changed → reparent. Position within the new parent is
        // handled server-side by append-to-tail on the reparent path;
        // the user can drag again to tune the exact slot.
        onReparent?.(id, nextParent);
      } else if (onReorder) {
        // Same parent → within-group reorder. react-arborist's `index`
        // is the target slot in the post-move sibling list; translate
        // to the backend's 0-based sequence by compensating for the
        // moved row's own old position when it sat earlier in the list
        // (react-arborist passes the index as if the row were still in
        // place during the drag, so a row moving from position 4 to 0
        // arrives with index=0 and a row moving from position 0 to 4
        // arrives with index=5).
        const siblings = capabilities
          .filter((c) => c.parentCapabilityId === current.parentCapabilityId)
          .sort((a, b) => a.sequenceOrder - b.sequenceOrder);
        const oldIndex = siblings.findIndex((s) => s.id === id);
        const target =
          index > oldIndex ? Math.max(0, index - 1) : index;
        if (target !== oldIndex) {
          onReorder(id, target);
        }
      }
    }
  };

  return (
    <Tree<CapabilityTreeNode>
      data={data}
      width={width}
      height={height}
      rowHeight={32}
      indent={20}
      openByDefault={true}
      selection={selectedId ?? undefined}
      onMove={handleMove}
      onSelect={(nodes) => {
        // react-arborist fires onSelect with [] before the first real
        // selection in some cases; guard and only fire our setter when
        // a real node is selected.
        if (nodes.length === 0) return;
        const head = nodes[0];
        if (head.data.id !== selectedId) select(head.data.id);
      }}
    >
      {CapabilityTreeRow}
    </Tree>
  );
}

const INDENT_PX = 20;

function CapabilityTreeRow({
  node,
  style,
  dragHandle,
}: NodeRendererProps<CapabilityTreeNode>) {
  // react-arborist's virtualizer sets top/height/position on `style`; the
  // left-indent for tree depth is NOT automatic in v3, so we apply it here
  // using node.level (root = 0).
  const rowStyle: CSSProperties = {
    ...(style as CSSProperties),
    paddingLeft: `${node.level * INDENT_PX + 8}px`,
  };
  return (
    <div
      ref={dragHandle}
      style={rowStyle}
      className={cn(
        'flex items-center gap-1 pr-2 cursor-pointer select-none text-sm',
        'hover:bg-accent/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        node.isSelected && 'bg-accent text-accent-foreground',
      )}
      onClick={() => {
        if (node.isInternal) node.toggle();
        node.select();
      }}
      role="treeitem"
      aria-selected={node.isSelected}
      aria-level={node.level + 1}
      aria-expanded={node.isInternal ? node.isOpen : undefined}
    >
      <DisclosureIcon node={node} />
      <NodeGlyph node={node} />
      <span className="truncate" title={node.data.name}>
        {node.data.name}
      </span>
    </div>
  );
}

function DisclosureIcon({ node }: { node: NodeApi<CapabilityTreeNode> }) {
  if (node.isLeaf) {
    // Visible spacer so leaf rows align with internal rows.
    return <span className="inline-block w-4" aria-hidden />;
  }
  const Icon = node.isOpen ? CaretDown : CaretRight;
  return (
    <Icon
      size={16}
      className="text-muted-foreground shrink-0"
      aria-hidden
    />
  );
}

function NodeGlyph({ node }: { node: NodeApi<CapabilityTreeNode> }) {
  // Leaf capabilities render a document glyph; non-leaf render a disc.
  // Both are token-tinted via text-muted-foreground.
  const Icon = node.isLeaf ? FileText : Circle;
  return (
    <Icon
      size={14}
      className={cn(
        'shrink-0 text-muted-foreground',
        node.isSelected && 'text-accent-foreground',
      )}
      aria-hidden
    />
  );
}
