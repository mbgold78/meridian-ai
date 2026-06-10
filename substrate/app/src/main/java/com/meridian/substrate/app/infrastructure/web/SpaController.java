package com.meridian.substrate.app.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Catch-all controller that forwards unmatched non-API routes to index.html,
 * allowing React Router to handle client-side navigation.
 */
@Controller
public class SpaController {

    @RequestMapping(value = {
            "/ecosystem", "/ecosystem/**",
            "/diagnostics", "/diagnostics/**",
            "/designer", "/designer/**",
            "/transformation", "/transformation/**",
            "/roadmap", "/roadmap/**",
            "/decisions", "/decisions/**",
            // Beacon dashboards — product namespace (frontend features/portfolio/routes.tsx)
            "/portfolio", "/portfolio/**"
    })
    public String forward(HttpServletRequest request) {
        return "forward:/index.html";
    }
}
