# Auditoria de Segurança e Técnica — Exegese AI (outubro de 2026)

Escopo: código Java (controllers, services, security, config), templates Thymeleaf, `application*.properties`, `pom.xml`,
Dockerfiles, arquivos `docker-compose*.yml`, configuração NGINX/SWAG, `install.sh` e histórico do git.
Stack auditada: Spring Boot 4.1.1, Spring AI 2.0.1, Spring Security 7, Java 25, PostgreSQL 17 + pgvector, SWAG (NGINX).

As correções foram feitas no branch `claude/gracious-darwin-07xqcg`, em um commit por grupo de achados.

## Legenda de status

| Status | Significado |
| :--- | :--- |
| **corrigido** | O código ou a configuração do repositório elimina o problema. |
| **mitigado** | O risco foi reduzido, mas o controle não é completo (ver observação). |
| **pendente-operacional** | Depende de uma ação do operador nas instalações já existentes (Fase 0, ver README, seção 3.6). |

## Commits da remediação

| Commit | Grupo | Assunto |
| :--- | :--- | :--- |
| `0f8000f` | G1 build | Build Maven endurecido, dependências atualizadas, Maven Wrapper, Dependabot, testes herméticos |
| `5b6c1eb` | G2 segredos/infra | Chave AES obrigatória, credenciais do Postgres geradas, Ollama privado |
| `7c364a9` | G3 autenticação/autorização | Dono da sessão de chat, política de login, busca só em assuntos ativos |
| `3fce5d7` | G4 pipeline do chat | Chat em dois passos com CSRF, prompt por requisição, recursos limitados |
| `29c282c` | G5 LLM/embeddings/busca | Embeddings Gemini com reindexação, busca limitada, grounding real, allowlist de endpoints |
| `5db37b9` | G6 ingestão/schema/prod | Ingestão assíncrona, originais guardados, schema do Flyway, padrões seguros de produção |
| `1bed9d5` | G7 front-end/CSP | Tailwind compilado, scripts externos, CSP estrita |
| `52191d4` | G8 Docker/CI | Imagem em camadas e não-root, compose endurecido, configuração SWAG efetiva, CI |

## Achados de segurança

### Críticos

| Código | Achado | Severidade | Status | Commits |
| :--- | :--- | :--- | :--- | :--- |
| C1 | Chave mestra AES fixa no código. `EXEGESE_AES_SECRET` nunca chegava a `exegese.security.crypto-key`, a derivação completava a chave com zeros e não havia fail-fast. | Crítica | **corrigido** no código; **pendente-operacional**: rotacionar as chaves de API gravadas nas instalações antigas | `5b6c1eb` |
| C2 | PostgreSQL publicado em todas as interfaces (as portas do Docker ignoram o UFW), com a senha fixa e pública `exegese_password`. | Crítica | **corrigido** no repositório; **pendente-operacional**: trocar a senha com `ALTER USER`, atualizar o `.env` e fechar a 5432 no firewall e no security group | `5b6c1eb`, `52191d4` |
| C3 | IDOR: qualquer usuário autenticado lia (`GET /chat/{id}`) e escrevia (stream) nas conversas de outros usuários. | Crítica | **corrigido** | `7c364a9`, `3fce5d7` |

Observações:
- **C1**: a chave agora é Base64 de 32 bytes, obrigatória (a aplicação não sobe sem ela), e os valores são gravados no formato `v1:`.
  O `ApiKeyEncryptionMigrationRunner` recifra automaticamente, no startup, os valores legados cifrados com a chave fixa.
  O `install.sh` gera a chave com `openssl rand -base64 32` e preserva a existente ao reconfigurar.
- **C2**: o compose não publica a porta 5432 e exige `POSTGRES_USER`/`POSTGRES_PASSWORD`, sem valores padrão. O `install.sh` gera
  `exegese_<hex>` e uma senha aleatória e preserva as existentes. O compose avulso do banco (`docker/postgres`) publica só em `127.0.0.1`.
  O `52191d4` fixou a versão da imagem e adicionou `no-new-privileges`.

### Altos

| Código | Achado | Severidade | Status | Commits |
| :--- | :--- | :--- | :--- | :--- |
| A1 | O RBAC por assunto não era aplicado e a busca com lista vazia incluía os assuntos inativos. Cadastro aberto a qualquer conta Google. | Alta | **corrigido**. Decisão do usuário: os assuntos ativos são públicos e `user_subject_permission` é só organizacional. | `7c364a9` |
| A2 | Desativar um usuário não tinha efeito, e mudanças de papel só valiam após um novo login. | Alta | **corrigido** | `7c364a9` |
| A3 | Admin inicial padrão `admin@exegese.ai` e `email_verified` não verificado. | Alta | **corrigido** | `7c364a9` |
| A4 | Rate limit burlável por `X-Forwarded-For` forjado, e um mapa sem expiração permitia DoS de memória. | Alta | **corrigido** | `3fce5d7`, `52191d4` |
| A5 | Chat esgotava recursos: `commonPool`, transação aberta durante o LLM, sem cancelamento e com GET que alterava estado sem CSRF. | Alta | **corrigido** | `3fce5d7` |
| A6 | System prompt em um campo estático global, alterável por visitantes anônimos via `?lang=`. | Alta | **corrigido** | `3fce5d7` |
| A7 | O keystore de desenvolvimento `cert.p12` ia para o JAR e para a imagem. Rebaixado para Baixa porque é só de desenvolvimento. | Baixa | **corrigido**: excluído do JAR e do contexto Docker, e o CI verifica a imagem | `0f8000f`, `52191d4` |
| A8 | Ollama publicado sem autenticação na 11434 (ignorando o UFW), fora da rede `exegese-net`. | Alta | **corrigido** no repositório; **pendente-operacional**: fechar a 11434 no firewall e no security group | `5b6c1eb`, `52191d4` |

Observações:
- **A1**: toda recuperação filtra por assuntos ativos (os IDs pedidos ∩ os ativos; sem IDs, entram os documentos com assunto ativo ou sem
  assunto). A nova propriedade `exegese.security.allowed-email-domains` (`ALLOWED_EMAIL_DOMAINS`) restringe o login a domínios.
- **A2/A3**: o `CustomOidcUserService` recusa contas inativas, e-mails não verificados e domínios fora da allowlist. O
  `AccountStatusFilter` invalida a sessão de contas desativadas e atualiza as autoridades após mudança de papel. `INITIAL_ADMIN_EMAIL`
  não tem padrão, só promove enquanto não existir nenhum admin e é exigido pelo `install.sh`.
- **A4**: a chave do rate limit é a conta ou `getRemoteAddr()`, com `forward-headers-strategy=native` e proxies internos confiáveis,
  guardada em um cache Caffeine limitado. O NGINX de borda sobrescreve `X-Forwarded-For` com `$remote_addr`. Até o `52191d4`, o SWAG
  **não carregava** a configuração do site, porque o arquivo era montado como `site-confs/default` e o SWAG só inclui `*.conf`. Ver T11.

### Médios

| Código | Achado | Severidade | Status | Commits |
| :--- | :--- | :--- | :--- | :--- |
| M1 | CSP fraca (`'unsafe-inline'`, Tailwind Play CDN sem SRI) e sem `frame-ancestors`/`base-uri`/`form-action`/`object-src`. | Média | **corrigido** | `1bed9d5` |
| M2 | Mensagens de exceção vazavam para o cliente (evento SSE `error`, flash do upload). | Média | **corrigido**: mensagem genérica com código de referência; o detalhe fica só no log | `3fce5d7`, `5db37b9` |
| M3 | SSRF e exfiltração da chave de API via `baseUrl` editável pelo admin. | Média | **corrigido**: `https` e allowlist de hosts por provider (`LLM_ALLOWED_HOSTS`); `http` só para o Ollama local | `29c282c` |
| M4 | `InputSanitizationFilter` era uma blacklist regex contornável. | Média | **mitigado**: a defesa principal passou a ser estrutural (pergunta e contexto em seções delimitadas tratadas como dados pelo prompt), e o filtro ficou como camada auxiliar que rejeita caracteres de controle e registra as rejeições sem o texto. Nenhum filtro elimina prompt injection por completo. | `3fce5d7` |
| M5 | Upload sem validar o tipo (magic bytes) nem limitar páginas; processamento síncrono e em memória. | Média | **corrigido** | `5db37b9` |
| M6 | Configuração de desenvolvimento em produção: cache de templates desligado, logs DEBUG, `ddl-auto=update`, perfil `local`, cookie sem `Secure`/`SameSite`. | Média | **corrigido** | `5db37b9`, `52191d4` |
| M7 | Supply chain: repositórios milestone e snapshot da Spring e imagens `:latest`. | Média | **corrigido** | `0f8000f`, `52191d4` |

Observações:
- **M6**: o perfil `prod` está no compose (`5db37b9`) e também na imagem (`SPRING_PROFILES_ACTIVE=prod`, `52191d4`).
- **M7**: os repositórios foram removidos e o Dependabot foi adicionado no `0f8000f`. As imagens foram fixadas no `52191d4`:
  `pgvector/pgvector:0.8.7-pg17`, `linuxserver/swag:5.8.0`, `ollama/ollama:0.40.1` e `eclipse-temurin:25-*-noble`.
  Elas usam tags de versão, não digest, para receber os rebuilds de segurança. As imagens locais `br.org.rivelino/exegese-ai*:latest`
  são construídas pelo próprio compose.

### Baixos / boas práticas

| Achado | Status | Commits |
| :--- | :--- | :--- |
| Usuário genérico `default.user@exegese.ai` criado quando faltava o e-mail | **corrigido**: removido; sem e-mail, não há acesso | `7c364a9` |
| `AdminUserController`: nível livre, sem revoke, auto-rebaixamento e último admin desprotegidos, sem trilha | **corrigido**: enum, endpoint de revoke, proteções e log das ações administrativas | `7c364a9` |
| `install.sh` recebe segredos por flags de CLI (visíveis em `ps` e no histórico) | **mitigado**: aceita variáveis de ambiente e avisa sobre as flags, que continuam aceitas por compatibilidade | `5b6c1eb` |
| `cap_add: NET_ADMIN` do proxy sem justificativa | **corrigido**: documentado como necessário apenas para o fail2ban do SWAG | `52191d4` |

## Falhas técnicas

| Código | Achado | Status | Commits |
| :--- | :--- | :--- | :--- |
| T1 | Embeddings sempre zerados (`EmbeddingModel` `@Primary` de fallback) | **corrigido**: `gemini-embedding-001` com 768 dimensões e vetores validados; sem chave, a busca usa só texto completo. **pendente-operacional**: rodar **Reindexar embeddings** em `/admin/documents` | `29c282c` |
| T2 | Fallback carregava todos os chunks em memória a cada pergunta | **corrigido**: FTS estrita, depois relaxada, sempre com `LIMIT` | `29c282c` |
| T3 | `AntiHallucinationGuard` inócuo | **corrigido**: similaridade cosseno real contra o `similarity-threshold`, ou evidência lexical | `29c282c` |
| T4 | Status `FAILED` perdido no rollback, hash de chunk único global e original do PDF não guardado | **corrigido** | `5db37b9` |
| T5 | Três fontes de verdade para o schema | **corrigido**: o Flyway é o único dono (V1 baseline + V2/V3 idempotentes) | `5db37b9` |
| T6 | `@PostConstruct` + `@Transactional` sem transação | **corrigido**: `ApplicationRunner` | `29c282c` |
| T7 | Ping do admin não testava nada | **corrigido**: requisição mínima real com timeout | `29c282c` |
| T8 | Ollama inconsistente (modelo padrão, URL e rede) | **corrigido** | `5b6c1eb` |
| T9 | Jackson 2 com `ObjectMapper` cru | **corrigido**: `JsonMapper` (Jackson 3) gerenciado pelo Boot | `29c282c` |
| T10 | Só H2, sem Testcontainers e sem CI | **corrigido**: testes Testcontainers (Flyway, pgvector, FTS, reindexação) e GitHub Actions (build, testes, CSS, imagens, compose) | `29c282c`, `5db37b9`, `52191d4` |
| T11 | Proxy: `sed`/`COPY` sem efeito em `/config`; `X-Accel-Buffering` enviado como header de requisição | **corrigido**. Ver observação abaixo. | `52191d4` |

Observação sobre **T11**: o Dockerfile do proxy agora só fixa a imagem do SWAG. A configuração do site é montada como
`site-confs/default.conf`, e o `install.sh` gera exatamente o arquivo versionado. Com isso foram removidos o header inútil, a rota SSE
`/api/chat/stream/` e o `proxy_cache_path` sem uso.

## Desatualização

| Item | Antes | Depois | Commits |
| :--- | :--- | :--- | :--- |
| PDFBox | 3.0.4 | 3.0.8 | `0f8000f` |
| Bucket4j | `bucket4j-core` 8.10.1 (artefato descontinuado) | `bucket4j_jdk17-core` 8.21.0 | `0f8000f` |
| IDs de modelo padrão | `claude-3-7-sonnet`, `gpt-4o`, … | `claude-sonnet-5-5`, `gpt-5-mini`, com migração automática | `29c282c` |
| Flag da JVM | `-XX:+ZGenerational` (obsoleta desde o JDK 24) | removida; `-XX:MaxRAMPercentage=75` adicionada | `52191d4` |
| Maven no build da imagem | `apt install maven` | Maven Wrapper (Maven 3.9.11, SHA-256 fixado) | `0f8000f`, `52191d4` |
| Compose | `version: "3.8"` | campo removido | `5b6c1eb` |

## Ações operacionais pendentes (Fase 0)

Estas ações ficam com o operador de cada instalação já publicada (passo a passo no README, seção 3.6):

1. Rotacionar todas as chaves de API de LLM e o *client secret* do Google OAuth2 (C1 + C2 permitiam roubá-las).
2. Trocar a senha do PostgreSQL com `ALTER USER` e atualizar o `.env` (C2).
3. Fechar as portas 5432 e 11434 no firewall do host e no security group da nuvem (C2, A8).
4. Definir `EXEGESE_AES_SECRET` (`openssl rand -base64 32`), se ainda não houver um valor válido. Os ciphertexts legados são migrados
   automaticamente no startup.
5. Reindexar os embeddings em `/admin/documents` (T1).

## Limites da verificação

- **Build local com JDK 21**: o projeto tem Java 25 como alvo, mas o ambiente da remediação tinha só o JDK 21 (o download do JDK 25 é
  bloqueado). A validação local usou `mvn -B -Djava.version=21 verify` (181 testes, dos quais 5 Testcontainers ignorados por falta de Docker).
  O código evita APIs exclusivas do Java 22+. A compilação e os testes no JDK 25 ficam com o CI (`./mvnw -B verify` no Temurin 25).
- **Docker e Testcontainers só no CI**: não havia daemon Docker. Os testes Testcontainers (`FlywayMigrationContainerTest`,
  `PgVectorHybridSearchContainerTest`) compilam, mas só rodam no GitHub Actions. O build das imagens da aplicação e do proxy e a
  inicialização real do NGINX/SWAG também não foram executados localmente.
- **O que foi validado localmente**:
  - `docker compose config` de todos os arquivos compose, com variáveis fictícias.
  - `actionlint` no workflow, `shellcheck` no `install.sh` e `hadolint` nos Dockerfiles. O hadolint só deixou avisos de pin de versão do
    `apt` e da forma shell do `HEALTHCHECK`, ambos aceitos.
  - A extração em camadas (`java -Djarmode=tools -jar app.jar extract --layers`) com o JAR gerado pelo Boot 4.1.1.
  - A igualdade entre `docker/proxy/config/default` e a saída do gerador do `install.sh` com as portas padrão.
  - A regeneração idêntica do `tailwind.css`.
- **Ainda não verificado** (fazer após o deploy): `ss -tlnp` e `nmap` externo mostrando só 80/443; o app alcançando `ollama:11434`;
  streaming SSE pelo SWAG; ausência de violações de CSP no navegador; e confirmar que um `api_key_encrypted` novo não decifra com a chave legada.
