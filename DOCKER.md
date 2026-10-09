# Docker execution and logging

## Architecture

```text
client -> localhost:8080 -> gateway-api -> http://orders-api:8081 -> orders-api
                               |                                  |
                           gateway-db                          orders-db
                           gateway_db                          orders_db
                        authentication data                   order data
```

The Compose stack has four services. `application` connects the APIs; `auth-data`
and `orders-data` are separate internal networks for each API and its PostgreSQL.
Gateway cannot directly reach the Orders database through its data network.
Both PostgreSQL instances are version 17 and have separate persistent volumes.
Services use `restart: unless-stopped` for process/daemon restarts. An unhealthy
healthcheck alone does not restart a running container.

Default host mappings:

| Service | Host access | Container port |
|---|---|---:|
| gateway-api | 127.0.0.1:8080 | 8080 |
| orders-api | No published port | 8081 |
| gateway-db | 127.0.0.1:5432 | 5432 |
| orders-db | 127.0.0.1:5433 | 5432 |

## Configuration

If a local `.env` already exists, keep its JWT secret. Otherwise copy the example
without committing a real secret:

```bash
cp .env.example .env
chmod 600 .env
openssl rand -base64 32
```

Paste the generated value into `JWT_SECRET` in `.env`. Do not regenerate it
independently for each API. Choose database passwords in this local file as well;
example credentials are fictitious development values. `GATEWAY_SEED_TEST_USER=true`
enables the assessment account `usuario` / `senha123`; it is opt-in and BCrypt
is persisted idempotently. Keep it disabled outside development/assessment.

Compose requires non-empty JWT/database secrets and overrides the local JDBC URLs
with `gateway-db` and `orders-db`, the Orders URL with `http://orders-api:8081`,
and Orders binding with `0.0.0.0`. These names are resolved by Docker DNS.
The local URLs in `.env` remain useful for Maven execution on the host.
Do not use plain `docker compose config` in shared logs because it expands secrets;
use `docker compose config --quiet` to validate.

## Build and start

Stop host applications occupying port 8080 before starting the complete stack.
If PostgreSQL already occupies 5432/5433, choose other published database ports
using GATEWAY_DB_PORT/ORDERS_DB_PORT and adjust local JDBC URLs accordingly.

```bash
docker compose config --quiet
docker compose build
docker compose up -d --wait --wait-timeout 300
docker compose ps
docker compose logs --tail=100 gateway-api orders-api
```

Each Dockerfile builds with Maven + JDK 21 and runs **verify**, including unit/
integration tests and JaCoCo thresholds. A shared, locked BuildKit Maven cache
reuses dependencies. The final image uses Temurin JRE 21, the executable JAR and
curl for healthchecks; it runs as unprivileged UID 10001. Build contexts are scoped
to each API; `.dockerignore` excludes local artifacts, secrets and editor files.

Databases use TCP `pg_isready` healthchecks. Orders starts after its database is
healthy; Gateway starts after its database and Orders HTTP are healthy.
API healthchecks use the existing public GET `/v3/api-docs`, so no health library
or extra endpoint is needed. They prove that HTTP responds after application
startup; they do not continuously check database connectivity. Keep OpenAPI
enabled with this Compose healthcheck, or change the probe if disabling it.

Gateway Swagger: http://localhost:8080/swagger-ui.html.
All normal order traffic uses the gateway. To temporarily open Orders Swagger
on loopback for local diagnostics:

```bash
docker compose -f docker-compose.yml -f docker-compose.debug.yml up -d --wait --wait-timeout 300
```

Orders Swagger is then http://localhost:8081/swagger-ui.html. Stop any host Orders
process using this port first. The override is optional and is not used by the
main stack. `EXPOSE` in a Dockerfile documents a port; publishing requires `ports`.

## Smoke check through the gateway

```bash
curl -sS -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"usuario","password":"senha123"}'
```

Save the returned token locally as TOKEN; do not paste it into application logs:

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"customerName":"Ana","customerEmail":"ana@example.com"}'
```

Expect 201 and a pending order with no items and zero total. Invalid credentials
or a missing token must return 401. This request exercises both APIs and the
Orders PostgreSQL database, rather than bypassing Gateway.

## Local execution and existing data

To run the APIs with Maven instead of in containers, start just the databases:

```bash
docker compose up -d gateway-db orders-db
```

Export `.env` variables (`set -a`, `source .env`, `set +a`) in each terminal and run
`mvn -f gateway-api/pom.xml spring-boot:run` or the Orders equivalent. With default
published ports, use `jdbc:postgresql://localhost:5432/gateway_db` and
`jdbc:postgresql://localhost:5433/orders_db`; Orders HTTP stays localhost:8081.
If your `.env` came from earlier stages, update ORDERS_DB_URL to port 5433.

The earlier gateway volume is retained. Orders now uses a new PostgreSQL service
and volume; orders previously stored in gateway-db's manually created orders_db
are not automatically migrated to orders-db. Export/import them separately if
needed. PostgreSQL initialization variables do not change passwords in an
existing volume; change the role password explicitly when rotating credentials.

`docker compose down` stops/removes containers and networks while retaining data.
`docker compose down -v` deletes the persistent volumes and their data; do not use
it merely to fix authentication errors.

## Logs

Spring's SLF4J backend writes timestamped INFO/WARN/ERROR messages to stdout.
Docker's local driver rotates each service's logs at 10 MB, retaining three files.
Application logs contain fixed event names, server-generated IDs, HTTP status and
exception type. They omit usernames, customer/product details, request/response
bodies, raw paths/queries, passwords, JWTs and JWT secrets. Security/web debugging,
SQL statements, binds/extracts and raw JDBC exception logging are disabled or
kept at INFO; safe application error events remain visible.

| Event | Meaning |
|---|---|
| AUTHENTICATION_SUCCEEDED | Login succeeded and token generation completed |
| AUTHENTICATION_FAILED | Login rejected; exception type only |
| ORDER_CREATED / ORDER_UPDATED / ORDER_DELETED | Order operation committed |
| ORDER_ITEM_CREATED / ORDER_ITEM_UPDATED / ORDER_ITEM_DELETED | Item operation committed |
| ORDERS_API_COMMUNICATION_FAILED | Connection, timeout or unreadable upstream response; 502/504 |
| ORDERS_API_SERVER_ERROR | Upstream returned 5xx; original response is preserved |
| REQUEST_FAILED | Internal failure; status/type, with no sensitive exception message |

Order events are registered with Spring transaction synchronization and emitted
only after commit. A failed commit/rollback must not produce a success event.
JUnit output-capture tests verify login/proxy secrecy, committed mutation events
and a forced database commit failure. No logging framework beyond Spring's
standard SLF4J setup is added.
