package com.meridian.substrate.engine.ai.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine-owned tool execution configuration ({@code substrate.engine.tools.*}).
 *
 * <p><b>Persona→tool access (port-map §2.3 fix):</b> homepoc hardcoded the
 * persona allowlist in a static map inside {@code ToolRegistry}. It is now
 * configuration-driven via {@code substrate.engine.tools.persona-access.<level>}
 * with {@code allow} / {@code deny} lists ({@code "*"} = all tools). When no
 * configuration is provided, code defaults replicate homepoc behavior exactly,
 * so missing keys never change behavior or break boot:
 *
 * <ul>
 *   <li>{@code architect}, {@code auditor}, {@code L3_ANALYST} — all tools</li>
 *   <li>{@code executive} — count_by, find_findings, get_decision_log</li>
 *   <li>{@code sme} — find_findings, get_evidence_for_finding, find_canonical_entities</li>
 *   <li>{@code data_owner} — get_system_profile</li>
 *   <li>{@code tech_executive} — all tools except get_evidence_for_finding</li>
 *   <li>unknown levels — all tools (homepoc parity)</li>
 * </ul>
 *
 * <p><b>Dispatch pool (port-map §2.3 fix):</b> homepoc used an unbounded cached
 * thread pool for tool dispatch; {@code dispatchPoolSize} bounds it (default 8).
 */
@Component
@ConfigurationProperties(prefix = "substrate.engine.tools")
public class ToolExecutionProperties {

    /** Wildcard meaning "every registered tool". */
    public static final String ALL_TOOLS = "*";

    /** Bounded size of the shared tool-dispatch executor. */
    private int dispatchPoolSize = 8;

    /** Persona level → access rule. Empty = use built-in homepoc-parity defaults. */
    private Map<String, PersonaToolAccess> personaAccess = new LinkedHashMap<>();

    private static final Map<String, PersonaToolAccess> BUILT_IN_DEFAULTS = buildDefaults();

    private static Map<String, PersonaToolAccess> buildDefaults() {
        Map<String, PersonaToolAccess> defaults = new LinkedHashMap<>();
        defaults.put("architect", PersonaToolAccess.allowAll());
        defaults.put("auditor", PersonaToolAccess.allowAll());
        defaults.put("L3_ANALYST", PersonaToolAccess.allowAll());
        defaults.put("executive", PersonaToolAccess.allowOnly(
                "count_by", "find_findings", "get_decision_log"));
        defaults.put("sme", PersonaToolAccess.allowOnly(
                "find_findings", "get_evidence_for_finding", "find_canonical_entities"));
        defaults.put("data_owner", PersonaToolAccess.allowOnly("get_system_profile"));
        defaults.put("tech_executive", PersonaToolAccess.allowAllExcept("get_evidence_for_finding"));
        return defaults;
    }

    public int getDispatchPoolSize() {
        return dispatchPoolSize;
    }

    public void setDispatchPoolSize(int dispatchPoolSize) {
        this.dispatchPoolSize = dispatchPoolSize;
    }

    public Map<String, PersonaToolAccess> getPersonaAccess() {
        return personaAccess;
    }

    public void setPersonaAccess(Map<String, PersonaToolAccess> personaAccess) {
        this.personaAccess = personaAccess;
    }

    /**
     * Whether the given persona level may invoke the given tool. Configured
     * rules win per level; levels with no configured rule fall back to the
     * built-in defaults; levels unknown to both are allowed everything
     * (homepoc parity for the {@code null}-entry semantics).
     */
    public boolean isAllowed(String personaLevel, String toolName) {
        PersonaToolAccess rule = personaAccess.get(personaLevel);
        if (rule == null) {
            rule = BUILT_IN_DEFAULTS.get(personaLevel);
        }
        if (rule == null) {
            return true;
        }
        return rule.permits(toolName);
    }

    /**
     * A persona's tool-access rule: {@code deny} is checked first, then
     * {@code allow} ({@code "*"} or an explicit tool name).
     */
    public static class PersonaToolAccess {

        private List<String> allow = new ArrayList<>();
        private List<String> deny = new ArrayList<>();

        public static PersonaToolAccess allowAll() {
            PersonaToolAccess access = new PersonaToolAccess();
            access.allow.add(ALL_TOOLS);
            return access;
        }

        public static PersonaToolAccess allowOnly(String... toolNames) {
            PersonaToolAccess access = new PersonaToolAccess();
            access.allow.addAll(List.of(toolNames));
            return access;
        }

        public static PersonaToolAccess allowAllExcept(String... toolNames) {
            PersonaToolAccess access = allowAll();
            access.deny.addAll(List.of(toolNames));
            return access;
        }

        public List<String> getAllow() {
            return allow;
        }

        public void setAllow(List<String> allow) {
            this.allow = allow != null ? allow : new ArrayList<>();
        }

        public List<String> getDeny() {
            return deny;
        }

        public void setDeny(List<String> deny) {
            this.deny = deny != null ? deny : new ArrayList<>();
        }

        public boolean permits(String toolName) {
            if (deny.contains(toolName)) {
                return false;
            }
            return allow.contains(ALL_TOOLS) || allow.contains(toolName);
        }
    }
}
