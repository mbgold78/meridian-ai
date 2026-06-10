package com.meridian.substrate.app.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fingerprints a URL to identify the type of API or document it exposes.
 * Stateless — no DB writes. Used by SourceController to drive the "Add Source" flow.
 *
 * <p><b>SECURITY (port-map §2.2 risk register):</b> this service performs live
 * outbound HTTP to a caller-supplied URL — an SSRF surface. Before production
 * exposure it needs a destination allowlist (and ideally async execution with a
 * result handle). Ported as-is for Phase 1 parity with homepoc; the hardening is
 * tracked as pre-production work, not silently absorbed into this port.
 */
@Service
public class ProbeService {

    private static final Logger log = LoggerFactory.getLogger(ProbeService.class);

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    public record ProbeResult(
            String url,
            String detected,
            String title,
            double confidence,
            List<String> endpoints,
            List<String> hints,
            String error
    ) {}

    public ProbeResult probe(String rawUrl) {
        String url = rawUrl.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }

        List<String> hints = new ArrayList<>();
        List<String> endpoints = new ArrayList<>();

        // Try candidate paths in order
        String body = null;
        String finalUrl = url;

        String[] candidates = candidatePaths(url);
        for (String candidate : candidates) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(candidate))
                        .timeout(Duration.ofSeconds(8))
                        .header("Accept", "application/json, text/xml, */*")
                        .GET()
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    body = resp.body();
                    finalUrl = candidate;
                    break;
                }
            } catch (Exception e) {
                log.debug("Probe candidate {} failed: {}", candidate, e.getMessage());
            }
        }

        if (body == null) {
            return new ProbeResult(url, null, null, 0.0, endpoints, hints, "Could not reach URL — check network or CORS");
        }

        return fingerprint(finalUrl, body, hints, endpoints);
    }

    private String[] candidatePaths(String base) {
        // Strip trailing slash
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return new String[]{
                b,
                b + "/openapi.json",
                b + "/swagger.json",
                b + "/api-docs",
                b + "/$metadata",
                b + "/graphql",
                b + "/schema",
        };
    }

    private ProbeResult fingerprint(String url, String body, List<String> hints, List<String> endpoints) {
        String lower = body.toLowerCase();

        // OpenAPI / Swagger
        if (lower.contains("\"openapi\"") || lower.contains("\"swagger\"")) {
            String title = extractJsonString(body, "title");
            double conf = lower.contains("\"openapi\":\"3") ? 0.97 : 0.85;
            hints.add("OpenAPI spec detected — can extract endpoints and schemas");
            extractPaths(body, endpoints);
            return new ProbeResult(url, "openapi", title, conf, endpoints, hints, null);
        }

        // OData $metadata (XML)
        if (lower.contains("edmx:") || lower.contains("entitytype") || lower.contains("$metadata")) {
            hints.add("OData $metadata detected — entity types will be extracted");
            return new ProbeResult(url, "odata", null, 0.90, endpoints, hints, null);
        }

        // GraphQL introspection
        if (lower.contains("\"__schema\"") || lower.contains("\"introspectionquery\"")) {
            hints.add("GraphQL schema detected — types and queries will be extracted");
            return new ProbeResult(url, "graphql", null, 0.92, endpoints, hints, null);
        }

        // Generic JSON
        if (body.trim().startsWith("{") || body.trim().startsWith("[")) {
            hints.add("JSON response — will be treated as structured data");
            return new ProbeResult(url, "json", null, 0.60, endpoints, hints, null);
        }

        hints.add("Response received but format not recognised");
        return new ProbeResult(url, "unknown", null, 0.20, endpoints, hints, null);
    }

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int quote1 = json.indexOf('"', colon + 1);
        if (quote1 < 0) return null;
        int quote2 = json.indexOf('"', quote1 + 1);
        if (quote2 < 0) return null;
        return json.substring(quote1 + 1, quote2);
    }

    private void extractPaths(String json, List<String> out) {
        int idx = json.indexOf("\"paths\"");
        if (idx < 0) return;
        int brace = json.indexOf('{', idx);
        if (brace < 0) return;
        int depth = 0;
        int start = brace;
        for (int i = start; i < Math.min(json.length(), start + 4000); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    // Extract path keys from the paths block
                    String paths = json.substring(start, i);
                    int p = 0;
                    while ((p = paths.indexOf("\"/", p)) >= 0) {
                        int end = paths.indexOf('"', p + 1);
                        if (end > p) {
                            String path = paths.substring(p + 1, end);
                            if (path.startsWith("/") && !out.contains(path)) out.add(path);
                            if (out.size() >= 10) return;
                        }
                        p = end + 1;
                    }
                    return;
                }
            }
        }
    }
}
