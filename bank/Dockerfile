FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/bank ./cmd/bank

FROM alpine:3.21
RUN adduser -D -u 10001 bank
USER 10001
COPY --from=build /out/bank /usr/local/bin/bank
EXPOSE 9000
HEALTHCHECK --interval=10s --timeout=2s CMD wget -qO- http://localhost:9000/health || exit 1
ENTRYPOINT ["bank"]
