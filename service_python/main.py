import logging
import os

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import RedirectResponse
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.propagate import get_global_textmap
from opentelemetry.sdk.resources import SERVICE_NAME, Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from starlette.middleware.base import BaseHTTPMiddleware

THIS_SERVICE_NAME = os.getenv("SERVICE_NAME", "ServicePython")
NEXT_SERVICE_URL = os.getenv("NEXT_SERVICE_URL", "")
TRACE_COLLECTOR_URL = os.getenv("TRACE_COLLECTOR_URL", "http://jaeger:4318/v1/traces")


class LoggingMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        if request.url.path != "/ping":
            return await call_next(request)

        logger.info(f"Request: {request.method} {request.url.path}, Headers: {dict(request.headers)}")
        response = await call_next(request)
        logger.info(f"Response: {response.status_code}, Headers: {dict(response.headers)}")
        return response


# Initializing http-server
app = FastAPI(title=THIS_SERVICE_NAME)
app.add_middleware(LoggingMiddleware)

logging.basicConfig(level=logging.WARNING, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)
logger.setLevel(logging.DEBUG)

# Initializing Opentelemetry
trace.set_tracer_provider(TracerProvider(resource=Resource(attributes={SERVICE_NAME: THIS_SERVICE_NAME})))
otlp_exporter = OTLPSpanExporter(endpoint=TRACE_COLLECTOR_URL)
span_processor = BatchSpanProcessor(otlp_exporter)
trace.get_tracer_provider().add_span_processor(span_processor)
tracer = trace.get_tracer(f"{THIS_SERVICE_NAME}-tracer")


async def make_get_request(url: str):
    # Create child span
    with tracer.start_as_current_span("make_get_request()") as span:
        span.set_attribute("http.method", "GET")
        span.set_attribute("http.url", url)

        # Injecting traceparent into headers (FYI)
        headers = {}
        get_global_textmap().inject(headers)

        async with httpx.AsyncClient() as client:
            try:
                logger.info(f'next request url: "{url}", headers: {headers}')
                r = await client.get(url, headers=headers)
                logger.info(f"next response: {r.text}")

                r.raise_for_status()
                return r.json()
            except Exception as exc:
                span.record_exception(exc)
                span.set_status(trace.StatusCode.ERROR, f"Exception: {exc}")
                raise Exception(f"failed to perform request: {exc}")


@app.get("/")
async def redirect_to_docs():
    return RedirectResponse(url="/docs")


@app.get("/ping")
async def ping(request: Request):
    # Extracting traceparent from headers (FYI)
    ctx = get_global_textmap().extract(request.headers)
    with tracer.start_as_current_span(f"{request.method.upper()} {request.url.path}", ctx) as span:
        span.set_attribute("language", "python")
        if not NEXT_SERVICE_URL:
            return {"message": "pong"}

        # Request next service
        response = await make_get_request(NEXT_SERVICE_URL)
        return {"this": THIS_SERVICE_NAME, "next:": NEXT_SERVICE_URL, "response": response}
