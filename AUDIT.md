# Auditoria final da prova técnica — etapa 16

Data: 9 de outubro de 2026.

A análise combina inspeção dos arquivos entregues, testes de regressão,
execuções novas de Maven/JaCoCo e validação da configuração do Compose.
Resultados de etapas anteriores não foram usados para afirmar que os containers
atuais iniciam. O usuário executou os comandos Docker e a verificação HTTP na
máquina local e forneceu as saídas nesta etapa. A implantação na EC2 não faz
parte desta auditoria.

## Critérios de classificação

- **PASS:** implementação encontrada e evidência adequada ao requisito. Para
  comportamento HTTP, foram utilizados testes com filtros reais de segurança;
  para persistência, contexto Spring completo e JPA/Hibernate com H2.
- **FAIL:** requisito ausente ou comportamento incorreto demonstrado e ainda
  não corrigido.
- **PARCIAL:** implementação presente, mas falta comprovar a execução necessária.
  H2 não comprova PostgreSQL; transporte simulado não comprova DNS/TCP entre
  dois processos; `docker compose config` não comprova build ou inicialização.

Os **59 requisitos oficiais receberam PASS** após as correções e a confirmação
de execução com Docker/PostgreSQL. Não restou um defeito demonstrado de código
sem correção. Na verificação adicional de secrets, não foi possível auditar
o histórico de commits: `git` não reconhece um repositório neste ambiente
(o diretório `.git` acessível está vazio). Isso não foi convertido em um PASS artificial.

## Correções aplicadas

Na inspeção inicial, **erros consistentes** recebeu PARCIAL: duas regressões
novas comprovaram que `/api/orders//items` retornava 400 sem Content-Type/JSON
do contrato de erro. Os testes falharam antes da alteração nas duas aplicações
com `Content type not set`.

O firewall continua rejeitando a URL. O handler agora escreve diretamente
`400 / INVALID_REQUEST` com mensagem fixa, em vez de deixar um `sendError`
provocar um despacho secundário para `/error`. Esse despacho também passa
pela autorização do Spring Security e poderia mascarar o erro original.
O tratamento usa a interface do próprio Spring Security; nenhuma regra de
autenticação, validação ou firewall foi relaxada.

Arquivos de código alterados:

- [Gateway SecurityConfig](gateway-api/src/main/java/com/example/gateway/config/SecurityConfig.java): registra o `RequestRejectedHandler` via `WebSecurityCustomizer`.
- [Gateway SecurityErrorHandler](gateway-api/src/main/java/com/example/gateway/security/SecurityErrorHandler.java): produz o mesmo DTO de erro para a rejeição do firewall.
- [Orders SecurityConfig](orders-api/src/main/java/com/example/orders/config/SecurityConfig.java): registra o handler equivalente.
- [Orders SecurityErrorHandler](orders-api/src/main/java/com/example/orders/security/SecurityErrorHandler.java): responde diretamente com o contrato JSON.
- [AuthControllerTest](gateway-api/src/test/java/com/example/gateway/controller/AuthControllerTest.java) e [OrdersSecurityTest](orders-api/src/test/java/com/example/orders/security/OrdersSecurityTest.java): método `rejectsMalformedPathsWithTheJsonErrorContractBeforeAuthentication`; verificam 400, código, mensagem, path e ausência de chamada ao repository/service.

Os dois testes passaram após a correção e continuam na suíte completa.
A documentação das duas APIs e os dados de cobertura do README foram
atualizados. O README de Orders também passou a esclarecer que `nbf` é validado
**quando presente**; o decoder não exige esse claim, embora a gateway o emita.

Referência do comportamento de autorização de despachos:
[Spring Security — Authorize HTTP Requests](https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html).

## Resultados executados

O Maven foi executado com JDK 21, usando um cache Maven gravável em `/tmp`,
em modo offline por restrições deste ambiente. Esse ajuste apenas muda o local
das dependências; os POMs e os limites de cobertura são os do projeto.

```bash
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f /usr/bin/javac)")")"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -o -B -ntp -Dmaven.repo.local=/tmp/prova-tecnica-m2 -f gateway-api/pom.xml clean verify
mvn -o -B -ntp -Dmaven.repo.local=/tmp/prova-tecnica-m2 -f orders-api/pom.xml clean verify
```

| API | Build | Testes | Falhas / erros / ignorados | Linhas | Branches | Instruções |
|---|---|---:|---|---:|---:|---:|
| Gateway | PASS | 121 | 0 / 0 / 0 | 95,29% (364/382) | 80,77% (126/156) | 96,19% |
| Orders | PASS | 117 | 0 / 0 / 0 | 97,71% (384/393) | 81,63% (80/98) | 97,66% |

`clean verify` compilou, executou os testes, empacotou os JARs e aplicou JaCoCo.
Ambas as execuções imprimiram `All coverage checks have been met` e
`BUILD SUCCESS`. O limite é 80% de linhas e 70% de branches em cada API;
nenhuma classe de produção foi excluída. Não foi necessário repetir `test`
separadamente porque essa fase já é executada por `verify`.

Relatórios produzidos:

- [Gateway JaCoCo HTML](gateway-api/target/site/jacoco/index.html) e [XML](gateway-api/target/site/jacoco/jacoco.xml).
- [Orders JaCoCo HTML](orders-api/target/site/jacoco/index.html) e [XML](orders-api/target/site/jacoco/jacoco.xml).
- Resultados Surefire: `gateway-api/target/surefire-reports` e `orders-api/target/surefire-reports`.

Esses artefatos de `target` são gerados pelo build e ficam ignorados pelo Git.

## Docker e fluxo real confirmados nesta etapa

O ambiente do agente tentou `docker compose build` após a correção e recebeu
negação de acesso ao socket do daemon. Por isso, o usuário executou os comandos
na máquina local e enviou a saída:

- `docker compose build`: **FINISHED**, 31/31 etapas, 2/2 imagens construídas.
  As etapas `RUN mvn -B -ntp verify` dos dois Dockerfiles foram efetivamente
  executadas, incluindo testes e verificação de cobertura.
- `docker compose up -d --wait --wait-timeout 300`: quatro containers **Healthy**.
- `docker compose ps`: gateway publicada em `127.0.0.1:8080`, Orders sem porta
  publicada, ambos os PostgreSQL e ambas as APIs saudáveis.
- `python3 /tmp/stage16-runtime-check.py`: **SUCCESS**, 26 chamadas HTTP somente
  pela gateway, com os dez endpoints de pedidos/itens e login exercitados.

O script foi preparado pelo agente em `/tmp`, fora dos arquivos de entrega,
e executado pelo usuário. Não imprimiu JWT nem senha. Criou um único pedido
de auditoria, adicionou/alterou/removeu seus itens e excluiu somente esse pedido,
preservando os dados que já existiam.

| Comportamento de runtime | Resultado |
|---|---|
| Sem token e token inválido | PASS — 401 |
| Login inválido/válido, Bearer e expiresIn | PASS — 401/200, Bearer/3600 |
| Autenticado em rota negada | PASS — 403 |
| URL malformada e DTO inválido | PASS — 400 com os códigos e campos esperados |
| Criar, buscar, listar com paginação, atualizar e excluir pedido | PASS — 201/200/204 |
| Listar, criar, buscar individual, atualizar e excluir itens | PASS — 201/200/204 |
| Subtotal e total após criação/alteração/exclusão | PASS — 39,80 → 45,75 → 55,75 → 45,75 → 0,00 |
| Item/pedido excluído | PASS — 404, ORDER_ITEM_NOT_FOUND / ORDER_NOT_FOUND |
| Location relativo preservando acesso pela gateway | PASS — `/api/orders/{id}` e `/api/orders/{id}/items/{itemId}` |

Essa execução comprova comunicação HTTP entre containers, JWT emitido pela
gateway aceito por Orders e o ciclo CRUD com PostgreSQL. Não substitui testes
de carga ou testes de concorrência específicos de PostgreSQL; as suítes Maven
relatadas acima continuam sendo as execuções padrão com H2.

## Checklist final — geral

| Requisito | Resultado | Evidência e limite |
|---|---|---|
| Java | PASS | [Gateway POM](gateway-api/pom.xml) e [Orders POM](orders-api/pom.xml): `java.version=21`; ambas compiladas com JDK 21. |
| Spring Boot 3.x | PASS | Os dois POMs usam `spring-boot-starter-parent:3.5.16`; testes iniciam contextos Spring Boot. |
| Spring Security | PASS | [Gateway SecurityConfig](gateway-api/src/main/java/com/example/gateway/config/SecurityConfig.java) e [Orders SecurityConfig](orders-api/src/main/java/com/example/orders/config/SecurityConfig.java); filtros permanecem ativos nos testes. |
| JWT | PASS | [Gateway JwtConfig](gateway-api/src/main/java/com/example/gateway/config/JwtConfig.java), [JwtService](gateway-api/src/main/java/com/example/gateway/security/JwtService.java) e [Orders JwtConfig](orders-api/src/main/java/com/example/orders/config/JwtConfig.java); assinatura e decodificação reais nos testes. |
| JPA/Hibernate | PASS | [Order](orders-api/src/main/java/com/example/orders/entity/Order.java), [OrderItem](orders-api/src/main/java/com/example/orders/entity/OrderItem.java), [UserAccount](gateway-api/src/main/java/com/example/gateway/entity/UserAccount.java); suítes de persistência executam Hibernate e transações reais sobre H2. |
| Spring Data JPA | PASS | [OrderRepository](orders-api/src/main/java/com/example/orders/repository/OrderRepository.java), [OrderItemRepository](orders-api/src/main/java/com/example/orders/repository/OrderItemRepository.java), [UserAccountRepository](gateway-api/src/main/java/com/example/gateway/repository/UserAccountRepository.java) estendem `JpaRepository`; usados nas integrações. |
| PostgreSQL | PASS | Driver nos dois POMs, URLs PostgreSQL em ambos os `application.yml`, dois serviços `postgres:17` no [Compose](docker-compose.yml); containers saudáveis e ciclo CRUD real confirmado pelo usuário nesta etapa. As suítes automatizadas padrão usam H2. |
| Duas APIs REST | PASS | Dois POMs, [GatewayApiApplication](gateway-api/src/main/java/com/example/gateway/GatewayApiApplication.java), [OrdersApiApplication](orders-api/src/main/java/com/example/orders/OrdersApiApplication.java), controllers e configurações independentes nas portas 8080/8081; contratos HTTP exercitados por MockMvc. |
| Comunicação entre APIs | PASS | [OrdersProxyService](gateway-api/src/main/java/com/example/gateway/service/OrdersProxyService.java) e [OrdersProxyControllerTest](gateway-api/src/test/java/com/example/gateway/controller/OrdersProxyControllerTest.java) comprovam o contrato WebClient; 26 chamadas reais via gateway confirmaram HTTP entre os containers e CRUD nesta etapa. |

## Checklist final — API 1

| Requisito | Resultado | Evidência |
|---|---|---|
| POST /auth/login | PASS | [AuthController](gateway-api/src/main/java/com/example/gateway/controller/AuthController.java); testes de login e persistência verificam 200 e token. |
| Username/password | PASS | [LoginRequest](gateway-api/src/main/java/com/example/gateway/dto/LoginRequest.java), [AuthService](gateway-api/src/main/java/com/example/gateway/service/AuthService.java), [DatabaseUserDetailsService](gateway-api/src/main/java/com/example/gateway/security/DatabaseUserDetailsService.java); AuthenticationManager e BCrypt reais nos testes. |
| JWT válido por 1 hora | PASS | [JwtService](gateway-api/src/main/java/com/example/gateway/security/JwtService.java): `exp = iat + 3600s`; [JwtServiceTest](gateway-api/src/test/java/com/example/gateway/security/JwtServiceTest.java) verifica intervalo exato e rejeição no instante de expiração, sem tolerância extra. |
| Validação JWT nas requests protegidas | PASS | [SecurityConfig](gateway-api/src/main/java/com/example/gateway/config/SecurityConfig.java), [JwtConfig](gateway-api/src/main/java/com/example/gateway/config/JwtConfig.java), [AuthControllerTest](gateway-api/src/test/java/com/example/gateway/controller/AuthControllerTest.java); sem token, assinatura inválida, claims inválidos e expirados são rejeitados. |
| Proxy para API 2 | PASS | [OrdersProxyController](gateway-api/src/main/java/com/example/gateway/controller/OrdersProxyController.java), [OrdersProxyService](gateway-api/src/main/java/com/example/gateway/service/OrdersProxyService.java); contrato testado sem duplicar domínio e fluxo real confirmado pelo usuário via gateway. |
| /api/orders/** roteado | PASS | Mapeamentos genéricos `/api/orders` e `/api/orders/**`; [OrdersProxyControllerTest](gateway-api/src/test/java/com/example/gateway/controller/OrdersProxyControllerTest.java) cobre os dez endpoints, métodos e query de paginação. |
| JWT encaminhado | PASS | Proxy encaminha `Authorization` da request autenticada; teste inspeciona o Bearer exato no transporte do WebClient. Requests sem autenticação não chamam Orders. |
| CORS | PASS | [GatewayCorsProperties](gateway-api/src/main/java/com/example/gateway/config/GatewayCorsProperties.java), [SecurityConfig](gateway-api/src/main/java/com/example/gateway/config/SecurityConfig.java), [GatewayCorsPropertiesTest](gateway-api/src/test/java/com/example/gateway/config/GatewayCorsPropertiesTest.java), [AuthControllerTest](gateway-api/src/test/java/com/example/gateway/controller/AuthControllerTest.java): origens exatas configuráveis, preflight, origem negada, sem wildcard/cookies. |
| Endpoints protegidos | PASS | `authenticated` para pedidos e `denyAll` para o restante, com exceções explícitas para login e GETs de documentação; STATELESS, Basic/form/logout desabilitados. |
| Erros de autenticação | PASS | [SecurityErrorHandler](gateway-api/src/main/java/com/example/gateway/security/SecurityErrorHandler.java) e [GlobalExceptionHandler](gateway-api/src/main/java/com/example/gateway/exception/GlobalExceptionHandler.java); 401/403 consistentes e `INVALID_CREDENTIALS` sem distinguir usuário inexistente/senha errada. |

## Checklist final — API 2

| Requisito | Resultado | Evidência |
|---|---|---|
| CRUD de pedidos | PASS | [OrderController](orders-api/src/main/java/com/example/orders/controller/OrderController.java), [OrderService](orders-api/src/main/java/com/example/orders/service/OrderService.java), [OrderControllerTest](orders-api/src/test/java/com/example/orders/controller/OrderControllerTest.java), [OrderPersistenceIntegrationTest](orders-api/src/test/java/com/example/orders/OrderPersistenceIntegrationTest.java): criação, consulta/listagem, atualização e exclusão. |
| CRUD de itens | PASS | [OrderItemController](orders-api/src/main/java/com/example/orders/controller/OrderItemController.java), [OrderService](orders-api/src/main/java/com/example/orders/service/OrderService.java); integrações incluem listagem/GET individual, POST, PUT, DELETE e vínculo com o pedido correto. |
| Validação | PASS | [CreateOrderRequest](orders-api/src/main/java/com/example/orders/dto/CreateOrderRequest.java), [UpdateOrderRequest](orders-api/src/main/java/com/example/orders/dto/UpdateOrderRequest.java), [OrderItemRequest](orders-api/src/main/java/com/example/orders/dto/OrderItemRequest.java); Bean Validation e `@Valid`, quantidade inteira positiva e dinheiro positivo com até duas casas decimais. |
| Paginação | PASS | Pageable e `JpaRepository.findAll(Pageable)` em [OrderService](orders-api/src/main/java/com/example/orders/service/OrderService.java); DTO de página, tamanho padrão 10/máximo 100 e ordenação por ID. Integração verifica páginas reais. |
| Order correto | PASS | [Order](orders-api/src/main/java/com/example/orders/entity/Order.java): todos os campos pedidos, tabela `orders`, ID gerado, data do servidor, PENDING, itens vazios e total inicial 0. |
| OrderItem correto | PASS | [OrderItem](orders-api/src/main/java/com/example/orders/entity/OrderItem.java): todos os campos, tabela `order_items`, FK `order_id`, ManyToOne lazy; Order tem OneToMany com cascade/orphanRemoval. |
| OrderStatus correto | PASS | [OrderStatus](orders-api/src/main/java/com/example/orders/entity/OrderStatus.java): PENDING, CONFIRMED, DELIVERED, CANCELLED; `EnumType.STRING` em Order, sem regras extras de transição. |
| totalAmount correto | PASS | [OrderService](orders-api/src/main/java/com/example/orders/service/OrderService.java): soma BigDecimal recalculada ao criar/atualizar/remover itens, limite NUMERIC(19,2), transação e lock do pedido; testes de recálculo e rollback. |
| subtotal correto | PASS | Mesmo service: `unitPrice.multiply(BigDecimal.valueOf(quantity))`; [OrderServiceTest](orders-api/src/test/java/com/example/orders/service/OrderServiceTest.java) e persistência verificam valores decimais e overflow. |

## Checklist final — requisitos técnicos

| Requisito | Resultado | Evidência e limite |
|---|---|---|
| Validação | PASS | DTOs validados de login e pedidos/itens; controllers usam `@Valid`; valores desconhecidos/tipos incompatíveis retornam 400. |
| Global exception handler | PASS | `exception/GlobalExceptionHandler.java` em cada API, ambos com `@RestControllerAdvice`; erros de filtros tratados separadamente por `SecurityErrorHandler`. |
| Testes unitários | PASS | [OrderServiceTest](orders-api/src/test/java/com/example/orders/service/OrderServiceTest.java), [JwtServiceTest](gateway-api/src/test/java/com/example/gateway/security/JwtServiceTest.java), testes de configuração/initializer; JUnit 5 e Mockito, asserções de comportamento. |
| Testes de integração | PASS | [AuthPersistenceIntegrationTest](gateway-api/src/test/java/com/example/gateway/AuthPersistenceIntegrationTest.java), [OrderPersistenceIntegrationTest](orders-api/src/test/java/com/example/orders/OrderPersistenceIntegrationTest.java), MockMvc/security/OpenAPI/proxy; executados sem skips. Limites H2/transporte estão declarados. |
| >= 70% cobertura | PASS | JaCoCo nos dois POMs, resultados XML novos: Gateway 95,29% linhas/80,77% branches; Orders 97,71%/81,63%; build exige 80%/70%, sem exclusões de produção. |
| Swagger/OpenAPI | PASS | [Gateway OpenApiConfig](gateway-api/src/main/java/com/example/gateway/config/OpenApiConfig.java) e [Orders OpenApiConfig](orders-api/src/main/java/com/example/orders/config/OpenApiConfig.java); testes geram/verificam schemas, operações, códigos HTTP, paginação, Bearer e recursos Swagger. Gateway documenta login e contrato genérico do proxy, não replica as dez operações completas de Orders. |
| Dockerfile gateway | PASS | [Gateway Dockerfile](gateway-api/Dockerfile): build Maven/JDK 21 multi-stage, `verify`, JRE 21 e usuário não root. Saída enviada pelo usuário confirma imagem construída e container saudável após a correção. |
| Dockerfile orders | PASS | [Orders Dockerfile](orders-api/Dockerfile): mesma estratégia; imagem construída e container saudável, confirmados pela execução do usuário. |
| WebClient | PASS | Starter WebFlux da gateway, [OrdersWebClientConfig](gateway-api/src/main/java/com/example/gateway/config/OrdersWebClientConfig.java), [OrdersProxyService](gateway-api/src/main/java/com/example/gateway/service/OrdersProxyService.java); timeouts, destinos configuráveis e status 4xx/5xx preservados, 502/504 para falhas de transporte. |
| Logging | PASS | [AuthService](gateway-api/src/main/java/com/example/gateway/service/AuthService.java), [OrderService](orders-api/src/main/java/com/example/orders/service/OrderService.java), proxy e handlers; SLF4J, eventos após commit e testes de captura de saída/rollback. |

## Checklist final — qualidade

| Requisito | Resultado | Evidência |
|---|---|---|
| Clean Code | PASS | Código com nomes em inglês, injeção por constructor, sem Lombok, responsabilidades de autenticação/proxy/CRUD separadas; inspeção sem refatoração cosmética. |
| Pacotes organizados | PASS | `controller`, `dto`, `entity`, `repository`, `service`, `security`, `config`, `exception`; Orders tem [mapper](orders-api/src/main/java/com/example/orders/mapper/OrderMapper.java). |
| Separação de responsabilidades | PASS | Controller → service → repository; gateway não tem Order/OrderItem; configuração HTTP/segurança separada dos serviços de negócio. |
| Sem entidades expostas desnecessariamente | PASS | Controllers retornam DTOs/ResponseEntity; entidades não são os tipos de entrada/saída HTTP e não usam serialização JSON para resolver ciclos. |
| DTOs | PASS | Pacotes `dto` de ambas as APIs; records para contratos, [OrderMapper](orders-api/src/main/java/com/example/orders/mapper/OrderMapper.java) para conversão explícita. |
| Erros consistentes | PASS | `ApiErrorResponse`, handlers MVC/security e nova regressão de firewall nas duas APIs. CORS rejeitado tem corpo fixo do Spring (`Invalid CORS request`), exceção explicitamente documentada; rejeições anteriores à aplicação no servidor HTTP não estão cobertas por MockMvc. |
| Sem senha plaintext | PASS | [TestUserInitializer](gateway-api/src/main/java/com/example/gateway/config/TestUserInitializer.java) persiste `passwordEncoder.encode`, [SecurityConfig](gateway-api/src/main/java/com/example/gateway/config/SecurityConfig.java) usa BCrypt; integração inspeciona `password_hash` e verifica `matches`. `senha123` aparece como credencial conhecida da prova, não como valor persistido. |
| Sem segredo JWT hardcoded | PASS | `${JWT_SECRET}` obrigatório, exemplo vazio, validação de Base64/32 bytes nos JwtConfigs; nenhuma ocorrência do segredo local ou JWT literal encontrada nos arquivos de entrega. Histórico de commits não disponível. |
| Sem logs sensíveis | PASS | Logs com eventos/status/IDs, sem corpo, senha, JWT ou segredo; testes verificam ausência desses valores. Configuração desabilita SQL/bind/extract e mensagens do SqlExceptionHelper. |
| Performance básica | PASS | Listagem paginada com resumos sem itens, lazy/OIV desligado, total armazenado, timeouts e limite de resposta 2 MiB do WebClient. Não houve teste de carga; detalhe de pedido/itens é não paginado e proxy MVC utiliza `block()`, limites documentados. |
| Paginação | PASS | [OrderController](orders-api/src/main/java/com/example/orders/controller/OrderController.java), [OrderService](orders-api/src/main/java/com/example/orders/service/OrderService.java), [PageResponse](orders-api/src/main/java/com/example/orders/dto/PageResponse.java), testes unitários/integração; query preservada pelo proxy. |
| Configuração por ambiente | PASS | Os dois `application.yml`, [.env.example](.env.example), propriedades de CORS/Orders e Compose; senhas/URLs/issuer/segredo/bind ajustáveis. A execução Maven exige exportar `.env`, conforme README. |

## Checklist final — entrega

| Requisito | Resultado | Evidência e limite |
|---|---|---|
| Código das duas APIs | PASS | Diretórios [gateway-api](gateway-api) e [orders-api](orders-api), POMs independentes e builds/testes novos aprovados. |
| Dockerfiles | PASS | Dois arquivos presentes e inspecionados; build de ambas as imagens confirmado pelo usuário com as alterações atuais. |
| docker-compose | PASS | [docker-compose.yml](docker-compose.yml) e [override de debug](docker-compose.debug.yml); configuração validada, quatro serviços, redes separadas e volumes persistentes. Saída do usuário confirma quatro serviços saudáveis e fluxo funcional. |
| README | PASS | [README.md](README.md) na raiz com todas as seções solicitadas; referências para Docker, testes e esta auditoria. |
| Como executar | PASS | README contém comandos locais e Compose com caminhos/portas/variáveis correspondentes ao código; syntax Bash conferida. Comandos de build/inicialização do Compose executados pelo usuário, com sucesso. |
| Endpoints | PASS | Tabela dos onze endpoints de negócio (login + dez pedidos/itens), consistente com controllers e OpenAPI de Orders. |
| Exemplos | PASS | Login/Bearer, criação/atualização/exclusão de pedido e item, paginação; JSONs válidos e variáveis TOKEN/ORDER_ID/ITEM_ID definidas nos exemplos. |
| Credenciais de teste | PASS | `usuario` / `senha123`, role USER, documentadas; seed seguro/idempotente opt-in, `.env.example` habilita para avaliação. ID gerado, não fixado artificialmente em 1. |
| Decisões arquiteturais | PASS | README explica CRUD extra de itens, JWT nas duas APIs, DTOs, cálculos no servidor, BigDecimal, paginação, WebClient, cascade/orphanRemoval e limites da implementação. |

## Verificações adicionais solicitadas

| Verificação | Resultado | Evidência |
|---|---|---|
| Build das duas APIs | PASS | Dois `clean verify` novos com JDK 21, JARs gerados. |
| Testes | PASS | 238 testes ao todo, nenhum erro/falha/skip; segurança e validação ativas. |
| JaCoCo | PASS | Relatórios HTML/XML/CSV, duas verificações de cobertura aprovadas. |
| Docker build | PASS | Tentativa do agente bloqueada pelo socket; execução pelo usuário concluiu 31/31 etapas e construiu as duas imagens, incluindo `mvn verify`. |
| Validar docker-compose | PASS | `docker compose config --quiet` e combinação com debug passaram; segredo JWT vazio foi corretamente rejeitado. Mesmo JWT/issuer, URLs internas, redes separadas e Orders sem porta publicada. Usuário confirmou quatro containers saudáveis e 26 verificações HTTP via gateway. |
| Procurar TODO/FIXME | PASS | Nenhum marcador pendente encontrado em código-fonte, testes ou arquivos operacionais inspecionados. |
| Procurar secrets acidentalmente commitados | PARCIAL | Nenhuma chave privada, chave AWS/GitHub, JWT literal ou cópia do segredo local encontrada nos arquivos de entrega. `.env` está ignorado; `.env.example` permitido. Git não reconhece um repositório neste ambiente (`.git` acessível vazio), portanto não se pode afirmar ausência de secrets no índice/histórico. |
| Procurar código morto | PASS | Inspeção de referências/classes e métodos privados não identificou código morto confirmado. Beans, entidades, constructors JPA e métodos acionados por Spring/reflexão não foram removidos como supostamente inúteis. |
| Procurar imports não utilizados | PASS | Inspeção dos 66 arquivos Java e checagem de referências sem candidatos encontrados; builds completos aprovados. Compilação por si só não seria prova de ausência de imports ociosos. |
| Procurar documentação divergente | PASS | Bash/JSON/links locais válidos, rotas/portas/variáveis comparadas ao código. Corrigidas a descrição de `nbf`, respostas de firewall e medição de cobertura; resultados antigos de TESTING continuam identificados por etapa. |

## Reproduzir a validação de runtime

Na máquina local, com Docker disponível e o `.env` já configurado, executar
na raiz sem apagar os volumes existentes:

```bash
docker compose config --quiet
docker compose build
docker compose up -d --wait --wait-timeout 300
docker compose ps
```

Esses comandos foram confirmados pelo usuário nesta etapa. Após quatro
serviços saudáveis, executar os exemplos do README pela gateway:
login, criação/listagem/alteração de pedido, CRUD de item e verificação dos totais.
Conferir também 401 sem Bearer e 404/400 sem perda de status pelo proxy.
Não enviar `.env`, senhas de banco, o JWT completo ou configuração expandida
como evidência; o resumo do build, estados dos serviços e respostas sem token
são suficientes para a primeira confirmação.

A execução de persistência especificamente sobre PostgreSQL deve usar bancos
**exclusivos de teste**, conforme [TESTING.md](TESTING.md). As suítes usam
`create-drop` e não podem apontar para bancos de desenvolvimento com pedidos
ou usuários que devam ser preservados.

Se um repositório Git existir na máquina de entrega, falta inspecionar também
seu índice e histórico para concluir a busca de secrets commitados. Nenhum
repositório foi criado nem histórico modificado durante esta auditoria.

O único item adicional PARCIAL remanescente é a inspeção de secrets no histórico
Git indisponível. Não há FAIL/PARCIAL remanescente na checklist oficial de 59
requisitos. Essa classificação se limita ao escopo da prova e às evidências
descritas; não representa um teste de carga ou aprovação de infraestrutura pública.
