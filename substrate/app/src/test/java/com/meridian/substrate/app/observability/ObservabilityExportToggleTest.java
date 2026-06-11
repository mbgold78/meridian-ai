package com.meridian.substrate.app.observability;

import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 3 config-off guarantee (plan §8; docs/observability.md): with the
 * shipped {@code application.yml} defaults ({@code substrate.observability.*}
 * all off) NO OTLP beans exist — no {@link OtlpMeterRegistry}, no
 * {@link OtlpHttpSpanExporter} — nothing can leave the process. Flipping the
 * single {@code substrate.observability.enabled=true} key turns on both lanes
 * through the real Boot autoconfigurations and the real YAML placeholder
 * mappings ({@link ConfigDataApplicationContextInitializer} loads the actual
 * application.yml).
 *
 * <p>Note Boot 3.4 semantics: the OTel bridge {@link Tracer} bean exists
 * whenever micrometer-tracing-bridge-otel is on the classpath —
 * {@code management.tracing.enabled=false} disables tracing by withholding
 * the OTLP span exporter and the observation tracing handlers (no spans are
 * ever started), not by removing the bridge bean. The export-boundary
 * assertion here is therefore the exporter beans.
 */
class ObservabilityExportToggleTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    MetricsAutoConfiguration.class,
                    OtlpMetricsExportAutoConfiguration.class,
                    OpenTelemetryAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class,
                    OtlpTracingAutoConfiguration.class));

    @Test
    void observabilityOffByDefault_noOtlpBeansAnywhere() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OtlpMeterRegistry.class);
            assertThat(context).doesNotHaveBean(OtlpHttpSpanExporter.class);
        });
    }

    @Test
    void singleEnabledKey_turnsOnMetricExportAndTracing() {
        runner.withPropertyValues("substrate.observability.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OtlpMeterRegistry.class);
                    assertThat(context).hasSingleBean(Tracer.class);
                    assertThat(context).hasSingleBean(OtlpHttpSpanExporter.class);
                });
    }

    @Test
    void tracingCanStayOffWhileMetricsExport() {
        runner.withPropertyValues(
                        "substrate.observability.enabled=true",
                        "substrate.observability.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OtlpMeterRegistry.class);
                    assertThat(context).doesNotHaveBean(OtlpHttpSpanExporter.class);
                });
    }
}
