package com.paicli.platform.server.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.platform.server.config.LangfuseProperties;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Configuration
public class LangfuseTelemetryConfiguration {
    @Bean
    AgentTelemetry agentTelemetry(LangfuseProperties properties, ObjectMapper mapper) {
        if (!properties.enabled()) return NoopAgentTelemetry.INSTANCE;
        String credentials = properties.publicKey() + ":" + properties.secretKey();
        String authorization = "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(properties.tracesEndpoint())
                .addHeader("Authorization", authorization)
                .addHeader("x-langfuse-ingestion-version", "4")
                .setTimeout(Duration.ofMillis(properties.exportTimeoutMillis()))
                .build();
        BatchSpanProcessor processor = BatchSpanProcessor.builder(exporter)
                .setMaxQueueSize(2_048)
                .setMaxExportBatchSize(256)
                .setScheduleDelay(Duration.ofMillis(500))
                .setExporterTimeout(Duration.ofMillis(properties.exportTimeoutMillis()))
                .build();
        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), "paicli-server")));
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(processor)
                .build();
        return new OpenTelemetryAgentTelemetry(
                provider.get("com.paicli.platform.server.agent"), provider, properties,
                new LangfusePayloadSanitizer(mapper, properties));
    }
}
