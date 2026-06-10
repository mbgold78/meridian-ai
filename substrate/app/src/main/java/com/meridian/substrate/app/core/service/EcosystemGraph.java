package com.meridian.substrate.app.core.service;

import java.util.List;
import java.util.Map;

/**
 * Represents the ecosystem graph for a domain, consisting of system and process
 * nodes connected by integration and process-link edges.
 *
 * @param nodes the graph nodes (systems and processes)
 * @param edges the graph edges (integrations and process-system links)
 */
public record EcosystemGraph(
    List<GraphNode> nodes,
    List<GraphEdge> edges
) {

    /**
     * A node in the ecosystem graph.
     *
     * @param id       the unique identifier of the node
     * @param name     the display name of the node
     * @param nodeType the type of node (e.g. "system", "process")
     * @param data     additional data associated with the node
     */
    public record GraphNode(String id, String name, String nodeType, Map<String, Object> data) {}

    /**
     * An edge in the ecosystem graph.
     *
     * @param id       the unique identifier of the edge
     * @param source   the source node identifier
     * @param target   the target node identifier
     * @param edgeType the type of edge (e.g. "integration", "process_link")
     * @param data     additional data associated with the edge
     */
    public record GraphEdge(String id, String source, String target, String edgeType, Map<String, Object> data) {}
}
