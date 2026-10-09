# Orders API — CRUD and JWT checkpoint

The API separates HTTP controllers, transactional business services, repositories,
and DTO mapping. Entities are never returned directly as JSON.

## Endpoints

| Method | Path | Success |
| --- | --- | --- |
| GET | /api/orders?page=0&size=10 | 200 |
| GET | /api/orders/{id} | 200 |
| POST | /api/orders | 201 + Location |
| PUT | /api/orders/{id} | 200 |
| DELETE | /api/orders/{id} | 204 |
| GET | /api/orders/{orderId}/items | 200 |
| POST | /api/orders/{orderId}/items | 201 + Location |
| GET | /api/orders/{orderId}/items/{itemId} | 200 |
| PUT | /api/orders/{orderId}/items/{itemId} | 200 |
| DELETE | /api/orders/{orderId}/items/{itemId} | 204 |

The specification requests complete CRUD for items but omits individual item
read/update/delete routes from its endpoint list. These three routes implement
that requirement explicitly.

Pagination uses Spring Data Pageable and queries the database by page, sorted
by ID. Page size defaults to 10 and must be between 1 and 100; page starts at 0.
List responses contain order summaries. Individual order responses include items.

Create an order:

```json
{"customerName":"Alice","customerEmail":"alice@example.com"}
```

Update an order:

```json
{"customerName":"Alice Updated","customerEmail":"alice@example.com","status":"CONFIRMED"}
```

Create or update an item:

```json
{"productName":"Keyboard","quantity":2,"unitPrice":19.90}
```

An order starts empty with PENDING status, a server-generated date and zero total.
Order updates replace customer name, email and status, preserving ID, date and items.
There are no additional status transition rules.

Quantity must be a positive integer. Prices must be positive, with at most
17 integer digits and 2 fractional digits, matching NUMERIC(19,2).
Subtotals and totals are calculated with BigDecimal. Calculated amounts exceeding
the column capacity are rejected. Unknown JSON fields are rejected, including
client-supplied IDs, creation dates, subtotals and totals.

Writes run in a transaction. Mutations lock the parent order to serialize changes
to the same order and prevent concurrent total updates from overwriting each other.
Items can only be accessed through their actual parent order.

## Security checkpoint

Orders is a Spring Security JWT Resource Server. It validates tokens emitted by
the gateway; it does not issue JWTs and has no login endpoint, user table or
password authentication. The application uses the existing Resource Server/Nimbus
support rather than a custom JWT filter or manual cryptographic implementation.

```text
Client -> Gateway validates JWT -> Orders validates the same JWT -> CRUD -> PostgreSQL
```

The gateway's WebClient proxy forwards the validated Bearer to Orders. See the
gateway README for client calls. Validation on both sides ensures that reaching the Orders service directly
does not grant access without valid credentials. In Express, this is equivalent
to installing JWT verification middleware in each service, not assuming that
traffic arriving at the second service has already been authenticated.

Orders binds to `127.0.0.1` by default using `ORDERS_SERVER_ADDRESS`, reducing
unnecessary LAN/public exposure during local development. Calls from the same
computer are still possible and must carry a valid JWT. Orders grants no browser
CORS access; frontend clients should call Gateway, which owns CORS configuration.
This does not prevent curl or another server from making calls.

For containers, explicitly set `ORDERS_SERVER_ADDRESS=0.0.0.0` on a private Docker
network and do not publish the Orders port publicly. Network boundaries/firewall
rules control which services can reach it; CORS and JWT alone do not prove that a
request came through Gateway. The Compose stack applies this configuration;
see [DOCKER.md](../DOCKER.md).

| Path | Security rule |
| --- | --- |
| `/api/orders/**` | `authenticated`, including all item operations |
| Swagger/OpenAPI GETs | Public documentation and UI resources |
| Other paths | `denyAll`, including `/auth/login` |

Default denial outside orders is a project decision, matching Gateway's approach.
Swagger/OpenAPI GETs are explicitly public. The assessment
requires authentication for orders, so no additional USER-only role restriction
has been invented. Signed `roles: ["USER"]` is converted to Spring authority
`ROLE_USER`; authentication name comes from `sub`.

Both APIs must receive the same **Base64-encoded JWT_SECRET** (at least 32 random
decoded bytes) and **JWT_ISSUER** (default `gateway-api`). There is no default signing
secret. The decoder accepts only HS256 and checks the signature, issuer, nonblank
subject, issued-at and expiration, plus not-before when present. It rejects missing required claims,
future issuance, expired tokens and unsigned tokens. Zero clock skew matches the
gateway's one-hour validity policy, including rejection at the expiration boundary.

Security is stateless: no login session is persisted. HTTP Basic, form login and
request caching and the default logout filter are disabled. CSRF is disabled because authentication uses only
an explicitly supplied Authorization Bearer header, without automatically sent
credentials such as an authentication cookie or HTTP Basic.
Stateless alone would not justify disabling CSRF if authentication used a cookie.
Bearer tokens in cookies or query parameters are ignored. Standard Spring Security
headers remain enabled; HSTS applies to secure requests, so production requires
HTTPS at its ingress. See Gateway's stage 10 security-review table for the audit.

Missing/invalid/expired Bearer returns `401 / UNAUTHORIZED` and `WWW-Authenticate:
Bearer`. An authenticated request to a denied path returns `403 / FORBIDDEN`.
Both use the same JSON error contract as MVC; JWTs and passwords are not logged.

MVC contract tests keep `@WithMockUser` to isolate controller validation and CRUD
behavior. `OrdersSecurityTest` does not mock authentication or the decoder: it
generates signed tokens, runs the real filters/decoder/converter and mocks only
the business service. PostgreSQL integration tests also send real signed Bearers.

## Running with Gateway tokens

The Orders database must exist before startup. As a PostgreSQL administrator:

```sql
CREATE ROLE orders_dev WITH LOGIN PASSWORD 'orders_dev';
CREATE DATABASE orders_db OWNER orders_dev;
```

These are fictitious development credentials. Override them via `ORDERS_DB_URL`,
`ORDERS_DB_USERNAME` and `ORDERS_DB_PASSWORD` when needed. The current Compose file
initializes both databases in separate PostgreSQL services; Orders is published
on localhost:5433 for host Maven execution.
Hibernate creates/updates mapped tables once connected, not the database itself.

The root `.env.example` includes both APIs. Copy/fill a local `.env` with a generated
secret once, then load that same file in both terminals. Do not generate a separate
key for each API. Spring Boot does not read `.env` automatically:

```bash
set -a
source .env
set +a
```

From the repository root, start Gateway in one terminal and Orders in another,
both with the same exported JWT variables and JDK 21:

```bash
# Gateway terminal
mvn -f gateway-api/pom.xml spring-boot:run

# Orders terminal
mvn -f orders-api/pom.xml spring-boot:run
```

Obtain the JWT from Gateway:

```bash
curl -sS -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"usuario","password":"senha123"}'
```

For this stage's direct validation check, paste the returned token into `<JWT>`:

```bash
curl -i 'http://localhost:8081/api/orders?page=0&size=10' \
  -H 'Authorization: Bearer <JWT>'
```

Expect `200` with valid credentials/database. Without the header or with an invalid
token, expect `401`. Normal client traffic uses Gateway on port 8080; this direct
call demonstrates Orders' independent JWT validation.

## Validation and errors

Request DTOs use Jakarta Bean Validation. Customer and product names must not be
blank. Customer email must be nonblank and valid. Quantity and unit price are
required and strictly positive. POST and PUT controllers use @Valid.

Errors use the same JSON contract in MVC and Spring Security filters:

```json
{
  "timestamp": "2026-10-06T19:00:00Z",
  "status": 404,
  "error": "Not Found",
  "code": "ORDER_NOT_FOUND",
  "message": "Order 10 not found",
  "path": "/api/orders/10"
}
```

Timestamps are generated by the server in UTC. Validation errors additionally
include an errors object mapping field names to validation messages. Rejected
values are not included. Query strings are not included in path.

| Status | Code | Meaning |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | Invalid DTO fields or constrained parameters |
| 400 | INVALID_REQUEST | Invalid JSON, unknown fields, parameter types, URL or headers |
| 400 | INVALID_ORDER_AMOUNT | Calculated amount exceeds storage capacity |
| 404 | ORDER_NOT_FOUND | The requested order does not exist |
| 404 | ORDER_ITEM_NOT_FOUND | The item does not exist within the requested order |
| 401 | UNAUTHORIZED | Authentication is missing or invalid |
| 403 | FORBIDDEN | An authenticated request is denied access |
| 500 | INTERNAL_ERROR | Unexpected internal failure |

Unexpected errors return a generic message without exception names, stack traces,
SQL or connection details. Application error logging records only a fixed event,
HTTP status and exception type. Spring Boot fallback error settings also exclude exception
details, binding errors and stack traces.

## Build and tests

Use JDK 21 and Maven:

```bash
mvn -B -f orders-api/pom.xml clean verify
```

This runs service, MVC, JWT decoder and security tests. They use their own random
test key and do not require a local `.env` or development PostgreSQL instance.
Full persistence integration tests run by default with an in-memory H2 database
in PostgreSQL compatibility mode. H2 is a test-scoped dependency only.
Set ORDERS_TEST_DB_URL, ORDERS_TEST_DB_USERNAME and ORDERS_TEST_DB_PASSWORD
to execute the same suite against a dedicated PostgreSQL database.

`OrdersSecurityTest` verifies missing/invalid/expired tokens, issuer/claim/signature
validation, mapping roles, accepting valid tokens, Bearer writes without CSRF,
stateless behavior, rejecting HTTP Basic and denial outside orders.
`JwtDecoderTest` checks the exact expiration boundary with a fixed clock.

The PostgreSQL suite requires an existing, dedicated test database. It uses
ddl-auto=create-drop, creating mapped tables at startup and dropping them at
shutdown. Never point it at a database whose data should be preserved.

For a local test database with development credentials:

```bash
export ORDERS_TEST_DB_URL=jdbc:postgresql://localhost:5433/orders_test_db
export ORDERS_TEST_DB_USERNAME=orders_dev
export ORDERS_TEST_DB_PASSWORD=orders_dev
mvn -B -f orders-api/pom.xml -Dtest=OrderPersistenceIntegrationTest test
```

The suite checks the full CRUD lifecycle, orphan removal, cascading order deletion,
parent-scoped item operations, pagination and rollback when the total overflows.

## Swagger / OpenAPI (etapa 11)

Com a aplicação em execução:

- Swagger UI: http://localhost:8081/swagger-ui.html
- Contrato JSON: http://localhost:8081/v3/api-docs
- Contrato YAML: http://localhost:8081/v3/api-docs.yaml

Os GETs da documentação e dos arquivos do Swagger são públicos; pedidos continuam
exigindo JWT. O esquema `bearerAuth` habilita o botão **Authorize**: cole somente
o token, sem escrever `Bearer`. O Swagger acrescenta esse prefixo ao header.
A autorização não é persistida no armazenamento local do navegador.

Obtenha o token executando `POST /auth/login` no Swagger da gateway com
`usuario` / `senha123` (usuário de teste precisa estar habilitado e inicializado).
A configuração do Swagger fica em `config/OpenApiConfig.java`; os controllers
continuam responsáveis apenas pelo fluxo HTTP. Os schemas são derivados dos DTOs.
Para desabilitar documentação numa implantação, configure
`springdoc.api-docs.enabled=false` e `springdoc.swagger-ui.enabled=false`.

São documentadas as dez operações de pedidos e itens, os DTOs de entrada/saída,
paginação (page >= 0; size entre 1 e 100), status, validações e erros padronizados.
Criação retorna 201 com Location; exclusão retorna 204 sem corpo.

Decisão para atender CRUD completo: também existem GET, PUT e DELETE
`/api/orders/{orderId}/items/{itemId}`, complementando a lista original da prova.

O Try it out desta interface chama Orders diretamente, no mesmo endereço da UI,
para diagnóstico local. Não há login aqui: use o token emitido pela gateway.
O fluxo normal de clientes deve usar a porta 8080 da gateway; a interface dela
permite testar listagem e criação pelo proxy. Orders permanece em loopback por
padrão e não recebe CORS público nesta etapa.

## Test coverage (stage 12)

```bash
mvn -B -f orders-api/pom.xml test
mvn -B -f orders-api/pom.xml verify
```

`test` runs unit and integration tests and generates the JaCoCo report at
`target/site/jacoco/index.html` (XML and CSV are also generated). `verify` runs
the tests and enforces at least **80% line coverage** and **70% branch coverage**
for this application independently. No production classes are excluded.
The JaCoCo agent is combined with the existing Mockito startup agent through
Surefire's late `@{argLine}` evaluation. Coverage instruments the code; it does
not replace assertions or prove that a test detects every regression.

The full testing matrix, test boundaries and PostgreSQL instructions are in
[TESTING.md](../TESTING.md).

## Docker and logging (stage 13)

The complete stack, Dockerfiles, internal URLs, ports, environment configuration,
healthcheck limits and logging events are described in [DOCKER.md](../DOCKER.md).
Build with `docker compose build` and start with
`docker compose up -d --wait --wait-timeout 300` from the repository root.
Both images use Java 21, run tests/coverage during the multi-stage build, and
execute the final JAR as a non-root user. Application logs never record credentials,
tokens or request/response bodies; Order mutation events are emitted after commit.
