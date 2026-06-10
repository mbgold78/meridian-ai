package com.meridian.substrate.common.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the LLM integration. Shared by app + ingestion modules.
 *
 * <p>Model id and cost rates are deliberately NOT defaulted in code — they are
 * externalized to {@code application.yml} ({@code substrate.llm.anthropic.model},
 * {@code substrate.llm.cost.*}). Missing keys never break boot: an absent model
 * fails loudly at call time, absent cost rates read as 0.0.
 */
@Component
@ConfigurationProperties(prefix = "substrate.llm")
public class LlmProperties {

    private String provider = "anthropic";
    private Anthropic anthropic = new Anthropic();
    private Tools tools = new Tools();
    private Cost cost = new Cost();
    private Safety safety = new Safety();

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public Anthropic getAnthropic() { return anthropic; }
    public void setAnthropic(Anthropic anthropic) { this.anthropic = anthropic; }
    public Tools getTools() { return tools; }
    public void setTools(Tools tools) { this.tools = tools; }
    public Cost getCost() { return cost; }
    public void setCost(Cost cost) { this.cost = cost; }
    public Safety getSafety() { return safety; }
    public void setSafety(Safety safety) { this.safety = safety; }

    public static class Anthropic {
        /** API key. Absent => client disabled (degraded mode), never a boot failure. */
        private String apiKey;
        /** Model id — externalized, no hardcoded default (set substrate.llm.anthropic.model). */
        private String model;
        private int maxTokens = 4096;
        private long timeoutMs = 120000;

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public static class Tools {
        private int maxCallsPerRequest = 5;
        private long dispatchTimeoutMs = 10000;

        public int getMaxCallsPerRequest() { return maxCallsPerRequest; }
        public void setMaxCallsPerRequest(int max) { this.maxCallsPerRequest = max; }
        public long getDispatchTimeoutMs() { return dispatchTimeoutMs; }
        public void setDispatchTimeoutMs(long timeout) { this.dispatchTimeoutMs = timeout; }
    }

    public static class Cost {
        /** USD per million tokens — externalized, no hardcoded rates (set substrate.llm.cost.*). */
        private double inputRatePerMillion;
        private double outputRatePerMillion;

        public double getInputRatePerMillion() { return inputRatePerMillion; }
        public void setInputRatePerMillion(double rate) { this.inputRatePerMillion = rate; }
        public double getOutputRatePerMillion() { return outputRatePerMillion; }
        public void setOutputRatePerMillion(double rate) { this.outputRatePerMillion = rate; }
    }

    public static class Safety {
        private String refusalFallbackMessage = "I cannot answer that question. Please rephrase or contact a Risk Architect.";

        public String getRefusalFallbackMessage() { return refusalFallbackMessage; }
        public void setRefusalFallbackMessage(String msg) { this.refusalFallbackMessage = msg; }
    }
}
