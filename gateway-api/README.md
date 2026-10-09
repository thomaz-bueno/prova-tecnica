# Gateway API — authentication, JWT, orders proxy and CORS

Java 21, Spring Boot 3.5, Spring Security and PostgreSQL. This stage implements
persistent users, BCrypt authentication, JWT issuance/validation and a WebClient
proxy to Orders and explicit, configurable browser CORS support.

## Request flow

```text
POST /auth/login
  -> AuthController (validates LoginRequest)
  -> AuthService
  -> AuthenticationManager / DaoAuthenticationProvider
  -> DatabaseUserDetailsService -> UserAccountRepository -> PostgreSQL
  -> PasswordEncoder.matches (BCrypt)
  -> JwtService / JwtEncoder
  -> LoginResponse

/api/orders/** requests
  -> Spring Security Bearer filter -> JwtDecoder
  -> signature, issuer, not-before and expiration validation
  -> SecurityContext with username and ROLE_USER
  -> OrdersProxyController -> OrdersProxyService -> WebClient
  -> Orders API Bearer filter -> JwtDecoder (same secret and issuer)
  -> Orders controller -> service -> repository -> PostgreSQL
  <- Orders status, response body and selected headers <- Gateway <- Client
```

The login route authenticates username/password and ignores a Bearer header sent
with it. Users/entities are never HTTP responses.

| Request | Security rule |
| --- | --- |
| `POST /auth/login` | `permitAll`; authenticates submitted credentials |
| `/api/orders/**` | `authenticated`; requires a valid Bearer JWT |
| Swagger/OpenAPI GETs | Public documentation and UI resources |
| Other paths/methods | `denyAll`; no implicit access to new endpoints |

The default-deny policy is our project decision. Authentication alone does not
grant access to every endpoint. Swagger/OpenAPI GETs already have an explicit
public rule. No extra role restriction is imposed on orders because
the assessment asks for `authenticated`. Unauthenticated orders requests are
rejected with 401 before the proxy runs. Authenticated ones are forwarded.

## Browser CORS

`GATEWAY_CORS_ALLOWED_ORIGINS` is a comma-separated list of exact origins, defaulting
to `http://localhost:5173` for development. For example:

```bash
export GATEWAY_CORS_ALLOWED_ORIGINS='http://localhost:5173,https://client.example'
```

Origins contain scheme, host and optional port, without paths or a trailing slash.
Wildcards, credential-bearing URLs, queries, fragments and invalid origins fail
startup. An empty list grants no cross-origin browser access.

`GatewayCorsProperties` binds/validates the list. `SecurityConfig` registers CORS
only for `/auth/login`, `/api/orders` and `/api/orders/**`, using Spring Security's
built-in CorsFilter before Bearer authentication. Methods are GET, HEAD, POST, PUT,
DELETE and OPTIONS; allowed request headers are Authorization, Content-Type and
Accept. Location is exposed to browser JavaScript. Cookie credential support is
disabled, and a successful preflight can be cached for 600 seconds.

A browser's OPTIONS preflight sends the desired method/header names, not the JWT.
An accepted preflight receives 200 locally in the gateway without calling Orders.
The actual orders request still requires a valid Bearer. We do not permit every
OPTIONS route. Unknown origins/methods/headers receive 403 from Spring's CORS
processor with its fixed `Invalid CORS request` body and no Allow-Origin header;
this protocol-level rejection happens before MVC's JSON exception handlers.
Authentication errors for allowed origins retain the normal JSON error contract
and CORS headers so the frontend can read them.

Malformed URLs/headers rejected by Spring Security's firewall return
`400 / INVALID_REQUEST` directly in the JSON error contract. The firewall remains
enabled; its rejection handler does not dispatch to the protected `/error` path.

From the repository root, with Gateway running, check a preflight:

```bash
curl -i -X OPTIONS http://localhost:8080/api/orders \
  -H 'Origin: http://localhost:5173' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: Authorization,Content-Type'
```

Expect 200 and `Access-Control-Allow-Origin: http://localhost:5173`. Repeat with
`Origin: https://untrusted.example` to check a 403. A normal GET with the allowed
Origin but without Authorization still returns 401. CORS grants browser access;
it does not authenticate users or prevent calls from curl/other servers.

In Express this corresponds to configuring `cors({ origin: [...], methods: [...],
allowedHeaders: [...], exposedHeaders: ['Location'], credentials: false })` before
the JWT middleware. Our client supplies Bearer explicitly and does not use cookies
or `credentials: 'include'` for authentication.

## Orders proxy

One generic mapping covers `/api/orders` and `/api/orders/**`, including all order
and item CRUD routes. Explicit controllers for every route would duplicate the
Orders contract and require gateway edits whenever its endpoints change. This
generic proxy is our project decision; it does not contain Order/OrderItem entities,
business DTOs, validation or pricing rules.

| Environment variable | Development default | Purpose |
| --- | --- | --- |
| `ORDERS_API_URL` | `http://localhost:8081` | Fixed Orders origin |
| `ORDERS_API_CONNECT_TIMEOUT` | `2s` | Maximum connection setup time |
| `ORDERS_API_TIMEOUT` | `5s` | Maximum time for the whole exchange, including body reading |

`OrdersApiProperties` binds these settings to a record. The URL must be an HTTP(S)
origin, without credentials, a path prefix, query or fragment. Both timeouts must
be positive. Misconfiguration fails startup rather than silently selecting a
different destination. The client cannot choose the upstream host.

`OrdersProxyController` obtains the validated `Jwt` principal from Spring Security,
the HTTP method, raw path/query, Content-Type, Accept and optional body bytes.
`OrdersProxyService` builds a URI using the configured origin and forwards the
same validated token as `Authorization: Bearer ...`. It preserves the method,
path, repeated/encoded query parameters and body bytes. It forwards only selected
request headers: client Host, cookies and connection headers are not copied.

`exchangeToMono` reads the status and body without turning HTTP 4xx/5xx into Java
exceptions. Actual responses from Orders retain their status and body, including
validation errors, 401/403, 404, 405, 500 and 503. A 204 retains an empty body.
Relevant response headers include Content-Type, Location, Allow, WWW-Authenticate,
Cache-Control, ETag, Last-Modified, Retry-After, Content-Language, Vary and Expires.
Transport headers and upstream cookies are not copied. A Location pointing to an
Orders resource at the configured origin becomes `/api/orders/...`, keeping the
client on the gateway when it follows the link.

| Failure before a complete response | Gateway result |
| --- | --- |
| Orders cannot be reached (connection refusal, DNS or transport failure) | `502 / ORDERS_API_UNAVAILABLE` |
| Connection or whole-exchange timeout | `504 / ORDERS_API_TIMEOUT` |
| Response exceeds the decoding buffer | `502 / ORDERS_API_BAD_RESPONSE` |

These errors use the existing JSON error contract and safe fixed messages. Neither
logs nor responses include the transport exception message or full JWT.

Automatic redirects and retries are disabled: redirects must not forward a Bearer
to another destination, and retrying a write could create duplicate operations.
This is a small MVC proxy: `block()` waits on the MVC request thread with a bounded
timeout. WebClient uses Reactor Netty connection pooling, but this does not make
the whole application reactive. Responses are buffered with a 2 MiB decoding limit;
large files/streaming and more demanding traffic would need a different design.
Normal orders responses should use pagination to keep payloads bounded.

In Node.js this resembles an Express route calling `fetch`/`axios`, forwarding
method, URL, headers and body, then using `res.status(...).send(...)`. WebClient
returns a Reactor `Mono` rather than a Promise. The explicit exchange API lets us
preserve HTTP error responses, like inspecting `fetch`'s `response.status` or using
an Axios `validateStatus` policy that accepts the upstream status.

## Database

The application uses `gateway_users` and `gateway_user_roles`, avoiding the reserved
name `user`. Username has a unique constraint; IDs are generated by PostgreSQL.
`password_hash` contains only BCrypt hashes. `@ElementCollection` maps the role set
to a separate table, and `@EntityGraph` loads the roles with the user lookup.

Defaults below are fictitious local-development credentials. As a PostgreSQL
administrator, create the local role and database once:

```sql
CREATE ROLE gateway_dev WITH LOGIN PASSWORD 'gateway_dev';
CREATE DATABASE gateway_db OWNER gateway_dev;
```

Override the development defaults through environment variables when necessary:

| Variable | Default |
| --- | --- |
| `GATEWAY_DB_URL` | `jdbc:postgresql://localhost:5432/gateway_db` |
| `GATEWAY_DB_USERNAME` | `gateway_dev` |
| `GATEWAY_DB_PASSWORD` | `gateway_dev` |
| `GATEWAY_DDL_AUTO` | `update` |
| `GATEWAY_SEED_TEST_USER` | `false`; `.env.example` explicitly enables it for development |
| `GATEWAY_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` |
| `JWT_SECRET` | Required; Base64 encoding of at least 32 random bytes |
| `JWT_ISSUER` | `gateway-api` |

`ddl-auto=update` is a development choice. A production deployment should use a
controlled schema migration and disable the demonstration account initializer.

When `GATEWAY_SEED_TEST_USER=true`, the initializer creates `usuario` with password `senha123` and role
`USER` if that username does not exist. BCrypt encodes the password before saving.
Restarting does not duplicate the account or reset an existing hash/password/role.
The database generates the ID; an empty database will normally start at 1, but the
code does not force that value. The username constraint prevents duplicate accounts;
the bootstrap assumes one instance initializes the demonstration user at a time.
Set `GATEWAY_SEED_TEST_USER=false` outside demonstration/development environments.

## JWT and security decisions

- HS256 is a project decision consistent with the required shared secret. JWT
  signing and validation use Spring Security's existing Nimbus support, without
  an additional JWT dependency or custom authentication filter.
- `sub` is the authenticated username and `roles` comes from persisted roles, not
  from the login request. `USER` becomes the Spring authority `ROLE_USER`.
- `iat` and `exp` differ by exactly 3600 seconds. The decoder has zero clock skew
  and rejects a token at its expiration boundary; deployment clocks must agree.
- The gateway requires the expected issuer, nonblank subject, issued-at, expiration
  and valid signature. Tokens issued in the future are rejected.
- Authentication is stateless. Form login, HTTP Basic, session persistence and
  request caching are disabled. CSRF is disabled because authentication uses only
  an explicitly supplied Bearer header, with no automatically sent credentials.
- BCrypt's default work factor is 10. Each encoded password receives a random salt.
  Passwords exceeding its 72-byte limit are rejected with the same generic 401 as
  other incorrect credentials rather than being silently truncated.
- Login responses use `Cache-Control: no-store`. Application logs do not include
  passwords, complete JWTs, exception messages, or request bodies.
- JWTs are signed, not encrypted; do not put secrets in their claims.

Generate a development secret in your shell using OpenSSL; do not commit its value:

```bash
export JWT_SECRET="$(openssl rand -base64 32)"
```

Reuse that same environment value in the orders API. Both applications validate
the token independently. Changing the secret invalidates existing tokens.

The repository root contains `.env.example` with an empty `JWT_SECRET`, so no
usable shared key is committed. The existing `.gitignore` ignores `.env` and
`.env.*`, with an exception for `.env.example`. Spring Boot does not automatically
read dotenv files. To use one, copy the template to `.env`, fill in the generated
secret and your local values, then export them before running Maven:

```bash
cp .env.example .env
chmod 600 .env
# Edit .env and fill JWT_SECRET with your locally generated Base64 secret.
set -a
source .env
set +a
```

### Understanding the JWT

An HS256 signed token consists of `header.payload.signature`:

- **Header:** Base64URL-encoded JSON identifying the algorithm and token type,
  for example `{"alg":"HS256","typ":"JWT"}`.
- **Payload:** Base64URL-encoded JSON claims. `sub` identifies the user, `roles`
  carries permissions, `iat` is issued-at, `nbf` is not-before, `exp` is expiration,
  and `iss` identifies the issuer. JWT timestamps use seconds since the Unix epoch.
- **Signature:** produced by the JWT library using the header, payload and shared
  key. Changing either JSON part invalidates the signature without knowledge of
  the key. Base64URL decoding alone does not verify a token.

Signing provides integrity/authenticity, not confidentiality. Anyone holding the
token can read its header/payload, so passwords and password hashes must never be
claims. Encryption is what hides content; this project uses signed JWTs, not JWE.

A stateless API validates the token on each request instead of retrieving a
server-side login session. The decoded principal goes into `SecurityContext` for
that request only. JWT does not eliminate database access for business operations.
Here, password/role changes do not revoke already-issued tokens; absent another
revocation mechanism, they remain valid until expiration or key replacement.

In Node.js, `JwtEncoder.encode` corresponds to `jsonwebtoken.sign`, while
`JwtDecoder.decode` performs verification/validation like `jsonwebtoken.verify`.
The Bearer filter is like Express authentication middleware, and `SecurityContext`
plays the role of `req.user`. With this project's Base64 environment convention,
the equivalent signing call is:

```javascript
const token = jwt.sign(
  { roles: ["USER"] },
  Buffer.from(process.env.JWT_SECRET, "base64"),
  {
    algorithm: "HS256",
    issuer: "gateway-api",
    subject: "usuario",
    expiresIn: 3600,
  }
);
```

`jsonwebtoken` adds `iat` by default. Its `decode` function only reads claims;
use `verify` to validate a token. Similarly, our Spring decoder is configured to
verify signatures and claims rather than merely decode Base64URL.

## Build and run

Run from the repository root:

```bash
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f /usr/bin/javac)")")"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -f gateway-api/pom.xml clean verify
```

Build/unit/MVC tests do not require a development database or `JWT_SECRET`. Tests
generate their own temporary secrets and retain the real Spring Security filters.

To start the applications, both PostgreSQL databases/users must already exist.
The root Docker Compose initializes separate gateway and Orders PostgreSQL services;
run `docker compose up -d gateway-db orders-db` for host Maven execution. Orders
uses localhost:5433 by default; see [DOCKER.md](../DOCKER.md). Configure `.env` as described above,
then load the same file in each terminal so the signing key and issuer agree.
Use Java 21 in both terminals.

First terminal, from the repository root:

```bash
set -a
source .env
set +a
mvn -f gateway-api/pom.xml spring-boot:run
```

Second terminal, also from the repository root:

```bash
set -a
source .env
set +a
mvn -f orders-api/pom.xml spring-boot:run
```

Gateway listens on port 8080 and Orders on 8081. Login with:

```bash
curl -i -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"usuario","password":"senha123"}'
```

Successful response (`200`):

```json
{"token":"<JWT>","tokenType":"Bearer","expiresIn":3600}
```

Incorrect username/password both return `401` with the same response:

```json
{
  "timestamp": "2026-10-06T12:00:00Z",
  "status": 401,
  "error": "Unauthorized",
  "code": "INVALID_CREDENTIALS",
  "message": "Invalid username or password",
  "path": "/auth/login"
}
```

Invalid input returns `400 / VALIDATION_ERROR` with a field-to-message `errors`
map. Invalid/missing Bearer tokens on protected routes return `401 / UNAUTHORIZED`.
Access denied uses `403 / FORBIDDEN`. Unexpected infrastructure errors use a generic
`500 / INTERNAL_ERROR`, with no sensitive exception details in the response.

| Situation | Result |
| --- | --- |
| Missing Bearer on a protected route | `401 / UNAUTHORIZED` |
| Malformed, unsigned, altered or incorrectly signed JWT | `401 / UNAUTHORIZED` |
| Expired JWT, including the exact expiration boundary | `401 / UNAUTHORIZED` |
| Wrong issuer or missing required claims | `401 / UNAUTHORIZED` |
| Valid JWT on a path denied by the default policy | `403 / FORBIDDEN` |

Call orders through the gateway using curl (replace `<JWT>` with the login token).
Both applications and their PostgreSQL databases must be running with the same
`JWT_SECRET` and `JWT_ISSUER`:

```bash
# 401: no token.
curl -i http://localhost:8080/api/orders

# 401: malformed token.
curl -i http://localhost:8080/api/orders -H 'Authorization: Bearer invalid-token'

# Forwarded to Orders: real pagination at the repository.
curl -i 'http://localhost:8080/api/orders?page=0&size=10' \
  -H 'Authorization: Bearer <JWT>'

# Create an order through the gateway.
curl -i -X POST http://localhost:8080/api/orders \
  -H 'Authorization: Bearer <JWT>' \
  -H 'Content-Type: application/json' \
  -d '{"customerName":"Ana","customerEmail":"ana@example.com"}'

# Replace <ORDER_ID> with the returned id; Orders calculates subtotal/total.
curl -i -X POST 'http://localhost:8080/api/orders/<ORDER_ID>/items' \
  -H 'Authorization: Bearer <JWT>' \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Keyboard","quantity":2,"unitPrice":100.50}'

# 403: authenticated, but this path is denied by the default policy.
curl -i http://localhost:8080/unconfigured -H 'Authorization: Bearer <JWT>'
```

## Security review (stage 10)

| Finding | Status | Evidence / decision |
| --- | --- | --- |
| Browser CORS | CORRIGIDO | Explicit configurable origins/methods/headers; preflight before JWT; no wildcard or cookie credentials. Real requests remain authenticated. |
| Known demonstration account | CORRIGIDO | Disabled by default; development opts in with `GATEWAY_SEED_TEST_USER=true`. BCrypt/idempotency remain intact. |
| Unnecessary Orders network exposure | CORRIGIDO | Orders binds to `127.0.0.1` by default; it still validates every JWT. |
| Default logout endpoint bypassing route policy | CORRIGIDO | Regression tests reproduced 302 redirects from /logout in both APIs. Default logout is disabled; it now follows denyAll (401 without JWT, 403 with valid JWT). |
| CSRF / sessions | OK | Bearer is explicitly supplied in Authorization; cookies, Basic and form login are not authentication mechanisms. Both APIs use STATELESS and disable request caching. |
| Password storage | OK | BCrypt with random per-password salt and work factor 10; only hashes are persisted. Inputs over 72 UTF-8 bytes receive generic invalid-credentials errors. |
| JWT key and expiry | OK | Required Base64 key with at least 32 decoded bytes; `.env` is ignored. HS256 signature and issuer/claims validated in both APIs; `exp - iat = 3600` seconds with zero clock skew. |
| 401 / 403 / roles | OK | Missing/invalid Bearer is 401; an authenticated request to a denied path is 403. Persisted USER becomes ROLE_USER; login input cannot assign roles. Orders requires authentication as specified. |
| Headers | OK | Spring defaults include nosniff, frame DENY and no-store; HSTS is emitted for secure requests. X-XSS-Protection=0 disables the obsolete browser filter; it does not replace validation or frontend XSS protection. |
| Validation / mass assignment | OK | Controllers use validated request DTOs and explicit mapping, never bind JPA entities. Unknown JSON fields such as id, roles and totals are rejected where not part of the request contract. |
| SQL injection | OK | Derived repository queries and fixed JPQL bind parameters rather than concatenate user strings. JPA does not make manually concatenated JPQL/native SQL safe. |
| Logs / errors | OK | Application code does not log bodies, passwords, full JWTs or exception messages; internal errors use fixed messages without traces. Login failures do not distinguish unknown users from wrong passwords. |
| Production transport / network | ATENÇÃO | Deploy with HTTPS. Orders belongs on a private network with no publicly published port; loopback limits local network exposure but does not authenticate the calling service. |
| Production accounts / database | ATENÇÃO | The local `.env` opts into the assessment account. Disable the seed in production and provision production accounts separately; disabling the initializer does not remove an already stored account. Use dedicated DB roles with minimal privileges and production credentials/schema management. |
| Login abuse / revocation | ATENÇÃO | Rate limiting for login should be configured at the ingress before public deployment. Already-issued JWTs remain valid for one hour after a password/role change; no revocation system is required or implemented. |

The default logout filter is disabled in both applications. There is no login
session to invalidate, and server-side session logout does not revoke a signed
Bearer. A client can discard its local token; an already-issued token remains
valid until expiration. `/logout` is not an API endpoint and follows denyAll.

STATELESS alone is not a reason to disable CSRF: even a stateless JWT cookie can
be automatically attached by the browser. If cookie-based authentication or HTTP
Basic is introduced, revisit CSRF protection. Our header-only Bearer requests do
not carry automatically attached authentication credentials. Like an Express
middleware, the security chain validates each request and sets a request-scoped
principal instead of restoring an `express-session` login.

This is a source/configuration review with automated behavior tests, not a claim
of a production penetration test or dependency-vulnerability scan. No additional
authentication scheme, role hierarchy, authorization ownership rule or library
was introduced.

## Tests

- `AuthControllerTest`: real controller, authentication manager, user-details
  service, BCrypt, encoder, decoder and security filters; only the database
  repository is mocked. Covers valid/invalid login, request validation, roles,
  stateless Bearer requests, invalid signatures/claims and safe error responses.
  Orders fixtures exist only in tests. The 403 check uses real security-filter
  denial on an unconfigured route, without manually throwing a forbidden exception.
- `JwtServiceTest`: real signing/verification with a fixed clock; verifies exactly
  one-hour validity and rejection at expiration, plus invalid secret configuration.
- `TestUserInitializerTest`: verifies BCrypt encoding and preservation of an
  existing account, mocking only the repository. Also checks the application's
  default disables the seed and explicit development configuration enables it.
- `GatewayCorsPropertiesTest`: rejects wildcard and non-origin configuration.
- CORS/security tests retain real filters and JWT validation; cover preflight,
  configured/unknown origins, rejected methods/headers, readable 401 responses,
  cookies/query tokens being ignored, default security headers, and a successful
  proxied response exposing Location without forwarding Origin to Orders.
- `OrdersProxyControllerTest`: calls the gateway with real signed JWTs and its
  security filters, controller, proxy service and WebClient. Replaces the external
  HTTP transport with an ExchangeFunction fixture; captures encoded requests and
  provides downstream responses. Covers all ten required routes, raw queries,
  method/body/Bearer preservation, login-to-proxy flow, response status/headers,
  safe 502, 504, and rejection before forwarding without a valid token. The login
  scenario mocks only the user repository for persistence. These tests do not
  claim to start two HTTP servers or execute Orders persistence.
- `AuthPersistenceIntegrationTest`: full application and real JPA persistence (H2 by default), including
  persisted hashes/roles, idempotent initialization and login with persisted data.

Persistence tests now run by default against an in-memory H2 database in
PostgreSQL compatibility mode. H2 is test scoped; the application remains PostgreSQL.
To run the same suite against PostgreSQL, set `GATEWAY_TEST_DB_URL`, username and
password. Create a separate
test database first, for example `gateway_test_db` owned by `gateway_dev`, then:

```bash
export GATEWAY_TEST_DB_URL='jdbc:postgresql://localhost:5432/gateway_test_db'
export GATEWAY_TEST_DB_USERNAME='gateway_dev'
export GATEWAY_TEST_DB_PASSWORD='gateway_dev'
mvn -B -f gateway-api/pom.xml clean verify
```

The integration test uses **create-drop**: it recreates and drops its mapped
tables. Always point it to a dedicated test database. With the variable absent,
the tests execute against H2 instead of being skipped.

The Maven Mockito agent configuration matches the orders API and enables mocking
on Java 21 without runtime self-attachment. No dependencies were added for the proxy.
OpenAPI is configured in stage 11, JaCoCo in stage 12 and Docker execution in
stage 13; see [DOCKER.md](../DOCKER.md).

## Swagger / OpenAPI (etapa 11)

Com a aplicação em execução:

- Swagger UI: http://localhost:8080/swagger-ui.html
- Contrato JSON: http://localhost:8080/v3/api-docs
- Contrato YAML: http://localhost:8080/v3/api-docs.yaml

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

A gateway documenta login e dois exemplos executáveis do proxy: GET e POST
`/api/orders`. A ausência dos demais endpoints nessa interface não limita o
roteamento: todas as rotas de pedidos e itens são encaminhadas, preservando
método, path, query, body, Bearer JWT e status/body da resposta. Os schemas de
pedidos aqui são genéricos para evitar duplicar o contrato de negócio.
O contrato completo está no Swagger de Orders. 4xx/5xx da API 2 são preservados;
falhas de conexão/resposta ilegível geram 502 e timeout gera 504.

## Test coverage (stage 12)

```bash
mvn -B -f gateway-api/pom.xml test
mvn -B -f gateway-api/pom.xml verify
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
