package com.meridian.substrate.app.core.exception;

import com.meridian.substrate.app.api.v1.response.ApiError;
import com.meridian.substrate.app.api.v1.response.ApiMeta;
import com.meridian.substrate.app.api.v1.response.ApiResponse;
import com.meridian.substrate.common.ai.LlmRateLimitException;
import com.meridian.substrate.common.ai.LlmUnavailableException;
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

/**
 * Global exception handler that maps exceptions to structured API error responses.
 * No stack traces are ever exposed in responses.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

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
}
