FROM python:3.11-buster as builder
WORKDIR /app

COPY requirements.txt .
RUN python -m venv /app/.venv
RUN /app/.venv/bin/pip install -r requirements.txt



FROM python:3.11-slim-buster
WORKDIR /app

ENV PATH="/app/.venv/bin:$PATH"
COPY --from=builder /app/.venv /app/.venv
COPY main.py .

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080", "--log-level", "debug"]
