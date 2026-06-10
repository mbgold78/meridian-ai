package com.meridian.substrate.app.infrastructure.web;

import com.meridian.substrate.app.api.v1.response.ApiMeta;
import com.meridian.substrate.app.api.v1.response.ApiResponse;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Wraps {@code @RestController} return values in {@code {data, meta, errors}} so
 * controllers can return plain entities and clients always see the same envelope
 * shape.
 *
 * <p><b>Scoped to {@code com.meridian.substrate.app.api}</b> (port-map §2.2 fix):
 * homepoc applied this advice unconditionally, which would also wrap springdoc
 * ({@code /v3/api-docs}) and actuator endpoint payloads, breaking their consumers.
 * Scoping by base package leaves any controller outside the API namespace —
 * including framework-provided ones — untouched.
 *
 * <p>Skips wrapping for:
 * <ul>
 *   <li>Values already wrapped in {@link ApiResponse}</li>
 *   <li>SSE / streaming responses (text/event-stream)</li>
 *   <li>Void / null responses</li>
 * </ul>
 */
@RestControllerAdvice(basePackages = "com.meridian.substrate.app.api")
public class ResponseEnvelopeAdvice implements ResponseBodyAdvice<Object> {

    private final PersonaContext personaContext;

    public ResponseEnvelopeAdvice(PersonaContext personaContext) {
        this.personaContext = personaContext;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // Let the beforeBodyWrite method decide per-response
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {

        // Already wrapped
        if (body instanceof ApiResponse<?>) {
            return body;
        }

        // SSE / streaming
        if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(selectedContentType)) {
            return body;
        }

        // Void
        if (body == null) {
            return null;
        }

        String requestId = MDC.get("requestId");
        String personaLevel = personaContext.getPersonaLevel();
        ApiMeta meta = ApiMeta.now(requestId, personaLevel);
        return ApiResponse.ok(body, meta);
    }
}
