import type { Capability } from '@/types/capability';

/**
 * Tree-node shape consumed by {@link CapabilityTree}'s react-arborist
 * instance. Holds the full Capability so node renderers have access to
 * status, description, aiGenerated, etc. without re-querying.
 *
 * react-arborist treats a node as a leaf when {@code children} is
 * undefined; an internal node with no children gets an expand chevron
 * that does nothing. We therefore omit the key for leaves rather than
 * passing an empty array.
 */
export interface CapabilityTreeNode {
  id: string;
  name: string;
  capability: Capability;
  children?: CapabilityTreeNode[];
}

/**
 * Build a tree of {@link CapabilityTreeNode} from the flat Capability list
 * returned by GET /api/v1/capabilities. Parent/child relationships come
 * from {@code parentCapabilityId}; sibling order comes from
 * {@code sequenceOrder} (0-based, rewritten as a contiguous cascade on
 * reorder by the backend).
 *
 * Capabilities whose parentCapabilityId refers to an id NOT in the input
 * list are treated as roots (defensive — prevents a partial/filtered
 * result set from silently hiding a subtree).
 */
export function buildCapabilityTree(flat: Capability[]): CapabilityTreeNode[] {
  const ids = new Set(flat.map((c) => c.id));
  const childrenByParent = new Map<string | null, Capability[]>();

  for (const c of flat) {
    const key: string | null = c.parentCapabilityId && ids.has(c.parentCapabilityId)
      ? c.parentCapabilityId
      : null;
    const bucket = childrenByParent.get(key);
    if (bucket) bucket.push(c);
    else childrenByParent.set(key, [c]);
  }

  // Sort each sibling bucket by sequenceOrder. Falls back to createdAt on
  // duplicates so render order stays deterministic even if the server
  // leaves a gap or dupe (migrations, manual SQL fixups, etc.).
  for (const bucket of childrenByParent.values()) {
    bucket.sort((a, b) => {
      const d = a.sequenceOrder - b.sequenceOrder;
      if (d !== 0) return d;
      return a.createdAt.localeCompare(b.createdAt);
    });
  }

  const build = (parentId: string | null): CapabilityTreeNode[] =>
    (childrenByParent.get(parentId) ?? []).map((c) => {
      const kids = build(c.id);
      const node: CapabilityTreeNode = {
        id: c.id,
        name: c.displayName,
        capability: c,
      };
      if (kids.length > 0) node.children = kids;
      return node;
    });

  return build(null);
}
