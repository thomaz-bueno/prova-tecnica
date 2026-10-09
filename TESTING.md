# Test strategy

Each API is an independent Maven application. Run commands from the repository
root with JDK 21. Test keys are generated within the suite; a local `.env` is not
required. Security and validation remain active.

## Matrix

| Component | Behavior | Test type | Existing coverage | Stage 12 action |
|---|---|---|---|---|
| Orders service | Create/read/update/delete, missing orders | Unit, Mockito repositories | OrderServiceTest | Retain |
| Orders service/mapper | Pagination and response DTOs | Unit | OrderServiceTest | Retain |
| Orders items | CRUD, decimal subtotal, total recalculation, overflow | Unit | OrderServiceTest | Retain |
| Orders HTTP | Ten routes, validation, 400/404, 201/204, pagination | MVC integration | OrderControllerTest | Retain |
| Orders security | Real signed JWT, claims, expiration, 401/403, stateless | MVC integration and decoder unit | OrdersSecurityTest, JwtDecoderTest | Add malformed/weak configuration tests |
| Orders persistence | CRUD, cascade/orphans, parent isolation, pagination, transactional rollback | Full application integration | OrderPersistenceIntegrationTest, previously conditional | Execute by default; add update rollback and no-write cases |
| Gateway JWT | Real encoding/decoding, exact hour, expiration | Unit | JwtServiceTest | Retain |
| Gateway login/security | Real AuthenticationManager/BCrypt/JWT, valid/invalid credentials, CORS, headers, safe errors | MVC integration, mock user repository | AuthControllerTest | Retain |
| Gateway initializer | BCrypt, idempotence, explicit opt-in | Unit | TestUserInitializerTest | Retain |
| Gateway proxy | Ten routes; Authorization/method/path/query/body; upstream status/body; 502/504 | MVC and WebClient integration with fake transport | OrdersProxyControllerTest | Retain |
| Gateway configuration | Invalid origins, destination credentials/path/query, timeout bounds | Unit | GatewayCorsPropertiesTest; proxy configuration not directly tested | Add OrdersApiPropertiesTest |
| Gateway persistence | Real persisted hash/roles, login and idempotence | Full application integration | AuthPersistenceIntegrationTest, previously conditional | Execute by default |
| Both documentation | Generated schemas, routes, Swagger resources and security requirements | MVC integration | OpenApiDocumentationTest | Retain |
| Both coverage | Independent line/branch thresholds, full production scope | JaCoCo Maven report/check | Not configured before stage 12 | Enforce 80% lines and 70% branches |

## Boundaries

- Unit tests instantiate Java objects without starting Spring. Repository mocks
  isolate business rules; assertions check monetary results and public behavior.
- MVC integration uses MockMvc with the real security filter chain, validation,
  controllers, error handling and JSON serialization. Some tests replace the
  service/database boundary; these are not database tests.
- Persistence integration starts the full Spring application context with real
  repositories, Hibernate, transactions, authentication and MockMvc. The default
  database is embedded H2 in PostgreSQL compatibility mode, isolated from the
  development databases. No persistence test is silently skipped by default.
- H2 tests do not prove PostgreSQL-specific SQL, locking or concurrency behavior.
  The same suites can use dedicated PostgreSQL databases as described below.
- Proxy tests use a real WebClient with a test ExchangeFunction. Requests are
  serialized into Spring's MockClientHttpRequest, so method, raw URI, Bearer and
  body are inspected at the transport boundary. Controlled ClientResponse values
  and errors exercise codecs, status propagation and timeout handling. No real
  external Orders API or network port is required. DNS/TCP behavior is outside
  this test boundary.
- Money parsed from JSON in persistence assertions uses BigDecimal, including
  amounts near the numeric(19,2) limit. Tests must not lose precision themselves.

## Commands and reports

```bash
mvn -B -f gateway-api/pom.xml test
mvn -B -f orders-api/pom.xml test
mvn -B -f gateway-api/pom.xml verify
mvn -B -f orders-api/pom.xml verify
```

For a fresh measurement use `clean verify`. Surefire runs the `*Test` classes,
including the full integration suites, in `test`; `verify` repeats the test phase,
packages the application and checks the independent coverage thresholds.
JaCoCo produces `target/site/jacoco/index.html`, `jacoco.xml` and `jacoco.csv`
in each API. No application classes are excluded. The report displays line,
instruction and branch coverage; the build checks lines >= 80%, branches >= 70%.
Coverage is evidence of execution, not a substitute for meaningful assertions.

## PostgreSQL integration

Create **dedicated** empty databases, for example `gateway_test_db` and
`orders_test_db`, owned by the corresponding development roles. These tests use
`ddl-auto=create-drop` and recreate/drop mapped tables. Never use development or
production databases containing data that must be kept.

```bash
export GATEWAY_TEST_DB_URL='jdbc:postgresql://localhost:5432/gateway_test_db'
export GATEWAY_TEST_DB_USERNAME='gateway_dev'
export GATEWAY_TEST_DB_PASSWORD='orders-and-gateway-local-password-example'
export ORDERS_TEST_DB_URL='jdbc:postgresql://localhost:5433/orders_test_db'
export ORDERS_TEST_DB_USERNAME='orders_dev'
export ORDERS_TEST_DB_PASSWORD='orders-and-gateway-local-password-example'
```

Replace password examples locally with the credentials of those test databases;
no real secret is committed. Then run the same Maven test/verify commands. Unset
the six variables to return to embedded H2. JWT test keys remain independent of
the applications' environment key.

## Measured result — stage 12

Measured from fresh `clean verify` executions with JDK 21 and JaCoCo 0.8.15:

| API | Passed tests | Lines | Branches | Instructions |
|---|---:|---:|---:|---:|
| gateway-api | 118 | 95.10% | 79.73% | 96.06% |
| orders-api | 114 | 97.57% | 81.25% | 97.53% |

Both `test` and `clean verify` passed for each API, with no failures, errors or
skipped tests. Each `verify` printed `All coverage checks have been met`.
Persistence was executed on embedded H2, not PostgreSQL. The optional dedicated
PostgreSQL run is not included in these measured results.

## Logging verification — stage 13

Output-capture integration tests check login outcomes without username/password,
JWT or signing key; safe upstream 5xx/connection error logs; and committed order/
item events without customer or product fields. A temporary database CHECK
constraint forces an update to fail during commit: the row must remain PENDING
and no ORDER_UPDATED success event may be logged. The constraint is removed in
finally and is not a business rule or production schema change.

Fresh local `clean verify` results after these changes:

| API | Passed tests | Lines | Branches |
|---|---:|---:|---:|
| gateway-api | 120 | 95.25% | 80.77% |
| orders-api | 116 | 97.69% | 81.63% |

No skipped tests. Both JaCoCo checks passed. These are Maven/H2 results, not proof
of running containers. Compose config and required-secret behavior were verified;
Docker build in the agent environment was blocked by daemon socket permissions.

## Final audit — stage 16

Two new regression tests reproduce a malformed URL rejected by Spring Security's
firewall (`/api/orders//items`). Before the fix, both returned 400 without the
API's JSON error body. A `RequestRejectedHandler` now writes `400 / INVALID_REQUEST`
directly, preserving the firewall and preventing a secondary `/error` dispatch.
The tests verify the error contract and that no repository/service is called.

Fresh `clean verify` results with JDK 21, default H2 integration and all security
filters enabled:

| API | Passed tests | Lines | Branches | Instructions |
|---|---:|---:|---:|---:|
| gateway-api | 121 | 95.29% | 80.77% | 96.19% |
| orders-api | 117 | 97.71% | 81.63% | 97.66% |

No failures, errors or skipped tests. Both JaCoCo checks passed without production
class exclusions. These Maven results use H2 and a controlled WebClient transport,
not real inter-service DNS/TCP. Compose configuration validation passed for the
main stack and the optional debug override. Agent access to the Docker daemon was
denied, so the user executed the Docker commands locally: both images built with
`mvn verify`, all four containers became healthy, and 26 HTTP checks via Gateway
passed against the PostgreSQL stack. The temporary check covered login/security,
all ten order/item endpoints, pagination, recalculated totals and 400/404 errors,
then removed only its own order. See [AUDIT.md](AUDIT.md) for the complete checklist
and the distinction between agent execution and user-supplied runtime evidence.
