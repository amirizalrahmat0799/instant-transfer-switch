# Changelog

All notable changes to this project. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Dependabot: weekly, grouped minor and patch updates for Maven, the Docker base images and GitHub Actions.

## [0.3.0] - 2026-10-02

### Added
- Prometheus metrics at `/actuator/prometheus`: transfers by outcome and ISO reason code, receiving-bank latency
  histograms, bank up/down, net positions, debit cap usage, pending and completed reversals, settlement close time.
- Prometheus and Grafana in Docker Compose, with a provisioned "Instant Transfer Switch" dashboard at
  http://localhost:3000.
- Latency histograms for the switch's own HTTP endpoints.

## [0.2.0] - 2026-10-02

### Added
- Interactive API docs with Swagger UI at `/swagger-ui.html` (OpenAPI spec at `/v3/api-docs`), with each endpoint's
  required headers documented from the same path rules the switch enforces.
- Integration tests start their own PostgreSQL with Testcontainers; `mvn verify` needs only Docker.

### Changed
- CI no longer needs a PostgreSQL service container or the `ITS_TEST_DB_URL` variable.
- The Testcontainers Docker client asks for API 1.44, so tests work with Docker Engine 29.

## [0.1.1] - 2026-10-02

### Fixed
- A read timeout that happened while reading the receiving bank's response headers caused a 500 instead of
  rejecting the transfer with AB05 and reversing it.
- A transfer could stay in flight forever if forwarding failed unexpectedly, which held part of the bank's debit cap
  and blocked the settlement close.

## [0.1.0] - 2026-10-02

### Added
- Spring Boot switch: ISO 20022 pacs.008 / pacs.002 / camt.056 / camt.029, proxy lookup, net debit caps,
  idempotent transfers, timeouts with automatic reversals, heartbeats, end-of-day settlement and the operations dashboard.
- Go bank simulator with ledger, holds, chaos controls and reconciliation.
- Docker Compose setup, `scripts/demo.sh` and CI.

[Unreleased]: https://github.com/amirizalrahmat0799/instant-transfer-switch/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/amirizalrahmat0799/instant-transfer-switch/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/amirizalrahmat0799/instant-transfer-switch/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/amirizalrahmat0799/instant-transfer-switch/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/amirizalrahmat0799/instant-transfer-switch/releases/tag/v0.1.0
