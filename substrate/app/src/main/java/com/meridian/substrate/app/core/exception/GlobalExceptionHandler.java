package com.meridian.substrate.app.core.exception;

import com.meridian.substrate.app.api.v1.response.ApiError;
import com.meridian.substrate.app.api.v1.response.ApiMeta;
import com.meridian.substrate.app.api.v1.response.ApiResponse;
import com.meridian.substrate.common.ai.LlmRateLimitException;
import com.meridian.substrate.common.ai.LlmUnavailableException;
import com.meridian.substrate.engine.cascade.CascadeQueryService;
import com.meridian.substrate.engine.cascade.exception.CascadeConflictException;
import com.meridian.substrate.engine.cascade.exception.CascadeForbiddenException;
import com.meridian.substrate.engine.cascade.read.CascadeDetail;
import com.meridian.substrate.engine.contract.exception.ContractConflictException;
import com.meridian.substrate.engine.contract.gateway.ContractDeniedException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.UUID;

/**
 * Global exception handler that maps exceptions to structured API error responses.
 * No stack traces are ever exposed in responses.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final CascadeQueryService cascadeQueryService;

    public GlobalExceptionHandler(CascadeQueryService cascadeQueryService) {
        this.cascadeQueryService = cascadeQueryService;
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleEntityNotFound(EntityNotFoundException ex) {
        log.warn("Entity not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "Invalid value for parameter '" + ex.getName() + "': " + ex.getValue());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        log.warn("Validation failed: {}", ex.getMessage());
        List<ApiError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError("VALIDATION_ERROR", fe.getDefaultMessage(), fe.getField()))
                .toList();
        ApiMeta meta = ApiMeta.now(MDC.get("requestId"), null);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(fieldErrors, meta));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(OptimisticLockingFailureException ex) {
        log.warn("Optimistic locking conflict: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "CONFLICT", "Resource was modified by another request");
    }

    @ExceptionHandler(LlmRefusalException.class)
    public ResponseEntity<ApiResponse<Void>> handleLlmRefusal(LlmRefusalException ex) {
        log.warn("LLM refusal: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.UNPROCESSABLE_ENTITY, "LLM_REFUSAL", ex.getMessage());
    }

    /**
     * Degraded mode (no API key): LLM-backed endpoints answer 503 with a clear
     * envelope instead of falling through to the 500 catch-all. The app itself
     * stays healthy — only AI-backed calls are unavailable (A2 degraded-mode
     * contract; homepoc had no degraded mode so no handler existed to port).
     */
    @ExceptionHandler(LlmUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleLlmUnavailable(LlmUnavailableException ex) {
        log.warn("LLM unavailable (degraded mode): {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.SERVICE_UNAVAILABLE, "LLM_UNAVAILABLE", ex.getMessage());
    }

    @ExceptionHandler(LlmRateLimitException.class)
    public ResponseEntity<ApiResponse<Void>> handleLlmRateLimit(LlmRateLimitException ex) {
        log.warn("LLM rate limit exceeded: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", ex.getMessage());
    }

    /**
     * Honor the status code carried by {@link ResponseStatusException}. Without
     * this handler the catch-all below would convert every controller-thrown
     * status exception (403 admin gates, 409 conflicts, etc.) into a 500.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        log.warn("Status exception {}: {}", status.value(), ex.getReason());
        String reason = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        return buildErrorResponse(status, status.name(), reason);
    }

    /**
     * Unknown ids surfaced from inside engine command transactions (e.g. the
     * cascade lifecycle's locked lookups, notification mark-read) arrive as
     * the JPA exception type — same 404 envelope as the app-layer
     * {@link EntityNotFoundException} (engine-spec cascade.md §6.2 error table).
     */
    @ExceptionHandler(jakarta.persistence.EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleJpaEntityNotFound(
            jakarta.persistence.EntityNotFoundException ex) {
        log.warn("Entity not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    /**
     * Cascade command conflicts (engine-spec cascade.md §6.2): 409 with the
     * exception's spec code ({@code CASCADE_STALE_STATE} /
     * {@code CASCADE_ILLEGAL_TRANSITION} / {@code CASCADE_TERMINAL}) and the
     * CURRENT {@link CascadeDetail} in the envelope {@code data} — the client
     * refreshes ("cascade moved on — review current state"), never renders a
     * generic failure (§11.5). None of these codes is decline vocabulary:
     * a legitimate decline is 200 (ADR-001 non-negotiable #1).
     */
    @ExceptionHandler(CascadeConflictException.class)
    public ResponseEntity<ApiResponse<CascadeDetail>> handleCascadeConflict(CascadeConflictException ex) {
        log.warn("Cascade conflict [{}]: {}", ex.getCode(), ex.getMessage());
        ApiMeta meta = ApiMeta.now(MDC.get("requestId"), null);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiResponse<>(currentCascadeDetail(ex.getCascadeId()), meta,
                        List.of(ApiError.of(ex.getCode().name(), ex.getMessage()))));
    }

    /**
     * Cascade actor guards (engine-spec cascade.md §6.2): 403 with
     * {@code CASCADE_NOT_LEVEL_OWNER} / {@code CASCADE_NOT_ORIGINATOR}.
     * Persona-header trust in Phase 2 (S9); OIDC hardens the source in Phase 5.
     */
    @ExceptionHandler(CascadeForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleCascadeForbidden(CascadeForbiddenException ex) {
        log.warn("Cascade forbidden [{}]: {}", ex.getCode(), ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, ex.getCode().name(), ex.getMessage());
    }

    /**
     * Contract command conflicts (engine-spec contracts.md §4.5): 409 with the
     * spec code ({@code CONTRACT_CONTENT_FROZEN} /
     * {@code INVALID_CONTRACT_STATE} / {@code RESPONSE_CONFLICT}). None of
     * these is decline vocabulary: DECLINE is a legitimate first-class
     * terminal resolution that returns 200 always (port-map trap #8; ADR-002
     * D6) — conflicts arise only from frozen content, conflicting responses,
     * or wrong-state commands. The fourth §4.5 code, {@code CONTRACT_DENIAL},
     * is the gateway's ({@code ContractDeniedException}, below).
     */
    @ExceptionHandler(ContractConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleContractConflict(ContractConflictException ex) {
        log.warn("Contract conflict [{}]: {}", ex.getCode(), ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getCode().name(), ex.getMessage());
    }

    /**
     * Enforcement-gateway denial (engine-spec contracts.md §4.5; ADR-002
     * D1/D4): HTTP <b>409</b>, code {@code CONTRACT_DENIAL}, message = the
     * gateway's human summary citing contract key + clause descriptions +
     * decisionId. A governance outcome, never a 5xx — and never decline
     * vocabulary (decline returns 200; port-map trap #8). The business
     * transaction has already rolled back; the DENY decision row survives via
     * REQUIRES_NEW (§10.4, ADR D17).
     */
    @ExceptionHandler(ContractDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleContractDenied(ContractDeniedException ex) {
        log.warn("Contract denial [reason={}, decisionId={}]: {}",
                ex.getDeny().reason(), ex.getDecisionId(), ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, ContractDeniedException.CODE, ex.getMessage());
    }

    /**
     * Breach waiver attempted by a non-HUMAN actor (engine-spec contracts.md
     * §5.4/§7.2; ADR-002 D16 built-in non-removable guard): 403. This is the
     * service-level backstop — REST actors are HUMAN-typed pre-Phase-5, so
     * the lane fires mainly for engine-internal misuse and (with 3c) the MCP
     * write surface, where the gateway PEP additionally denies the
     * {@code waive_breach} tool for AGENT principals.
     */
    @ExceptionHandler(com.meridian.substrate.engine.contract.exception.WaiverHumanOnlyException.class)
    public ResponseEntity<ApiResponse<Void>> handleWaiverHumanOnly(
            com.meridian.substrate.engine.contract.exception.WaiverHumanOnlyException ex) {
        log.warn("Waiver denied for non-human actor: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "WAIVER_HUMAN_ONLY", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAll(Exception ex) {
        log.error("Unhandled exception", ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Please try again later.");
    }

    private ResponseEntity<ApiResponse<Void>> buildErrorResponse(HttpStatus status, String code, String message) {
        ApiMeta meta = ApiMeta.now(MDC.get("requestId"), null);
        ApiResponse<Void> body = ApiResponse.error(List.of(ApiError.of(code, message)), meta);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Best-effort current-state projection for the 409 envelope {@code data};
     * {@code null} for draft-anchored conflicts (no cascade row yet) or if the
     * re-read itself fails — the conflict envelope must never be masked by its
     * own enrichment.
     */
    private CascadeDetail currentCascadeDetail(UUID cascadeId) {
        if (cascadeId == null) {
            return null;
        }
        try {
            return cascadeQueryService.detail(cascadeId).orElse(null);
        } catch (RuntimeException reReadFailure) {
            log.warn("Could not project current cascade detail for conflict envelope [cascadeId={}]",
                    cascadeId, reReadFailure);
            return null;
        }
    }
}
