package com.example.demo;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.context.propagation.TextMapPropagator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class PingController {

    private final Tracer tracer;
    private final TextMapPropagator textMapPropagator;
    private final TextMapGetter textMapGetter;
    private final TextMapSetter textMapSetter;
    private final RestTemplate restTemplate = new RestTemplate();
    private final RestClient restClient = RestClient.create();
    private final String serviceName;
    private final String nextServiceUrl;

    @Autowired
    public PingController(
            Tracer tracer,
            TextMapPropagator textMapPropagator,
            TextMapGetter textMapGetter,
            TextMapSetter textMapSetter,
            String serviceName,
            String nextServiceUrl) {
        this.tracer = tracer;
        this.textMapPropagator = textMapPropagator;
        this.textMapGetter = textMapGetter;
        this.textMapSetter = textMapSetter;
        this.serviceName = serviceName;
        this.nextServiceUrl = nextServiceUrl;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping(@RequestHeader Map<String, String> headers) {
        // Extracting traceparent from headers (FYI)
        Context extractedContext = textMapPropagator.extract(Context.current(), headers, textMapGetter);
        Span span = tracer.spanBuilder("GET /ping").setParent(extractedContext).startSpan();

        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("language", "java");

            Map<String, Object> response = new LinkedHashMap<>();
            if (nextServiceUrl == null || nextServiceUrl.isEmpty()) {
                response.put("message", "pong");
            } else {
                JsonNode nextServiceResponse = makeGetRequest(nextServiceUrl);
                response.put("this", serviceName);
                response.put("next", nextServiceUrl);
                response.put("response", nextServiceResponse);
            }
            return response;
        } finally {
            span.end();
        }
    }

    public JsonNode makeGetRequest(String url) {
        // Create child span
        Span span = tracer.spanBuilder("makeGetRequest()").setParent(Context.current()).startSpan();
        try (Scope scope = span.makeCurrent()) {
            span.setAttribute("http.method", "GET");
            span.setAttribute("http.url", url);

            // Injecting traceparent into headers (FYI)
            Map<String, String> headers = new HashMap<>();
            textMapPropagator.inject(Context.current(), headers, textMapSetter);

            String responseString = restClient.get()
                .uri(url)
                .headers(httpHeaders -> headers.forEach(httpHeaders::set))
                .retrieve()
                .body(String.class);

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode responseJson = objectMapper.readTree(responseString);
            return responseJson;
        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, "Exception: " + e.getMessage());
            throw new RuntimeException("Error during HTTP GET request", e);
        } finally {
            span.end();
        }
    }
}