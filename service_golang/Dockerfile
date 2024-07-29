FROM golang:1.21-alpine AS builder
WORKDIR /app

COPY go.mod go.sum ./
RUN go mod download

COPY main.go .
RUN go build -o main .



FROM alpine:latest
WORKDIR /app

COPY --from=builder /app/main .
RUN addgroup -S appgroup && adduser -S appuser -G appgroup && chown appuser:appgroup main
USER appuser

CMD ["./main"]
