# e-Banking Transaction API

A paginated transaction-history API built as a **CQRS read model**. Transaction
events are consumed from Kafka and projected into a PostgreSQL read table; the HTTP
API serves paginated, per-month transaction history with on-the-fly currency
conversion and credit/debit totals.

## Architecture

```
                 ┌────────────────┐        ┌──────────────────────┐
  Kafka topic ─► │ Transaction      │  JPA   │ PostgreSQL            │
  "transactions" │ Projector        │ ─────► │ read model            │
                 │ (KafkaListener)  │  save  │ (transaction table)   │
                 └────────────────┘        └──────────┬───────────┘
                                                        │ indexed range scan
                                                        ▼
   JWT (Bearer) ─► [Security] ─► TransactionController ─► TransactionQueryService
                  scope:                                    │
                  transactions:read                         ├─► FxRateService
                                                            │   (cache + retry +
                                                            │    circuit breaker)
                                                            ▼
                                                  TransactionPageResponse (JSON)
```

The read table is a **rebuildable projection** of the Kafka topic — drop it and
replay from offset 0 to rebuild (the projector's `save` is an idempotent upsert).

C4 diagrams (PlantUML): [docs/c4-context.puml](docs/c4-context.puml) (system context)
and [docs/c4-container.puml](docs/c4-container.puml) (containers).

## Tech stack

- Java 21 (LTS), Spring Boot 3.3.5
- Spring Web, Spring Data JPA, Spring Security (OAuth2 resource server / JWT)
- Spring for Apache Kafka
- PostgreSQL + Flyway (schema migrations; Hibernate `ddl-auto: validate`)
- Caffeine cache + Resilience4j (retry + circuit breaker) for the FX provider
- Actuator + Micrometer/Prometheus; springdoc OpenAPI / Swagger UI
- Testcontainers, WireMock, REST Assured, JUnit 5, Mockito

## Running locally

Requirements: JDK 21, Docker (for Postgres + Kafka), and an OAuth2 issuer that
mints JWTs with the `transactions:read` scope.

```bash
# Build + run unit tests
./mvnw clean test

# Run the full suite incl. Testcontainers integration test (needs Docker)
./mvnw clean verify

# Run the app (override the env vars below as needed)
./mvnw spring-boot:run
```

### Configuration (env vars)

| Variable           | Default                              | Purpose                          |
| ------------------ | ------------------------------------ | -------------------------------- |
| `DB_URL`           | `jdbc:postgresql://localhost:5432/transactions` | JDBC URL                |
| `DB_USER`          | `txuser`                             | DB user                          |
| `DB_PASSWORD`      | `changeme`                           | DB password                      |
| `KAFKA_BROKERS`    | `localhost:9092`                     | Kafka bootstrap servers          |
| `KAFKA_TOPIC`      | `transactions`                       | Source topic                     |
| `JWT_ISSUER_URI`   | `https://auth.example-bank.com`      | OIDC issuer (drives JWKS lookup) |
| `FX_API_BASE_URL`  | `https://open.er-api.com/v6`         | External FX rate provider        |

## API

All endpoints require a `Bearer` JWT with the `transactions:read` scope. The
customer identity is taken **exclusively from the validated JWT `sub` claim** and
is never accepted as a request parameter (prevents IDOR).

### `GET /api/v1/transactions`

Paginated history for one calendar month.

| Param      | Required | Default | Constraint                  |
| ---------- | -------- | ------- | --------------------------- |
| `year`     | yes      | —       | 1900–2100                   |
| `month`    | yes      | —       | 1–12                        |
| `currency` | no       | `EUR`   | 3-letter ISO 4217 code      |
| `page`     | no       | `0`     | ≥ 0                         |
| `size`     | no       | `50`    | 1–200                       |

The response includes the page content, page metadata, and credit/debit totals
converted to `currency` (with the FX rates and rate date used).

### `GET /api/v1/transactions/{transactionId}`

Returns a single transaction owned by the caller. Returns **404** both when the
transaction does not exist and when it belongs to another customer — the two are
indistinguishable by design, so the existence of other customers' records is never
leaked.

Errors are returned as RFC 7807 `application/problem+json`.

Interactive docs: `/swagger-ui.html` · OpenAPI: [openapi.yaml](openapi.yaml) / `/v3/api-docs`

## Design notes

- **Money is always `BigDecimal`**, never `double`; stored as `NUMERIC(19,4)`.
  Conversions round HALF_UP at scale 2.
- **FX rates** are valued at the *current* date (`LocalDate.now()`), not the
  transaction's value date — totals reflect today's valuation of the month's
  activity. Rates are cached 5 min, retried up to 3×, and circuit-broken with a
  last-known-good fallback (`FxRateUnavailableException` only on a cold cache).
- **Query performance**: the primary access pattern (customer + month range,
  newest first) is served by a composite index on `(customer_id, value_date)`
  using a `BETWEEN` range scan — no full table scan regardless of table size.
- **Scaling**: the Kafka projector scales up to one replica per topic partition;
  the stateless API layer scales independently on HTTP traffic.

## Deployment

- [Dockerfile](Dockerfile) — multi-stage build, slim JRE, non-root user,
  container-aware heap sizing.
- [k8s/deployment.yaml](k8s/deployment.yaml) — Kubernetes manifest with
  liveness/readiness probes wired to the Actuator health groups.

## Tests

| Test                          | Type | What it covers                                              |
| ----------------------------- | ---- | ----------------------------------------------------------- |
| `TransactionQueryServiceTest` | unit | Totals, mixed-currency conversion, BigDecimal rounding, signs |
| `FxRateServiceTest`           | unit | Cache, circuit-breaker fallback, last-known-good rate       |
| `TransactionSecurityTest`     | slice| JWT/scope enforcement, IDOR prevention                      |
| `TransactionContractTest`     | slice| Request validation + response contract                      |
| `TransactionFlowIT`           | IT   | End-to-end Kafka → projection → API (Testcontainers + WireMock) |

Unit tests run under Surefire (`mvn test`); the integration test is an `*IT` class
run under Failsafe (`mvn verify`) and requires a Docker daemon for Testcontainers.

## Continuous integration

[CircleCI](.circleci/config.yml) runs `./mvnw clean verify` on every push — this
executes the unit tests **and** the Testcontainers integration test (real Kafka +
PostgreSQL containers, WireMock for the FX provider) on the `machine` executor.
JUnit results appear in the CircleCI *Tests* tab; the JaCoCo coverage report is
stored as a build artifact.

> **Setup:** connect this repository at https://app.circleci.com (Projects → Set
> Up Project → use the existing `.circleci/config.yml`). After the first green run,
> add the status badge here:
>
> ```
> [![CircleCI](https://dl.circleci.com/status-badge/img/gh/JimmyHCM/E_Banking_Transaction_API/tree/main.svg?style=svg)](https://app.circleci.com/pipelines/github/JimmyHCM/E_Banking_Transaction_API)
> ```
