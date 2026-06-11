package com.meridian.substrate.app.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Reads persona headers from inbound requests and populates the
 * request-scoped {@link PersonaContext}.
 */
@Component
public class PersonaInterceptor implements HandlerInterceptor {

    private final PersonaContext personaContext;

    public PersonaInterceptor(PersonaContext personaContext) {
        this.personaContext = personaContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String level = request.getHeader("X-Persona-Level");
        if (level != null) {
            personaContext.setPersonaLevel(level);
        }
        personaContext.setPersonaId(request.getHeader("X-Persona-Id"));
        personaContext.setPersonaLabel(request.getHeader("X-Persona-Label"));
        return true;
    }
}
