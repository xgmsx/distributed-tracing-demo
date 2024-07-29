package com.example.demo;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.semconv.resource.attributes.ResourceAttributes;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class DemoConfig {

    @Bean
    public String serviceName() {
        return System.getenv().getOrDefault("SERVICE_NAME", "ServiceJava");
    }

    @Bean
    public String nextServiceUrl() {
        return System.getenv().getOrDefault("NEXT_SERVICE_URL", "");
    }

    @Bean
    public String traceCollectorUrl() {
        return System.getenv().getOrDefault("TRACE_COLLECTOR_URL", "http://jaeger:4317");
    }

    @Bean
    public OpenTelemetry openTelemetry(String serviceName, String traceCollectorUrl) {
        Resource resource = Resource.getDefault()
                .merge(Resource.create(io.opentelemetry.api.common.Attributes.of(
                        ResourceAttributes.SERVICE_NAME, serviceName)));

        OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(traceCollectorUrl)
                .build();

        BatchSpanProcessor spanProcessor = BatchSpanProcessor.builder(spanExporter)
                .build();

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(spanProcessor)
                .setResource(Resource.getDefault().merge(resource))
                .build();

        TextMapPropagator textMapPropagator = W3CTraceContextPropagator.getInstance();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(textMapPropagator))
                .build();
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("JavaServiceTracer");
    }

    @Bean
    public TextMapPropagator textMapPropagator(OpenTelemetry openTelemetry) {
        return openTelemetry.getPropagators().getTextMapPropagator();
    }

    @Bean
    public TextMapGetter textMapGetter() {
        return new TextMapGetter<Map<String, String>>() {
            @Override
            public Iterable<String> keys(Map<String, String> carrier) {
                return carrier.keySet();
            }

            @Override
            public String get(Map<String, String> carrier, String key) {
                return carrier.get(key);
            }
        };
    }

    @Bean
    public TextMapSetter textMapSetter() {
        return new TextMapSetter<Map<String, String>>() {
            @Override
            public void set(Map<String, String> carrier, String key, String value) {
                carrier.put(key, value);
            }
        };
    }
}
