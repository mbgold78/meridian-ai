package com.meridian.substrate.app.infrastructure.web;

import com.meridian.substrate.app.api.EnvelopeProbeController;
import com.meridian.substrate.app.api.v1.response.ApiResponse;
import com.meridian.substrate.app.config.JacksonConfig;
import com.meridian.substrate.app.testsupport.UnscopedProbeController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the response-envelope contract: plain controller return values from the
 * {@code com.meridian.substrate.app.api} namespace are wrapped in
 * {@code {data, meta, errors}}; already-wrapped, SSE, null, and out-of-namespace
 * responses are left alone (port-map §2.2 envelope-advice scoping fix).
 */
class ResponseEnvelopeAdviceTest {

    private MockMvc mvc;
    private ResponseEnvelopeAdvice advice;

    @BeforeEach
    void setUp() {
        advice = new ResponseEnvelopeAdvice(new PersonaContext());
        mvc = MockMvcBuilders
                .standaloneSetup(new EnvelopeProbeController(), new UnscopedProbeController())
                .setControllerAdvice(advice)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new JacksonConfig().objectMapper()))
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void plainReturnValue_isWrappedInEnvelope_withRequestIdMatchingHeader() throws Exception {
        MvcResult result = mvc.perform(get("/probe/plain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value").value("hello"))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty())
                .andExpect(jsonPath("$.meta.version").value("0.1.0"))
                .andExpect(jsonPath("$.meta.personaLevel").value("L3_ANALYST"))
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andReturn();

        String headerRequestId = result.getResponse().getHeader("X-Request-Id");
        assertThat(headerRequestId).isNotBlank();
        assertThat(result.getResponse().getContentAsString())
                .as("envelope meta.requestId must match the X-Request-Id header set by RequestIdFilter")
                .contains("\"requestId\":\"" + headerRequestId + "\"");
    }

    @Test
    void alreadyWrappedResponse_isNotDoubleWrapped() throws Exception {
        mvc.perform(get("/probe/wrapped"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.value").value("pre"))
                .andExpect(jsonPath("$.meta.requestId").value("pre-wrapped-id"))
                .andExpect(jsonPath("$.meta.personaLevel").value("L1_EXECUTIVE"))
                // a double wrap would nest data.data
                .andExpect(jsonPath("$.data.data").doesNotExist());
    }

    @Test
    void controllerOutsideApiNamespace_isNotWrapped() throws Exception {
        mvc.perform(get("/outside/raw"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("raw"))
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void sseContentType_isSkipped() {
        Object body = "data: chunk\n\n";
        Object out = advice.beforeBodyWrite(body, null, MediaType.TEXT_EVENT_STREAM, null, null, null);
        assertThat(out).isSameAs(body);
    }

    @Test
    void nullBody_staysNull() {
        Object out = advice.beforeBodyWrite(null, null, MediaType.APPLICATION_JSON, null, null, null);
        assertThat(out).isNull();
    }

    @Test
    void apiResponseBody_passesThroughUnchanged() {
        ApiResponse<String> wrapped = ApiResponse.ok("x", null);
        Object out = advice.beforeBodyWrite(wrapped, null, MediaType.APPLICATION_JSON, null, null, null);
        assertThat(out).isSameAs(wrapped);
    }
}
