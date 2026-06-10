package com.meridian.substrate.app.branding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.BrandingConfig;
import com.meridian.substrate.common.domain.enums.BrandingConfigStatus;

import java.util.UUID;

/**
 * Canonical default "Meridian" {@link BrandingConfig} factory. The values here
 * mirror the token names defined on the frontend in {@code tokens.css}. The
 * frontend is the authority on which token names exist; this class supplies
 * the default *values* the {@code ThemeProvider} hydrates them with at boot
 * when no customer override has been authored yet.
 *
 * <p><b>Token naming.</b> The token set follows two layers documented in
 * {@code tokens.css} and {@code docs/design/visual-foundation.md}:
 * <ol>
 *   <li>The Meridian visual foundation set ({@code background},
 *       {@code surface}, {@code surface-raised}, {@code foreground},
 *       {@code foreground-muted}, {@code foreground-subtle}, {@code border},
 *       {@code border-strong}, {@code primary}, {@code primary-hover},
 *       {@code primary-foreground}, {@code success}/{@code success-surface},
 *       {@code warning}/{@code warning-surface},
 *       {@code destructive}/{@code destructive-surface},
 *       {@code info}/{@code info-surface}).</li>
 *   <li>The shadcn/ui canonical aliases ({@code card}, {@code card-foreground},
 *       {@code popover}, {@code popover-foreground}, {@code secondary},
 *       {@code muted}, {@code accent}, {@code input}, {@code ring}). These are
 *       kept so shadcn components drop in without a rename layer; they alias
 *       to the visual-foundation values, not a parallel scheme.</li>
 * </ol>
 *
 * <p><b>Token values.</b> Colors are HSL triplet strings without the
 * {@code hsl(...)} wrapper — e.g. {@code "0 0% 100%"} rather than
 * {@code "#ffffff"}. This matches shadcn's convention and lets Tailwind
 * opacity utilities ({@code bg-primary/50}) inject alpha through
 * {@code hsl(var(--primary) / 50%)}. Customer overrides MAY use hex strings
 * if the customer does not need opacity utilities; the frontend writes
 * whatever value it receives straight into the CSS custom property.
 *
 * <p>Light-mode and dark-mode palettes are designed independently — no
 * algorithmic darkening of light values. Both maps are alphabetized.
 *
 * <p>Terminology overrides default to the identity map — the workspace's
 * configured term equals the internal name. Customer workspaces override
 * specific keys (e.g. {@code "finding" -> "Issue"}) without touching the
 * others.
 */
public final class BrandingDefaults {

    public static final String DEFAULT_APP_DISPLAY_NAME = "Meridian";
    public static final String DEFAULT_TAGLINE          = "Capability and workflow designer for enterprise transformation";

    public static final String DEFAULT_FONT_BODY    = "'Inter Variable', Inter, system-ui, -apple-system, 'Segoe UI', sans-serif";
    public static final String DEFAULT_FONT_HEADING = "'Instrument Serif', Georgia, serif";
    public static final String DEFAULT_FONT_MONO    = "'JetBrains Mono', ui-monospace, 'SF Mono', Menlo, Consolas, monospace";

    private BrandingDefaults() {}

    public static BrandingConfig newDefaultFor(UUID domainId, ObjectMapper mapper) {
        BrandingConfig c = new BrandingConfig();
        c.setDomainId(domainId);
        c.setAppDisplayName(DEFAULT_APP_DISPLAY_NAME);
        c.setTagline(DEFAULT_TAGLINE);
        c.setColorTokensLight(defaultLightTokens(mapper));
        c.setColorTokensDark(defaultDarkTokens(mapper));
        c.setFontBody(DEFAULT_FONT_BODY);
        c.setFontHeading(DEFAULT_FONT_HEADING);
        c.setFontMono(DEFAULT_FONT_MONO);
        c.setTerminologyOverrides(defaultTerminology(mapper));
        c.setStatus(BrandingConfigStatus.ACTIVE);
        c.setCreatedBy("seed");
        return c;
    }

    public static JsonNode defaultLightTokens(ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("accent",                  "38 18% 94%");
        n.put("accent-foreground",       "24 12% 12%");
        n.put("background",              "38 24% 97%");
        n.put("border",                  "32 12% 86%");
        n.put("border-strong",           "32 12% 75%");
        n.put("card",                    "38 18% 94%");
        n.put("card-foreground",         "24 12% 12%");
        n.put("destructive",             "0 65% 50%");
        n.put("destructive-foreground",  "30 12% 98%");
        n.put("destructive-surface",     "0 50% 95%");
        n.put("foreground",              "24 12% 12%");
        n.put("foreground-muted",        "28 8% 38%");
        n.put("foreground-subtle",       "28 6% 55%");
        n.put("industry-creative",       "240 30% 55%");
        n.put("industry-education",      "335 40% 55%");
        n.put("industry-financial",      "145 30% 40%");
        n.put("industry-government",     "215 25% 45%");
        n.put("industry-healthcare",     "210 65% 50%");
        n.put("industry-instrumentation","270 30% 50%");
        n.put("industry-insurance",      "175 35% 42%");
        n.put("industry-legal",          "25 25% 35%");
        n.put("industry-logistics",      "14 55% 50%");
        n.put("info",                    "210 65% 50%");
        n.put("info-surface",            "210 50% 95%");
        n.put("input",                   "32 12% 86%");
        n.put("muted",                   "38 18% 94%");
        n.put("muted-foreground",        "28 8% 38%");
        n.put("popover",                 "0 0% 100%");
        n.put("popover-foreground",      "24 12% 12%");
        n.put("primary",                 "28 85% 55%");
        n.put("primary-foreground",      "30 12% 98%");
        n.put("primary-hover",           "28 85% 48%");
        n.put("ring",                    "28 85% 55%");
        n.put("secondary",               "38 18% 94%");
        n.put("secondary-foreground",    "24 12% 12%");
        n.put("success",                 "142 55% 42%");
        n.put("success-foreground",      "30 12% 98%");
        n.put("success-surface",         "142 35% 92%");
        n.put("surface",                 "38 18% 94%");
        n.put("surface-raised",          "0 0% 100%");
        n.put("warning",                 "38 85% 50%");
        n.put("warning-foreground",      "30 12% 98%");
        n.put("warning-surface",         "38 70% 92%");
        return n;
    }

    public static JsonNode defaultDarkTokens(ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("accent",                  "30 8% 12%");
        n.put("accent-foreground",       "38 14% 95%");
        n.put("background",              "30 8% 9%");
        n.put("border",                  "30 8% 20%");
        n.put("border-strong",           "30 8% 28%");
        n.put("card",                    "30 8% 12%");
        n.put("card-foreground",         "38 14% 95%");
        n.put("destructive",             "0 65% 50%");
        n.put("destructive-foreground",  "30 12% 98%");
        n.put("destructive-surface",     "0 50% 18%");
        n.put("foreground",              "38 14% 95%");
        n.put("foreground-muted",        "32 10% 70%");
        n.put("foreground-subtle",       "32 8% 55%");
        n.put("industry-creative",       "240 35% 70%");
        n.put("industry-education",      "335 45% 70%");
        n.put("industry-financial",      "145 35% 55%");
        n.put("industry-government",     "215 30% 65%");
        n.put("industry-healthcare",     "210 65% 60%");
        n.put("industry-instrumentation","270 35% 65%");
        n.put("industry-insurance",      "175 40% 55%");
        n.put("industry-legal",          "25 30% 55%");
        n.put("industry-logistics",      "14 55% 62%");
        n.put("info",                    "210 65% 50%");
        n.put("info-surface",            "210 50% 18%");
        n.put("input",                   "30 8% 20%");
        n.put("muted",                   "30 8% 12%");
        n.put("muted-foreground",        "32 10% 70%");
        n.put("popover",                 "30 8% 15%");
        n.put("popover-foreground",      "38 14% 95%");
        n.put("primary",                 "28 85% 55%");
        n.put("primary-foreground",      "30 12% 98%");
        n.put("primary-hover",           "28 85% 48%");
        n.put("ring",                    "28 85% 55%");
        n.put("secondary",               "30 8% 12%");
        n.put("secondary-foreground",    "38 14% 95%");
        n.put("success",                 "142 55% 42%");
        n.put("success-foreground",      "30 12% 98%");
        n.put("success-surface",         "142 35% 15%");
        n.put("surface",                 "30 8% 12%");
        n.put("surface-raised",          "30 8% 15%");
        n.put("warning",                 "38 85% 50%");
        n.put("warning-foreground",      "30 12% 98%");
        n.put("warning-surface",         "38 70% 15%");
        return n;
    }

    public static JsonNode defaultTerminology(ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        addTerm(root, "capability", "Capability", "Capabilities");
        addTerm(root, "process",    "Process",    "Processes");
        addTerm(root, "domain",     "Domain",     "Domains");
        addTerm(root, "finding",    "Finding",    "Findings");
        addTerm(root, "system",     "System",     "Systems");
        addTerm(root, "reader",     "Reader",     "Readers");
        return root;
    }

    private static void addTerm(ObjectNode root, String key, String singular, String plural) {
        ObjectNode entry = root.objectNode();
        entry.put("singular", singular);
        entry.put("plural", plural);
        root.set(key, entry);
    }
}
