# Security

Instant Transfer Switch is a portfolio project that simulates payment systems. It is not affiliated with any real payment scheme, bank or card network, and it is not meant to handle real money or real customer data.

## Reporting a vulnerability

Please report security issues privately, not in a public issue:

- through GitHub: **Security > Report a vulnerability** on this repository, or
- by email to amirizalrahmat@gmail.com.

Include the steps to reproduce and the version or commit you tested. I will reply within a week.

## Supported versions

Only the latest version on `main` receives fixes.

## Demo credentials

The repository contains credentials that exist only so the demo runs with one command. They are public, so change all of them before running this anywhere other than your own machine:

- The bank API keys in `docker-compose.yml` (`alfa-dev-key`, `bravo-dev-key`, `charlie-dev-key`)
- The operations admin key `local-admin-key` (`ITS_ADMIN_KEY`)
- The PostgreSQL password `switch` and the Grafana password `admin`

## Known limitations

Messages between the switch and the banks are not yet signed and do not use mutual TLS (see the roadmap).
