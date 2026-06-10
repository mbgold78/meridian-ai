package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.core.service.ChatResponse;
import com.meridian.substrate.app.core.service.ChatService;
import com.meridian.substrate.app.infrastructure.reactive.MdcContextLifter;
import com.meridian.substrate.app.infrastructure.web.PersonaContext;
import com.meridian.substrate.common.ai.CompletionChunk;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for synchronous and streaming chat interactions with the LLM.
 *
 * <p>The streaming endpoint is envelope-exempt ({@code text/event-stream} is
 * skipped by {@code ResponseEnvelopeAdvice}); the sync endpoint returns the
 * plain {@link ChatResponse} and is wrapped in the standard
 * {@code {data, meta, errors}} envelope.
 */
@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;
    private final PersonaContext personaContext;

    public ChatController(ChatService chatService, PersonaContext personaContext) {
        this.chatService = chatService;
        this.personaContext = personaContext;
    }

    /**
     * Conduct a synchronous chat interaction using the tool-calling orchestrator.
     *
     * @param request the chat request containing domain ID and user message
     * @return the chat response with assistant message, tool calls, and token usage
     */
    @PostMapping
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.chat(request.domainId(), request.message(),
                Personas.toEngine(personaContext));
    }

    /**
     * Conduct a streaming chat interaction returning server-sent events.
     * Uses GET so browser EventSource (SSE) can connect directly.
     *
     * @param domainId the active domain UUID
     * @param message  the user's natural-language message
     * @return a reactive stream of server-sent events containing completion chunks
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<CompletionChunk>> chatStream(
            @RequestParam UUID domainId,
            @RequestParam String message) {
        // Capture MDC eagerly on the request thread — the RequestIdFilter clears it
        // in its finally block once the controller returns, which happens before
        // Spring's reactive return handler subscribes. The MdcContextLifter then
        // re-applies this map on each signal across scheduler boundaries.
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        return chatService.chatStream(domainId, message, Personas.toEngine(personaContext))
                .map(chunk -> ServerSentEvent.<CompletionChunk>builder()
                        .event("chunk").data(chunk).build())
                .contextWrite(ctx -> mdcSnapshot != null && !mdcSnapshot.isEmpty()
                        ? ctx.put(MdcContextLifter.MDC_CONTEXT_KEY, mdcSnapshot)
                        : ctx);
    }
}
