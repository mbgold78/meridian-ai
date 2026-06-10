package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Records a single chat interaction between a user and an AI assistant, including
 * the persona context, token usage, latency metrics, and cost estimates.
 */
@Entity
@Table(name = "chat_interactions")
public class ChatInteraction extends BaseEntity {

    @Column(name = "domain_id")
    private UUID domainId;

    @Column(name = "persona_id")
    private String personaId;

    @Column(name = "user_message", columnDefinition = "TEXT")
    private String userMessage;

    @Column(name = "assistant_message", columnDefinition = "TEXT")
    private String assistantMessage;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "tool_calls", columnDefinition = "TEXT")
    private JsonNode toolCalls;

    @Column(name = "model")
    private String model;

    @Column(name = "prompt_template_id")
    private String promptTemplateId;

    @Column(name = "prompt_template_version")
    private String promptTemplateVersion;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "refusal_detected")
    private Boolean refusalDetected;

    @Column(name = "cost_estimate")
    private Double costEstimate;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "metadata", columnDefinition = "TEXT")
    private JsonNode metadata;

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public String getPersonaId() {
        return personaId;
    }

    public void setPersonaId(String personaId) {
        this.personaId = personaId;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public String getAssistantMessage() {
        return assistantMessage;
    }

    public void setAssistantMessage(String assistantMessage) {
        this.assistantMessage = assistantMessage;
    }

    public JsonNode getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(JsonNode toolCalls) {
        this.toolCalls = toolCalls;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPromptTemplateId() {
        return promptTemplateId;
    }

    public void setPromptTemplateId(String promptTemplateId) {
        this.promptTemplateId = promptTemplateId;
    }

    public String getPromptTemplateVersion() {
        return promptTemplateVersion;
    }

    public void setPromptTemplateVersion(String promptTemplateVersion) {
        this.promptTemplateVersion = promptTemplateVersion;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Boolean getRefusalDetected() {
        return refusalDetected;
    }

    public void setRefusalDetected(Boolean refusalDetected) {
        this.refusalDetected = refusalDetected;
    }

    public Double getCostEstimate() {
        return costEstimate;
    }

    public void setCostEstimate(Double costEstimate) {
        this.costEstimate = costEstimate;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata;
    }
}
