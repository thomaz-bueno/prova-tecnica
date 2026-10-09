# Prova técnica Java — Gateway e Orders APIs

Projeto com duas APIs REST independentes em Java 21 e Spring Boot 3: uma gateway
responsável por autenticação e proxy HTTP, e uma API responsável por pedidos e
seus itens. As duas aplicações usam Spring Security com JWT e persistência com
Spring Data JPA/Hibernate em PostgreSQL.

A prova contempla autenticação, comunicação entre serviços, CRUD, validação,
tratamento de erros, documentação OpenAPI, testes, cobertura e execução Docker.
O projeto utiliza Maven e não utiliza Lombok.

## Arquitetura

```text
Cliente
   |
   v
Gateway API :8080 ----------------> PostgreSQL gateway_db
   |                               gateway_users / gateway_user_roles
   | WebClient + Authorization: Bearer <JWT>
   v
Orders API :8081
   |
   v
PostgreSQL orders_db
orders / order_items
```

- **Gateway:** autentica usuários persistidos, emite JWT e encaminha
  `/api/orders` e `/api/orders/**` para Orders. Não contém entidades nem regras
  de negócio de pedidos.
- **Orders:** valida novamente o JWT, executa o CRUD e calcula os valores
  monetários. Não possui endpoint de login nem cadastro de usuários.
- **Persistência:** autenticação e pedidos usam bancos e volumes separados.
- **Fluxo interno de Orders:** `Controller → Service → Repository → PostgreSQL`.
  DTOs e um mapper simples delimitam o contrato HTTP; entidades JPA não são
  expostas diretamente em JSON.

No Compose, os serviços são `gateway-api`, `orders-api`, `gateway-db` e
`orders-db`. A rede `application` conecta as APIs; `auth-data` e `orders-data`
são redes internas separadas para cada API e seu banco.

## Tecnologias

| Tecnologia | Uso |
|---|---|
| Java 21 | Linguagem e runtime |
| Spring Boot 3.5.16 / Spring MVC | Aplicações e endpoints REST |
| Spring Security / OAuth2 Resource Server | Autenticação stateless e validação JWT |
| Nimbus, integrado ao Spring Security | Assinatura e validação HS256 |
| BCrypt | Hash das senhas |
| Spring Data JPA / Hibernate | Repositories, entidades e transações |
| PostgreSQL 17 no Compose | Persistência das aplicações |
| Jakarta Bean Validation | Validação de requests e parâmetros |
| WebClient / Reactor Netty | Comunicação HTTP da gateway com Orders |
| springdoc-openapi 2.9.1 | OpenAPI e Swagger UI |
| JUnit 5, Mockito, MockMvc e H2 | Testes unitários e de integração |
| JaCoCo 0.8.15 | Relatórios e limites de cobertura |
| Maven, Docker e Docker Compose | Build e execução |
| SLF4J / logging padrão do Spring | Eventos de aplicação |

H2 tem escopo **test**. A execução normal das APIs utiliza PostgreSQL.
WebFlux é uma dependência da gateway para disponibilizar WebClient; os
controllers continuam usando Spring MVC.

## Estrutura do projeto

```text
.
├── .github/workflows/ci.yml
├── gateway-api/
│   ├── src/main/java/com/example/gateway/
│   │   ├── config/       # Security, JWT, CORS, WebClient, OpenAPI e inicialização
│   │   ├── controller/   # Login e proxy
│   │   ├── dto/
│   │   ├── entity/
│   │   ├── exception/
│   │   ├── repository/
│   │   ├── security/
│   │   └── service/
│   ├── src/main/resources/application.yml
│   ├── src/test/java/
│   ├── Dockerfile
│   ├── .dockerignore
│   └── pom.xml
├── orders-api/
│   ├── src/main/java/com/example/orders/
│   │   ├── config/
│   │   ├── controller/
│   │   ├── dto/
│   │   ├── entity/
│   │   ├── exception/
│   │   ├── mapper/
│   │   ├── repository/
│   │   ├── security/
│   │   └── service/
│   ├── src/main/resources/application.yml
│   ├── src/test/java/
│   ├── Dockerfile
│   ├── .dockerignore
│   └── pom.xml
├── docker-compose.yml
├── docker-compose.debug.yml
├── .env.example
├── DOCKER.md
├── TESTING.md
└── README.md
```

Cada API tem seu próprio `pom.xml`. Não existe um `pom.xml` agregador na raiz;
os comandos abaixo usam `-f` para selecionar a aplicação.

## Pré-requisitos

Para execução completa via Docker:

- Docker com daemon em execução e permissão de acesso.
- Docker Compose v2 com suporte a `--wait`.
- Acesso à internet no primeiro build para baixar imagens e dependências Maven.

Para executar as APIs manualmente ou os testes no computador:

- JDK **21** e Maven **3.9.x**.
- PostgreSQL acessível; pode ser iniciado pelo Compose.

Os exemplos de terminal usam Bash, curl e OpenSSL. Java e Maven não precisam
estar instalados no computador quando o build e a execução forem feitos apenas
com Docker.

**Todos os comandos deste README partem da raiz do projeto.**

## Variáveis de ambiente

Na primeira configuração, crie o arquivo local sem sobrescrever um existente:

```bash
if [ ! -f .env ]; then
  cp .env.example .env
fi
chmod 600 .env
```

Se `JWT_SECRET` estiver vazio, gere uma chave uma única vez:

```bash
openssl rand -base64 32
```

Copie o resultado para `JWT_SECRET` no `.env`. As duas aplicações devem receber
**o mesmo segredo e issuer**. Não gere uma chave diferente em cada terminal.
O segredo deve ser Base64 válido de pelo menos 32 bytes decodificados.
Não há segredo JWT padrão no código; `.env` é ignorado pelo Git.

| Variável | Padrão / exemplo de desenvolvimento | Finalidade |
|---|---|---|
| `JWT_SECRET` | Obrigatória, sem padrão | Chave compartilhada para HS256 |
| `JWT_ISSUER` | `gateway-api` | Emissor esperado nas duas APIs |
| `GATEWAY_DB_URL` | `jdbc:postgresql://localhost:5432/gateway_db` | Banco local de autenticação |
| `GATEWAY_DB_USERNAME` | `gateway_dev` | Usuário do banco da gateway |
| `GATEWAY_DB_PASSWORD` | `gateway_dev` no exemplo | Senha fictícia de desenvolvimento |
| `GATEWAY_DDL_AUTO` | `update` | Configuração de schema Hibernate |
| `GATEWAY_SEED_TEST_USER` | `false` no código; `true` no exemplo | Habilita a conta da prova |
| `GATEWAY_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Origens exatas, separadas por vírgula |
| `ORDERS_API_URL` | `http://localhost:8081` | Destino HTTP fixo do proxy |
| `ORDERS_API_CONNECT_TIMEOUT` | `2s` | Limite para estabelecer conexão |
| `ORDERS_API_TIMEOUT` | `5s` | Limite para a chamada completa |
| `ORDERS_DB_URL` | `jdbc:postgresql://localhost:5433/orders_db` | Banco local de pedidos |
| `ORDERS_DB_USERNAME` | `orders_dev` | Usuário do banco de pedidos |
| `ORDERS_DB_PASSWORD` | `orders_dev` no exemplo | Senha fictícia de desenvolvimento |
| `ORDERS_DDL_AUTO` | `update` | Configuração de schema Hibernate |
| `ORDERS_SERVER_ADDRESS` | `127.0.0.1` | Bind da API Orders fora do Docker |
| `GATEWAY_DB_PORT` | `5432` | Porta do banco da gateway publicada pelo Compose |
| `ORDERS_DB_PORT` | `5433` | Porta do banco de Orders publicada pelo Compose |
| `GATEWAY_API_PORT` | `8080` | Porta da gateway publicada pelo Compose |
| `GATEWAY_BIND_ADDRESS` | `127.0.0.1` | Interface de publicação da gateway no Compose |

As últimas quatro variáveis controlam publicação de portas no Compose; não
alteram as portas HTTP internas `8080` e `8081` das aplicações.
As senhas do exemplo são valores fictícios para desenvolvimento. Escolha suas
credenciais locais antes da primeira inicialização dos volumes.

O Compose lê `.env` automaticamente e exige segredo JWT e senhas de banco não
vazios. Sobrescreve as URLs com `gateway-db:5432`, `orders-db:5432` e
`http://orders-api:8081`, além de configurar Orders com bind `0.0.0.0`.
Spring Boot executado pelo Maven **não lê `.env` automaticamente**: é necessário
exportar as variáveis, conforme a seção de execução manual.

Para acesso de outra máquina à gateway em Docker, configure
`GATEWAY_BIND_ADDRESS=0.0.0.0`. Para um frontend em outra origem, configure também
`GATEWAY_CORS_ALLOWED_ORIGINS` com o endereço do frontend. São configurações
independentes: CORS não substitui publicação de portas nem autenticação.

## Executar via Docker Compose

Após configurar `.env`, libere as portas `8080`, `5432` e `5433` no computador:

```bash
docker compose config --quiet
docker compose build
docker compose up -d --wait --wait-timeout 300
docker compose ps
```

O resultado esperado são quatro serviços `Up` e `healthy`. A gateway fica
disponível em `http://localhost:8080`.

| Serviço | Acesso no computador | Porta interna |
|---|---|---|
| `gateway-api` | `127.0.0.1:8080` | `8080` |
| `orders-api` | Sem porta publicada no Compose principal | `8081` |
| `gateway-db` | `127.0.0.1:5432` | `5432` |
| `orders-db` | `127.0.0.1:5433` | `5432` |

Os Dockerfiles usam build multi-stage: Maven/JDK 21 executam `verify`, incluindo
testes e cobertura, e a imagem final usa JRE 21 para executar o JAR com usuário
sem privilégios de root. O cache Maven é compartilhado e protegido durante o
build. Os contextos de build não incluem o `.env` da raiz.

Orders aguarda seu PostgreSQL ficar saudável. A gateway aguarda seu banco e
Orders. Os healthchecks HTTP consultam o GET público `/v3/api-docs`: verificam
resposta HTTP após inicialização, mas não testam continuamente a conexão com o
banco. Essa configuração requer OpenAPI habilitado.

Para consultar logs:

```bash
docker compose logs --tail=100 gateway-api orders-api
```

Para encerrar preservando os volumes:

```bash
docker compose down
```

Os volumes `gateway-postgres-data` e `orders-postgres-data` persistem os dados.
Adicionar `-v` ao `down` apaga esses volumes. Alterar a senha no `.env` não altera
a senha de um usuário PostgreSQL já inicializado em um volume existente.
Pedidos antigos armazenados em um `orders_db` criado dentro de `gateway-db`
não são migrados automaticamente para o novo `orders-db`.

Detalhes operacionais: [DOCKER.md](DOCKER.md).

## Executar manualmente

Esta opção executa as APIs com Maven no computador e mantém apenas os bancos no
Docker. Se as APIs do Compose estiverem em execução, pare-as primeiro:

```bash
docker compose stop gateway-api orders-api
docker compose up -d --wait --wait-timeout 120 gateway-db orders-db
```

No `.env`, confira as URLs locais: gateway usa `localhost:5432/gateway_db` e
Orders usa `localhost:5433/orders_db`. Se alterou as portas publicadas, ajuste
também as URLs JDBC. Em configurações de etapas anteriores, Orders pode ainda
estar apontando para `5432`.

Confira a versão do Java utilizada pelo Maven:

```bash
java -version
mvn -version
```

No Linux, se `/usr/bin/javac` já aponta para o JDK 21, estes comandos selecionam
esse JDK no terminal:

```bash
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f /usr/bin/javac)")")"
export PATH="$JAVA_HOME/bin:$PATH"
```

Caso `/usr/bin/javac` aponte para outro JDK, configure `JAVA_HOME` com o diretório
do JDK 21 instalado. Confirme que `mvn -version` informa Java 21.

No **terminal 1**, carregue o `.env` e inicie Orders:

```bash
set -a
source .env
set +a
mvn -f orders-api/pom.xml spring-boot:run
```

No **terminal 2**, também na raiz e com JDK 21, carregue o mesmo `.env` e inicie
a gateway:

```bash
set -a
source .env
set +a
mvn -f gateway-api/pom.xml spring-boot:run
```

As portas HTTP são `8081` e `8080`, respectivamente. Para usar PostgreSQL instalado
fora do Docker, provisione os bancos/usuários indicados pelas variáveis e ajuste
as URLs JDBC. Hibernate cria/atualiza as tabelas ao conectar; não cria os bancos.

## Testes

Com JDK 21 e Maven, execute na raiz:

```bash
mvn -B -f gateway-api/pom.xml test
mvn -B -f orders-api/pom.xml test
mvn -B -f gateway-api/pom.xml clean verify
mvn -B -f orders-api/pom.xml clean verify
```

`test` executa testes unitários e de integração e gera o relatório JaCoCo.
`clean verify` limpa artefatos, executa novamente os testes, empacota a aplicação
e verifica os limites de cobertura. Cada API é verificada separadamente.

- **Unitários:** regras monetárias, CRUD no service, inicialização do usuário,
  configurações e JWT, com mocks de repositories quando apropriado.
- **Integração MVC/security:** MockMvc, filtros reais de segurança, validações,
  DTOs, erros, CORS e documentação. Alguns testes isolam o service/repository.
- **Integração de persistência:** contexto completo Spring, JPA, transações e
  banco H2 em modo de compatibilidade PostgreSQL, sem depender dos bancos locais.
- **Proxy:** WebClient real com transporte de teste `ExchangeFunction`, verificando
  método/path/query/body/Bearer, respostas e falhas sem uma API externa real.
- **Logging:** captura de saída, ausência de dados sensíveis e eventos de
  alteração somente após commit.

Os testes geram seus próprios segredos e não precisam de `.env`. A segurança
permanece ativa; testes dedicados validam JWTs realmente assinados. H2 não comprova
comportamentos específicos de PostgreSQL nem concorrência sob carga. Os testes
do proxy não iniciam dois servidores HTTP nem validam DNS/TCP reais.

Para executar persistência com PostgreSQL, a suíte aceita
`GATEWAY_TEST_DB_URL`, `GATEWAY_TEST_DB_USERNAME`, `GATEWAY_TEST_DB_PASSWORD` e os
equivalentes `ORDERS_TEST_DB_*`. Use bancos exclusivos para testes: a configuração
é `create-drop` e recria/remove as tabelas. Instruções e matriz: [TESTING.md](TESTING.md).

## Cobertura

Após `test` ou `verify`, abra os arquivos HTML no navegador:

- [Gateway: target/site/jacoco/index.html](gateway-api/target/site/jacoco/index.html)
- [Orders: target/site/jacoco/index.html](orders-api/target/site/jacoco/index.html)

Também são gerados `jacoco.xml` e `jacoco.csv` no mesmo diretório de cada API.
O build exige **80% de linhas** e **70% de branches**, acima do mínimo de 70%
solicitado pela prova. Não há exclusões de classes de produção para inflar a métrica.

Última medição registrada na auditoria final (etapa 16), com `clean verify` e H2:

| API | Testes aprovados | Linhas | Branches |
|---|---:|---:|---:|
| Gateway | 121 | 95,29% | 80,77% |
| Orders | 117 | 97,71% | 81,63% |

Sem falhas, erros ou testes ignorados nessa execução. Cobertura mede execução de
código; não substitui asserções nem comprova desempenho ou execução dos containers.

## Swagger / OpenAPI

| API | Swagger UI | OpenAPI JSON | OpenAPI YAML |
|---|---|---|---|
| Gateway | http://localhost:8080/swagger-ui.html | http://localhost:8080/v3/api-docs | http://localhost:8080/v3/api-docs.yaml |
| Orders | http://localhost:8081/swagger-ui.html | http://localhost:8081/v3/api-docs | http://localhost:8081/v3/api-docs.yaml |

As URLs de Orders funcionam na execução manual. No Compose principal, Orders
não publica porta. Para acesso local ao seu Swagger, use o override existente:

```bash
docker compose -f docker-compose.yml -f docker-compose.debug.yml up -d --wait --wait-timeout 300
```

O override publica somente `127.0.0.1:8081`. Libere essa porta caso Orders esteja
executando pelo Maven.

O Swagger de Orders documenta as dez operações de pedidos/itens, schemas,
paginação e erros. O Swagger da gateway documenta `/auth/login` e exemplos GET/POST
`/api/orders`, com schemas genéricos: as outras rotas continuam disponíveis pelo
proxy. Essa limitação evita duplicar o contrato de negócio na gateway.

Obtenha o token no login da gateway e cole **somente o token** em **Authorize**;
o Swagger acrescenta `Bearer`. A autorização não é persistida no navegador.
O Swagger de Orders chama diretamente Orders para diagnóstico; chamadas normais
de clientes devem usar a gateway. Os GETs de documentação são públicos.

## Autenticação e credenciais de teste

| Campo | Valor |
|---|---|
| Username | `usuario` |
| Password | `senha123` |
| Role | `USER` |

Habilite `GATEWAY_SEED_TEST_USER=true`, como no `.env.example`. Na inicialização,
a gateway cria essa conta somente se o username não existir, persistindo hash
BCrypt. Reiniciar não duplica a conta nem redefine uma senha existente. O ID é
gerado pelo banco e não é fixado pelo código.

```text
username/password
  -> AuthenticationManager
  -> UserDetailsService -> repository -> gateway_db
  -> PasswordEncoder verifica BCrypt
  -> JwtEncoder gera JWT
```

O JWT é assinado com HS256, contém `iss`, `sub` (username), `roles`, `iat`, `nbf`
e `exp`. Sua validade é exatamente **3600 segundos**, com rejeição no instante
de expiração e sem tolerância de relógio. Os relógios dos serviços devem estar
sincronizados. `USER` no claim é convertido em `ROLE_USER` no Spring Security.
As rotas de pedidos exigem autenticação; não há restrição adicional a uma role
específica nem vínculo de propriedade entre usuário autenticado e pedido.

O token é assinado, não criptografado. Não contém senha nem hash. Não há refresh
token, endpoint de logout ou mecanismo de revogação de tokens emitidos.

### Login

```bash
curl -i -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"usuario","password":"senha123"}'
```

Resposta esperada: `200 OK` e corpo:

```json
{
  "token": "<JWT retornado pela gateway>",
  "tokenType": "Bearer",
  "expiresIn": 3600
}
```

Copie o valor de `token` para a variável abaixo, substituindo o texto de exemplo.
Mantenha as próximas chamadas no mesmo terminal:

```bash
TOKEN='COLE_O_TOKEN_RETORNADO_PELO_LOGIN'
```

O login é público quanto ao acesso à rota, mas verifica as credenciais. A resposta
usa `Cache-Control: no-store`. Usuário inexistente e senha incorreta retornam o
mesmo `401 / INVALID_CREDENTIALS`.

## Endpoints

Todas as rotas de pedidos/itens abaixo são chamadas pelo cliente em
`http://localhost:8080` e encaminhadas para Orders. O login existe apenas na gateway.

| Método | Endpoint | Autenticação | Resultado de sucesso |
|---|---|---|---|
| POST | `/auth/login` | Username/password no corpo | `200` e JWT |
| GET | `/api/orders?page=0&size=10` | Bearer JWT | `200`, resumos paginados |
| GET | `/api/orders/{id}` | Bearer JWT | `200`, pedido com itens |
| POST | `/api/orders` | Bearer JWT | `201`, pedido e header Location |
| PUT | `/api/orders/{id}` | Bearer JWT | `200`, pedido atualizado |
| DELETE | `/api/orders/{id}` | Bearer JWT | `204`, sem corpo |
| GET | `/api/orders/{orderId}/items` | Bearer JWT | `200`, lista de itens |
| POST | `/api/orders/{orderId}/items` | Bearer JWT | `201`, item e header Location |
| GET | `/api/orders/{orderId}/items/{itemId}` | Bearer JWT | `200`, item |
| PUT | `/api/orders/{orderId}/items/{itemId}` | Bearer JWT | `200`, item atualizado |
| DELETE | `/api/orders/{orderId}/items/{itemId}` | Bearer JWT | `204`, sem corpo |

## Exemplos de pedidos

### Criar pedido usando Authorization Bearer

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"customerName":"Ana","customerEmail":"ana@example.com"}'
```

Resposta ilustrativa (`201`); ID e data variam:

```json
{
  "id": 1,
  "customerName": "Ana",
  "customerEmail": "ana@example.com",
  "orderDate": "2026-10-09T02:26:29.731359228",
  "status": "PENDING",
  "items": [],
  "totalAmount": 0.00
}
```

Criação recebe apenas `customerName` e `customerEmail`. Nome e email são
obrigatórios, limitados a 255 caracteres; email deve ser válido. O servidor gera
ID e data, define `PENDING`, lista vazia e total zero. Não envie `id`, `orderDate`,
`status`, `items` ou `totalAmount` nesse request: campos desconhecidos são rejeitados.

Defina o ID retornado antes das próximas chamadas. `1` é somente um exemplo:

```bash
ORDER_ID=1

curl -i "http://localhost:8080/api/orders/$ORDER_ID" \
  -H "Authorization: Bearer $TOKEN"
```

### Atualizar pedido

```bash
curl -i -X PUT "http://localhost:8080/api/orders/$ORDER_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"customerName":"Ana Updated","customerEmail":"ana@example.com","status":"CONFIRMED"}'
```

PUT exige nome, email e status. Preserva ID, data, itens e total. Os valores de
status são `PENDING`, `CONFIRMED`, `DELIVERED` e `CANCELLED`. Não há regras extras
de transição entre status.

## Exemplos de itens

### Adicionar item

```bash
curl -i -X POST "http://localhost:8080/api/orders/$ORDER_ID/items" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Keyboard","quantity":2,"unitPrice":19.90}'
```

Resposta ilustrativa (`201`):

```json
{
  "id": 1,
  "productName": "Keyboard",
  "quantity": 2,
  "unitPrice": 19.90,
  "subtotal": 39.80
}
```

`productName` é obrigatório e tem limite de 255 caracteres. `quantity` é um
Integer obrigatório e positivo; valores fracionários são rejeitados. `unitPrice`
é obrigatório e positivo, com até 17 dígitos inteiros e duas casas decimais.
Não envie `id`, `subtotal` ou referência de entidade no corpo.

O servidor usa **BigDecimal**: `subtotal = quantity × unitPrice` e
`totalAmount = soma dos subtotais`. Criação, atualização e exclusão de itens
recalculam o total. Valores calculados acima de `99999999999999999.99` são
rejeitados com `400`, preservando os dados por rollback.

Defina `ITEM_ID` com o ID retornado. `1` é somente um exemplo:

```bash
ITEM_ID=1

curl -i "http://localhost:8080/api/orders/$ORDER_ID/items" \
  -H "Authorization: Bearer $TOKEN"

curl -i "http://localhost:8080/api/orders/$ORDER_ID/items/$ITEM_ID" \
  -H "Authorization: Bearer $TOKEN"
```

### Atualizar e excluir item

```bash
curl -i -X PUT "http://localhost:8080/api/orders/$ORDER_ID/items/$ITEM_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"productName":"Keyboard Updated","quantity":3,"unitPrice":15.25}'
```

Esperado: `200` com subtotal `45.75`. Se esse for o único item, o total do pedido
também será `45.75`.

```bash
curl -i -X DELETE "http://localhost:8080/api/orders/$ORDER_ID/items/$ITEM_ID" \
  -H "Authorization: Bearer $TOKEN"
```

Esperado: `204`, sem corpo. Se era o último item, o total volta a zero.
O acesso a um item é limitado ao pedido indicado na URL.

Para excluir o pedido e seus itens restantes:

```bash
curl -i -X DELETE "http://localhost:8080/api/orders/$ORDER_ID" \
  -H "Authorization: Bearer $TOKEN"
```

Esperado: `204`, sem corpo.

## Paginação

```bash
curl -i 'http://localhost:8080/api/orders?page=0&size=10' \
  -H "Authorization: Bearer $TOKEN"
```

`page` começa em zero e tem padrão `0`; `size` tem padrão `10` e aceita `1` a
`100`. A ordenação é fixa por `id` crescente; não existe ordenação configurável
pelo cliente. A gateway preserva os parâmetros e Orders usa Spring Data Pageable
na consulta ao banco, evitando carregar todos os pedidos.

Resposta ilustrativa para uma base com um pedido:

```json
{
  "content": [
    {
      "id": 1,
      "customerName": "Ana",
      "customerEmail": "ana@example.com",
      "orderDate": "2026-10-09T02:26:29.731359",
      "status": "PENDING",
      "totalAmount": 0.00
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 1,
  "totalPages": 1
}
```

A listagem usa resumos sem itens. A busca individual inclui os itens; a listagem
de itens de um pedido não é paginada na implementação atual.

## Erros

MVC e filtros JWT usam respostas JSON consistentes, sem stack traces, SQL ou
detalhes sensíveis. Exemplo de pedido inexistente:

```json
{
  "timestamp": "2026-10-09T02:30:00Z",
  "status": 404,
  "error": "Not Found",
  "code": "ORDER_NOT_FOUND",
  "message": "Order 10 not found",
  "path": "/api/orders/10"
}
```

Exemplo de validação:

```json
{
  "timestamp": "2026-10-09T02:30:00Z",
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "path": "/api/orders",
  "errors": {
    "customerName": "Customer name must not be blank",
    "customerEmail": "Customer email must be a valid email address"
  }
}
```

| HTTP | Código | Situação |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Campos ou parâmetros fora das restrições |
| 400 | `INVALID_REQUEST` | JSON, tipos, campos desconhecidos, URL ou headers inválidos |
| 400 | `INVALID_ORDER_AMOUNT` | Subtotal/total excede a capacidade monetária |
| 401 | `INVALID_CREDENTIALS` | Login com credenciais incorretas |
| 401 | `UNAUTHORIZED` | Bearer ausente, inválido ou expirado |
| 403 | `FORBIDDEN` | Usuário autenticado tenta acessar rota negada |
| 404 | `ORDER_NOT_FOUND` | Pedido não encontrado |
| 404 | `ORDER_ITEM_NOT_FOUND` | Item não encontrado no pedido indicado |
| 404 | `RESOURCE_NOT_FOUND` | Rota desconhecida dentro da área permitida |
| 405 | `METHOD_NOT_ALLOWED` | Método não suportado pelo recurso |
| 406 | `NOT_ACCEPTABLE` | Tipo de resposta solicitado não suportado por Orders |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Content-Type não suportado |
| 500 | `INTERNAL_ERROR` | Falha interna com mensagem genérica |
| 502 | `ORDERS_API_UNAVAILABLE` | Falha de conexão/transporte com Orders |
| 502 | `ORDERS_API_BAD_RESPONSE` | Resposta de Orders excede o limite de leitura |
| 504 | `ORDERS_API_TIMEOUT` | Timeout ao chamar Orders |

Respostas HTTP 4xx/5xx recebidas de Orders são preservadas pela gateway, com
status e corpo originais. Falhas de transporte são tratadas separadamente.
URLs e headers rejeitados pelo firewall do Spring Security recebem diretamente
`400 / INVALID_REQUEST` no mesmo formato JSON, antes da autenticação; a rejeição
não é encaminhada para `/error` nem para a API de pedidos.
Rejeições de CORS ocorrem antes do controller e podem retornar `403` com o corpo
fixo `Invalid CORS request`, em vez do contrato JSON de erro.

## Decisões arquiteturais

> O enunciado lista GET e POST para itens, porém o critério de avaliação menciona
> CRUD completo de pedidos e itens. Portanto foram adicionados GET individual,
> PUT e DELETE de OrderItem.

- **Proxy genérico:** cobre `/api/orders/**` sem duplicar entidades, DTOs de
  negócio ou regras em cada serviço. WebClient preserva método, path, query,
  corpo e Bearer validado. `Location` de um recurso interno é devolvido como
  caminho relativo para manter o cliente na gateway.
- **Validação JWT nas duas APIs:** Orders não confia cegamente em uma chamada
  que alcance sua rede. Valida assinatura e claims mesmo após a gateway validar.
  O segredo compartilhado exige proteger a configuração de ambos os serviços.
- **DTOs:** requests limitam campos editáveis e responses evitam expor entidades
  e ciclos JPA. Campos desconhecidos são rejeitados; o cliente não define totais.
- **Dinheiro:** BigDecimal e colunas `NUMERIC(19,2)`, sem `double`/`float` nos
  cálculos de negócio. Preços são normalizados sem arredondamento silencioso.
- **Relacionamento:** `Order` 1:N `OrderItem`, com cascade e orphan removal;
  excluir pedido remove itens e remover item da coleção remove sua linha.
- **Transações e concorrência:** escritas usam `@Transactional` e lock pessimista
  no pedido para serializar alterações no mesmo agregado e proteger seu total.
  Leituras usam transações read-only; Open Session in View está desabilitado.
- **Schema:** tabelas `orders`, `order_items`, `gateway_users` e
  `gateway_user_roles` evitam nomes reservados. `ddl-auto=update` é uma decisão
  de desenvolvimento; migrations versionadas não estão implementadas.
- **Data do pedido:** LocalDateTime gerado pelo servidor, sem offset no JSON;
  representa o horário local do processo. Containers e execução local podem
  ter fusos distintos. Timestamps de erros usam Instant em UTC.
- **Escopo simples:** sem restrições extras de transição de status, hierarquia
  de roles ou cadastro de usuários. Essas funcionalidades não são exigidas nem
  estão implementadas.

## Segurança

- Ambas as APIs usam `STATELESS`, sem sessão de login. Basic, formulário de login,
  logout padrão e cache de requisição estão desabilitados.
- CSRF está desabilitado porque a autenticação usa apenas Bearer explícito no
  header Authorization, sem credenciais anexadas automaticamente pelo navegador.
  Stateless sozinho não justificaria desabilitar CSRF com autenticação por cookie.
- JWT é validado pelo Resource Server/Nimbus do Spring Security, sem filtro
  criptográfico manual. Aceita HS256 e valida assinatura, issuer, subject, issued-at,
  expiração e not-before quando presente. Senhas e segredos não são claims.
- Senhas são persistidas com BCrypt; login rejeita entradas acima do limite de
  72 bytes UTF-8 do BCrypt sem truncamento silencioso.
- Apenas `POST /auth/login` e GETs de documentação são públicos. Pedidos e itens
  exigem JWT; demais rotas seguem `denyAll`. Autenticação inválida resulta em
  401; acesso negado a usuário autenticado resulta em 403.
- CORS da gateway permite origens exatas configuráveis, sem wildcard e sem
  credenciais por cookie. Permite GET, HEAD, POST, PUT, DELETE e OPTIONS, headers
  Authorization/Content-Type/Accept e expõe Location. Preflight válido dispensa
  JWT; a chamada real continua protegida. Orders não oferece CORS público.
- Headers padrão do Spring Security permanecem ativos, incluindo `nosniff`,
  bloqueio de frames e controles de cache. HSTS é emitido em requisições seguras;
  HTTPS deve ser providenciado no ingresso de uma implantação pública.
- DTOs, Bean Validation e mapeamento explícito evitam mass assignment. Queries
  derivadas e JPQL fixo usam parâmetros; não concatenam entrada do usuário em SQL.
- Orders fica em loopback no modo local e não publica porta no Compose principal.
  Sua validação JWT continua obrigatória mesmo quando o override de debug é usado.

Para implantação pública, ainda seriam necessários ajustes de infraestrutura,
como HTTPS, limitação de tentativas de login, credenciais de banco com privilégios
mínimos e migrations controladas. Desabilitar o seed não remove uma conta já
persistida. Alterações de senha/roles não revogam JWTs já emitidos: eles permanecem
válidos até expiração ou troca do segredo.

## Logging

As APIs usam SLF4J e o logging padrão do Spring, escrevendo INFO/WARN/ERROR na
saída padrão. Eventos úteis incluem:

| Evento | Uso |
|---|---|
| `AUTHENTICATION_SUCCEEDED` / `AUTHENTICATION_FAILED` | Resultado do login |
| `ORDER_CREATED` / `ORDER_UPDATED` / `ORDER_DELETED` | Escritas de pedido confirmadas |
| `ORDER_ITEM_CREATED` / `ORDER_ITEM_UPDATED` / `ORDER_ITEM_DELETED` | Escritas de item confirmadas |
| `ORDERS_API_COMMUNICATION_FAILED` | Falha de comunicação/timeout/resposta ilegível |
| `ORDERS_API_SERVER_ERROR` | Orders respondeu com 5xx |
| `REQUEST_FAILED` | Erro interno |

Eventos de alteração são emitidos **após o commit**, sem registrar sucesso em
caso de rollback. Logs de aplicação usam nomes fixos de evento, IDs gerados pelo
servidor, status HTTP e tipo de exceção. Não registram senhas, JWTs, segredo JWT,
username, dados de clientes/produtos, corpos de requisição/resposta ou mensagens
sensíveis de exceção. SQL e bind/extract de parâmetros estão desabilitados.

No Docker, o driver `local` limita os logs a três arquivos de 10 MB por serviço.
Consulte-os com o comando de logs da seção Docker. Evite publicar o resultado
expandido de `docker compose config`, que contém variáveis sensíveis; a validação
documentada usa `--quiet`.

## Performance e escalabilidade

- Paginação real com limite de 100 pedidos por página e DTOs de resumo evita
  carregar todos os pedidos e suas coleções na listagem.
- Relacionamentos são lazy; os DTOs são montados dentro das transações. A busca
  individual e a listagem de itens ainda carregam todos os itens daquele pedido.
- WebClient reutiliza conexões por Reactor Netty e tem timeouts configuráveis.
  O proxy usa `block()` no thread MVC e buffer de resposta limitado a 2 MiB;
  não é uma aplicação inteiramente reativa nem um proxy de streaming.
- Retries e redirects automáticos estão desabilitados, evitando repetição de
  escritas e encaminhamento de Bearer para outro destino.
- O total é armazenado e recalculado a cada alteração de item. O custo cresce
  com a quantidade de itens; o lock do pedido protege consistência, mas pode
  causar contenção com muitas escritas simultâneas no mesmo pedido.
- Autenticação stateless permite distribuir requests entre instâncias com o
  mesmo segredo/issuer. Entretanto, o bootstrap da conta de teste pressupõe
  inicialização controlada; não é um provisionador concorrente de usuários.
- Os bancos separados permitem evolução independente. A configuração atual
  sobe uma instância de cada serviço e não inclui balanceador, cache, replicas
  de banco, autoscaling, testes de carga ou medições de throughput/latência.

## Integração contínua — GitHub Actions

O workflow [`.github/workflows/ci.yml`](.github/workflows/ci.yml) executa em
`push` e `pull_request`, com um job independente para cada API em uma matriz.
Configura Temurin Java 21, cache Maven com chave baseada no `pom.xml` da API e
executa `mvn -B -ntp -f <api>/pom.xml clean verify`. Uma falha em uma API não
cancela a verificação da outra; o workflow falha se qualquer build falhar.

A fase `verify` executa testes, empacota o JAR e aplica os limites JaCoCo já
configurados: 80% de linhas e 70% de branches. Os testes utilizam H2 e chaves
JWT temporárias; não exigem `.env`, PostgreSQL ou secrets do GitHub.

Na aba **Actions**, abra uma execução para consultar os logs e baixar
`jacoco-gateway-api` e `jacoco-orders-api`. Os relatórios disponíveis são enviados
mesmo quando a verificação de cobertura falha e ficam retidos por sete dias.
Se o build falhar antes de gerar o relatório, o upload informa sua ausência.
Não há deploy nem build Docker neste workflow; a CI prioriza Maven/tests/cobertura.

## Documentação complementar

- [DOCKER.md](DOCKER.md): containers, volumes, configuração e diagnóstico.
- [TESTING.md](TESTING.md): matriz, limites dos testes e execução com PostgreSQL.
- [AUDIT.md](AUDIT.md): checklist final, evidências e limitações da verificação.
- [Gateway API](gateway-api/README.md) e [Orders API](orders-api/README.md):
  explicações detalhadas das implementações.
