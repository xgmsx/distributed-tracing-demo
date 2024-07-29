package main

import (
	"context"
	"encoding/json"
	"fmt"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	"go.opentelemetry.io/otel/propagation"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"go.opentelemetry.io/otel/trace"
	"io"
	"log"
	"net/http"
	"os"
)

func getEnv(key, fallback string) string {
	value, exists := os.LookupEnv(key)
	if !exists {
		value = fallback
	}
	return value
}

type Config struct {
	ServiceName       string
	NextServiceUrl    string
	TraceCollectorUrl string
}

type Application struct {
	tracer trace.Tracer
	config Config
}

func (app *Application) makeGetRequest(ctx context.Context, url string) (string, error) {

	// # Create child span
	ctx, span := app.tracer.Start(ctx, "makeGetRequest()")
	span.SetAttributes(attribute.String("http.method", "GET"))
	span.SetAttributes(attribute.String("http.url", url))

	var err error
	defer func() {
		if err != nil {
			span.SetStatus(codes.Error, err.Error())
			span.RecordError(err)
		}
		span.End()
	}()

	// Injecting traceparent into headers (FYI)
	headers := http.Header{}
	propagation.TraceContext{}.Inject(ctx, propagation.HeaderCarrier(headers))

	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return "", fmt.Errorf("failed to create request: %v", err)
	}

	for key, values := range headers {
		for _, value := range values {
			req.Header.Add(key, value)
		}
	}

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		return "", fmt.Errorf("failed to perform request: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("failed to read response body: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("unexpected request status: %v, body: %s", resp.StatusCode, body)
	}

	var js json.RawMessage
	if err := json.Unmarshal(body, &js); err != nil {
		return "", fmt.Errorf("response body is not valid JSON: %w", err)
	}

	return string(body), nil
}

func (app *Application) handlerPing(w http.ResponseWriter, r *http.Request) {
	// Extracting traceparent from headers (FYI)
	ctxParent := propagation.TraceContext{}.Extract(context.Background(), propagation.HeaderCarrier(r.Header))
	ctxHandler, spanHandler := app.tracer.Start(ctxParent, fmt.Sprintf("%s %s", r.Method, r.URL.Path))

	spanHandler.SetAttributes(attribute.String("language", "golang"))
	defer spanHandler.End()

	if app.config.NextServiceUrl == "" {
		fmt.Fprint(w, `{"message": "pong"}`)
		return
	}

	// Request next service
	resp, err := app.makeGetRequest(ctxHandler, app.config.NextServiceUrl)
	if err != nil {
		fmt.Fprintf(w, `{"this": "%s", "next": "%s", "error": "%s"}`,
			app.config.ServiceName, app.config.NextServiceUrl, err.Error())
	} else {
		fmt.Fprintf(w, `{"this": "%s", "next": "%s", "response": %s}`,
			app.config.ServiceName, app.config.NextServiceUrl, resp)
	}
}

func main() {
	conf := Config{
		ServiceName:       getEnv("SERVICE_NAME", "ServiceGolang"),
		NextServiceUrl:    getEnv("NEXT_SERVICE_URL", ""),
		TraceCollectorUrl: getEnv("TRACE_COLLECTOR_URL", "http://jaeger:4318/v1/traces"),
	}

	// Initializing Opentelemetry
	ctx := context.Background()
	traceExporter, err := otlptracehttp.New(
		ctx,
		otlptracehttp.WithEndpointURL(conf.TraceCollectorUrl),
		otlptracehttp.WithInsecure(),
	)
	if err != nil {
		log.Fatal(fmt.Errorf("trace initialization error: %w", err.Error()))
	}
	traceProvider := sdktrace.NewTracerProvider(
		sdktrace.WithBatcher(traceExporter),
		sdktrace.WithResource(
			resource.NewWithAttributes(
				semconv.SchemaURL,
				semconv.ServiceNameKey.String(conf.ServiceName),
			)),
	)
	otel.SetTracerProvider(traceProvider)
	defer traceProvider.Shutdown(context.Background())

	// Initializing http-server
	app := &Application{
		tracer: otel.Tracer(fmt.Sprintf("%s-tracer", conf.ServiceName)),
		config: conf,
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/ping", app.handlerPing)

	log.Print("Starting server on 0.0.0.0:8080")
	log.Fatal(http.ListenAndServe("0.0.0.0:8080", mux))
}
