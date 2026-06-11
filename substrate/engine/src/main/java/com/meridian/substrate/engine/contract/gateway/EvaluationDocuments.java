package com.meridian.substrate.engine.contract.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The single-sourced §4.4 evaluation-document shape:
 * {@code {"payload": <payloadSummary>, "target": {"entityType":…,
 * "entityId":…}}} — so the spec's {@code payload.scope} / {@code target.*}
 * dot-paths resolve naturally. Shared by the {@code check_action} advisory
 * dry-run ({@link AdvisoryCheckService}) and the real PDP
 * ({@link ContractGateway}); §14.5 verdict parity depends on the two building
 * IDENTICAL documents, hence one builder.
 */
public final class EvaluationDocuments {

    private EvaluationDocuments() {
    }

    public static JsonNode of(ObjectMapper objectMapper, JsonNode payload,
                              String targetEntityType, String targetEntityId) {
        ObjectNode document = objectMapper.createObjectNode();
        document.set("payload", payload == null ? objectMapper.createObjectNode() : payload);
        ObjectNode target = document.putObject("target");
        if (targetEntityType != null) {
            target.put("entityType", targetEntityType);
        }
        if (targetEntityId != null) {
            target.put("entityId", targetEntityId);
        }
        return document;
    }
}
