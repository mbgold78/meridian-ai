package com.meridian.substrate.app.core.exception;

import com.meridian.substrate.app.api.v1.request.CapabilityCreateRequest;
import com.meridian.substrate.app.config.JacksonConfig;
import com.meridian.substrate.common.ai.LlmRateLimitException;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins all nine exception mappings of {@link GlobalExceptionHandler}
 * (port-map §2.2) and the no-stack-trace-leak guarantee.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders
                .standaloneSetup(new ThrowingProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new JacksonConfig().objectMapper()))
                .build();
    }

    @Test
    void entityNotFound_maps404() throws Exception {
        mvc.perform(get("/boom/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.errors[0].message").value(
                        "Domain not found with id: 00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void noResourceFound_maps404() {
        ResponseEntity<?> response = new GlobalExceptionHandler()
                .handleNoResource(new NoResourceFoundException(HttpMethod.GET, "/missing"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void typeMismatch_maps400() throws Exception {
        mvc.perform(get("/boom/typed/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("BAD_REQUEST"));
    }

    @Test
    void illegalArgument_maps400() throws Exception {
        mvc.perform(get("/boom/illegal"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.errors[0].message").value("bad input"));
    }

    @Test
    void beanValidation_maps400_withPerFieldErrors() throws Exception {
        mvc.perform(post("/boom/validated")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code").exists())
                .andExpect(jsonPath("$.errors[*].field").exists())
                .andExpect(jsonPath("$.errors[0].code").value("VALIDATION_ERROR"));
    }

    @Test
    void optimisticLock_maps409() throws Exception {
        mvc.perform(get("/boom/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code").value("CONFLICT"))
                .andExpect(jsonPath("$.errors[0].message").value("Resource was modified by another request"));
    }

    @Test
    void llmRefusal_maps422() throws Exception {
        mvc.perform(get("/boom/refusal"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].code").value("LLM_REFUSAL"));
    }

    @Test
    void llmRateLimit_maps429() throws Exception {
        mvc.perform(get("/boom/rate-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errors[0].code").value("RATE_LIMITED"));
    }

    @Test
    void responseStatusException_statusPassesThrough() throws Exception {
        mvc.perform(get("/boom/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors[0].code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.errors[0].message").value("Admin persona required"));
    }

    @Test
    void unhandledException_maps500_withoutLeakingDetails() throws Exception {
        MvcResult result = mvc.perform(get("/boom/unhandled"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errors[0].code").value("INTERNAL_ERROR"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("500 responses must never leak internal exception details or stack traces")
                .doesNotContain("secret internal detail")
                .doesNotContain("IllegalStateException")
                .doesNotContain("at com.meridian");
    }

    @RestController
    @RequestMapping("/boom")
    static class ThrowingProbeController {

        @GetMapping("/not-found")
        public Map<String, String> notFound() {
            throw new EntityNotFoundException("Domain",
                    UUID.fromString("00000000-0000-0000-0000-000000000001"));
        }

        @GetMapping("/typed/{id}")
        public Map<String, String> typed(@PathVariable("id") UUID id) {
            return Map.of("id", id.toString());
        }

        @GetMapping("/illegal")
        public Map<String, String> illegal() {
            throw new IllegalArgumentException("bad input");
        }

        @PostMapping("/validated")
        public Map<String, String> validated(@Valid @RequestBody CapabilityCreateRequest request) {
            return Map.of("ok", "true");
        }

        @GetMapping("/conflict")
        public Map<String, String> conflict() {
            throw new OptimisticLockingFailureException("stale version");
        }

        @GetMapping("/refusal")
        public Map<String, String> refusal() {
            throw new LlmRefusalException("I cannot answer that question.");
        }

        @GetMapping("/rate-limit")
        public Map<String, String> rateLimit() {
            throw new LlmRateLimitException("rate limit exceeded");
        }

        @GetMapping("/forbidden")
        public Map<String, String> forbidden() {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin persona required");
        }

        @GetMapping("/unhandled")
        public Map<String, String> unhandled() {
            throw new IllegalStateException("secret internal detail");
        }
    }
}
