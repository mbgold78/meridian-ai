package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.core.service.ProbeService;
import com.meridian.substrate.app.core.service.ProbeService.ProbeResult;
import org.springframework.web.bind.annotation.*;

/**
 * Drives the "Add Source" flow by fingerprinting a caller-supplied URL.
 *
 * <p><b>RISK (port-map §2.2):</b> performs live outbound HTTP — SSRF surface;
 * see {@link ProbeService} for the required pre-production hardening.
 */
@RestController
@RequestMapping("/api/v1/sources")
public class SourceController {

    private final ProbeService probeService;

    public SourceController(ProbeService probeService) {
        this.probeService = probeService;
    }

    @PostMapping("/probe")
    public ProbeResult probe(@RequestBody ProbeRequest request) {
        return probeService.probe(request.url());
    }

    public record ProbeRequest(String url) {}
}
