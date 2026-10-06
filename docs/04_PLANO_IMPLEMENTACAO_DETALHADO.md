# EXEGESE AI — PLANO DE IMPLEMENTAÇÃO DETALHADO POR ETAPAS SEQUENCIAIS

**Roteiro Técnico Incremental de Construção, Testes e Homologação**  
*Pacote: br.org.rivelino.exegese_ai | Runtime: Java 25 LTS | Spring Boot 4.x*

---

## 1. VISÃO GERAL DO PLANO DE ENTREGA

O plano de implementação é estruturado em **12 etapas sequenciais e independentemente verificáveis**. Cada etapa possui pré-requisitos claros, tarefas atômicas, artefatos gerados e critérios objetivos de aceite.

```
Etapa 01: Setup Base & Docker ──────► Etapa 02: DDL & Domínio ──────► Etapa 03: Google OAuth2 & RBAC
                                                                                  │
Etapa 06: Ingestão & Chunking ◄───── Etapa 05: Assuntos & Docs ◄───── Etapa 04: Admin Usuários
         │
         ▼
Etapa 07: Busca Híbrida (RRF) ──────► Etapa 08: Multi-Provedores IA ──► Etapa 09: Orquestrador RAG SSE
                                                                                  │
Etapa 12: Deploy & install.sh ◄───── Etapa 11: Golden Dataset ◄────── Etapa 10: UI Reativa HTMX
```

---

## 2. ETAPAS DETALHADAS DE IMPLEMENTAÇÃO

### ETAPA 01: Setup do Projeto Base Maven, Pacotes e Infraestrutura Docker
- **Objetivo**: Inicializar o repositório estruturado, configurar o `pom.xml` oficial e subir a infraestrutura de dados em container.
- **Tarefas**:
  1. Configurar o `pom.xml` com:
     - GroupId: `br.org.rivelino`
     - ArtifactId: `exegese-ai`
     - Pacote Java: `br.org.rivelino.exegese_ai`
     - Java 25 LTS, Spring Boot 4.x / 3.4.2 LTS, Spring AI 1.0.0-M6, Apache PDFBox 3.0.4, Bucket4j 8.10.1.
  2. Configurar o `docker-compose.yml` para orquestrar o container `exegese-ai-db` com imagem `pgvector/pgvector:pg17` e volumes persistentes.
  3. Criar os diretórios do projeto no padrão Maven (`src/main/java/br/org/rivelino/exegese_ai/...`).
  4. Configurar `application.yml` inicial apontando para o banco local.
- **Critério de Aceite**: Execução de `mvn clean compile` com sucesso em Java 25 e PostgreSQL 17 respondendo na porta 5432 com extensão `vector` ativa.

---

### ETAPA 02: Banco de Dados, Migrações e Modelo de Entidades
- **Objetivo**: Definir a estrutura de tabelas relacionais e vetoriais, integrando busca HNSW e Full Text Search.
- **Tarefas**:
  1. Criar o script DDL `schema.sql` com as tabelas:
     - `exegese_user`, `exegese_subject`, `user_subject_permission`
     - `exegese_document`, `document_subject` (relação N:N)
     - `exegese_chunk` (com coluna `embedding VECTOR(768)` e `tsv TSVECTOR`)
     - `ai_model_config`, `chat_session`, `chat_message`.
  2. Criar os índices HNSW para cosseno (`idx_exegese_chunk_hnsw`), GIN para texto (`idx_exegese_chunk_tsv`) e GIN para JSONB (`idx_exegese_chunk_metadata`).
  3. Mapear as entidades JPA em `domain.entity` e os repositórios Spring Data em `repository`.
- **Critério de Aceite**: Teste de integração com Testcontainers persistindo e recuperando um registro de documento associado a múltiplos assuntos e um chunk vetorial.

---

### ETAPA 03: Autenticação Google OAuth2 / OIDC & Segurança RBAC
- **Objetivo**: Proteger a aplicação via login corporativo com Google, provisionar usuários locais e habilitar controle de acesso baseado em papéis.
- **Tarefas**:
  1. Adicionar dependência `spring-boot-starter-oauth2-client`.
  2. Implementar `SecurityConfiguration` liberando rotas públicas (`/login`, `/css/**`, `/js/**`, `/actuator/health`) e exigindo autenticação nas demais.
  3. Implementar `CustomOidcUserService` para capturar os dados do Google (e-mail, nome, avatar) no login.
  4. Implementar `GoogleOAuth2SuccessHandler` com lógica de **bootstrap automático**:
     - Se o e-mail autenticado coincidir com `INITIAL_ADMIN_EMAIL` do `.env`, atribuir automaticamente `ROLE_ADMIN`.
     - Caso contrário, cadastrar como novo usuário ativo com `ROLE_USER`.
  5. Configurar anotações `@PreAuthorize("hasRole('ADMIN')")` para rotas do módulo administrativo.
- **Critério de Aceite**: Teste MockMvc garantindo redirecionamento para o Google, criação correta do primeiro ADMIN e bloqueio de usuários sem papel a endpoints administrativos.

---

### ETAPA 04: Painel Administrativo — Gestão de Usuários e Permissões
- **Objetivo**: Fornecer interface web para o Administrador gerenciar a equipe e conceder permissões granulares por assunto.
- **Tarefas**:
  1. Criar `AdminUserController` mapeando `/admin/users`.
  2. Desenvolver a view Thymeleaf `admin/users.html` listando usuários cadastrados, papel atual e data do último acesso.
  3. Implementar endpoint `POST /admin/users/{id}/role` para promoção/rebaixamento de papéis (`ROLE_ADMIN`, `ROLE_OPERATOR`, `ROLE_USER`).
  4. Implementar modal para concessão de permissões de leitura/gestão nos assuntos documentais (`user_subject_permission`).
  5. Implementar botão de suspensão/ativação de acesso (`toggleActive`).
- **Critério de Aceite**: Administrador altera papel de um usuário e concede acesso a assuntos específicos, refletindo imediatamente na sessão do usuário.

---

### ETAPA 05: Gestão de Assuntos (Topics) e Catálogo de Documentos
- **Objetivo**: Permitir a criação de categorias/assuntos documentais e listagem do acervo.
- **Tarefas**:
  1. Criar `AdminSubjectController` mapeando `/admin/subjects` para CRUD de assuntos (`ExegeseSubject`).
  2. Desenvolver a view `admin/subjects.html` permitindo criar novos assuntos (ex.: "Tributário - IRPF 2026", "Legislação Trabalhista", "Normas Internas").
  3. Criar `AdminDocumentController` mapeando `/admin/documents` com filtros por assunto e status.
  4. Desenvolver a visualização com tabela de documentos, quantidade de páginas, tamanho, data de envio e assuntos vinculados (tags).
- **Critério de Aceite**: Assunto "Tributário - IRPF 2026" cadastrado no banco e visualizado no catálogo de assuntos.

---

### ETAPA 06: Engine de Ingestão de Documentos & Chunking Polimórfico
- **Objetivo**: Extrair texto de PDFs, aplicar estratégias de chunking especializadas, gerar embeddings e persistir com idempotência.
- **Tarefas**:
  1. Implementar `PdfTextExtractor` utilizando Apache PDFBox 3.0.4 para extração textual com paginação e eliminação de cabeçalhos/rodapés repetitivos.
  2. Implementar a fábrica `SegmentationStrategyFactory` e as estratégias:
     - `StructuredQuestionSegmentationStrategy`: Identifica marcadores canônicos `^\d{3}\s*[—–-]` do manual do IRPF 2026, isolando questão, resposta e base legal em um chunk atômico.
     - `LegalSectionSegmentationStrategy`: Particiona por Artigos e Parágrafos.
     - `GeneralSegmentationStrategy`: Chunking recursivo por parágrafos com 15% de overlap.
  3. Implementar cálculo de hash criptográfico SHA-256 por chunk para garantir idempotência na ingestão (não reprocessar chunks idênticos já vetorizados).
  4. Implementar processamento em lotes (batches de 20 chunks) com backoff exponencial contra limites de taxa da API de embeddings (`text-embedding-004`).
  5. Vincular o documento aos assuntos selecionados no upload via tabela `document_subject` (relação N:N).
- **Critério de Aceite**: Ingestão do PDF oficial do IRPF 2026 (340 páginas) gerando os 745 chunks canônicos identificados, sem perda de texto e com metadados completos de página e lei.

---

### ETAPA 07: Mecanismo de Busca Híbrida e Fusão de Rankings (RRF)
- **Objetivo**: Unificar busca semântica vetorial e busca léxica por palavras-chave com restrição aos assuntos selecionados pelo usuário.
- **Tarefas**:
  1. Criar `HybridSearchService` injetando `VectorStore`, `EmbeddingModel` e `JdbcTemplate`.
  2. Implementar a consulta vetorial HNSW com filtro por coleção e assuntos:
     `SELECT c.* FROM exegese_chunk c JOIN document_subject ds ON ... WHERE ds.subject_id IN (:subjects) ORDER BY embedding <=> :queryVector LIMIT 10`.
  3. Implementar a consulta textual FTS em português:
     `SELECT c.* FROM exegese_chunk c JOIN document_subject ds ON ... WHERE ds.subject_id IN (:subjects) AND tsv @@ plainto_tsquery('portuguese', :query) ORDER BY ts_rank_cd(tsv, plainto_tsquery('portuguese', :query)) DESC LIMIT 10`.
  4. Implementar o cálculo de fusão de posições **Reciprocal Rank Fusion (RRF)**:
     $$Score_{RRF}(d) = \frac{1}{60 + rank_{vector}} + \frac{1}{60 + rank_{text}}$$
  5. Retornar os Top-4 documentos consolidados.
- **Critério de Aceite**: Consultas por conceitos gerais e por números exatos de leis (ex.: "Lei 14.754" ou "Dabim") retornam a pergunta exata da RFB na 1ª posição.

---

### ETAPA 08: Roteamento Multi-Provedor de IA & Gestão de Credenciais
- **Objetivo**: Permitir a troca dinâmica do LLM ativo entre os 6 provedores suportados com gestão segura de chaves de API.
- **Tarefas**:
  1. Criar enum `ModelProvider` (`GEMINI`, `CLAUDE`, `OPENAI`, `NEMOTRON`, `DEEPSEEK`, `OLLAMA_LOCAL`).
  2. Implementar `LlmProviderRouter` que constrói instâncias de `ChatClient` dinamicamente conforme o provedor ativo cadastrado na tabela `ai_model_config`.
  3. Implementar `CryptoService` para encriptar e decriptar chaves de API informadas na UI com **AES-256-GCM**.
  4. Suportar fallback automático: se a chave não estiver no banco, buscar na variável de ambiente correspondente (`GEMINI_API_KEY`, `ANTHROPIC_API_KEY`, etc.).
  5. Criar tela administrativa `/admin/models` para selecionar o modelo padrão e testar a conectividade da API com um prompt de ping.
- **Critério de Aceite**: O Administrador seleciona "Anthropic Claude" no painel e a próxima pergunta do usuário é processada pelo Claude sem necessidade de reiniciar a aplicação.

---

### ETAPA 09: Orquestração do RAG, Anti-Alucinação e Streaming SSE
- **Objetivo**: Conectar o fluxo de pergunta do usuário à reescrita de prompt, validação de limiares e emissão de streaming reativo.
- **Tarefas**:
  1. Implementar `QueryRewritingService` para resolver anáforas e referências em conversas encadeadas (ex.: "E se for no exterior?").
  2. Implementar `AntiHallucinationGuard` que verifica se o score de similaridade dos documentos recuperados atinge o limiar mínimo ($\ge 0.65$).
  3. Se o limiar não for atingido, emitir imediatamente a recusa canônica:
     *"Essa informação não consta nos documentos dos assuntos selecionados."*
  4. Se o contexto for válido, montar o prompt estruturado com o *System Prompt* institucional do Exegese AI e invocar o streaming do `ChatClient`.
  5. Emitir eventos SSE formatados: `event: token` para texto progressivo e `event: citation` para as fontes identificadas.
- **Critério de Aceite**: Resposta fluida em tempo real via SSE com citação obrigatória de número de pergunta, título e página.

---

### ETAPA 10: Interface Conversacional do Usuário (Thymeleaf + HTMX + SSE)
- **Objetivo**: Construir a interface web de chat acessível, moderna e reativa.
- **Tarefas**:
  1. Criar `ChatViewController` servindo a página principal `/`.
  2. Desenvolver componente de seleção múltipla de Assuntos disponíveis ao usuário autenticado (chips ou checkboxes interativos).
  3. Implementar integração com JavaScript e HTMX para abrir a conexão SSE com `/api/chat/stream` ao submeter a pergunta.
  4. Renderizar a resposta progressivamente no balão de chat com efeito de digitação suave.
  5. Ao receber o evento de citação, renderizar os chips de fontes clicáveis.
  6. Desenvolver modal acessível que exibe o trecho canônico do documento e os artigos de lei ao clicar no chip da fonte.
  7. Adicionar botão de limpar conversa e seletor de tema claro/escuro.
- **Critério de Aceite**: Usuário realiza consulta filtrando assuntos, acompanha o streaming contínuo no navegador e visualiza as fontes no modal.

---

### ETAPA 11: Avaliação de Qualidade (Golden Dataset) e Hardening de Segurança
- **Objetivo**: Validar a acurácia das respostas e a robustez contra abusos e ataques de injeção.
- **Tarefas**:
  1. Executar a suíte de testes de avaliação `RagQualityEvaluationTest` contendo o Golden Dataset de 20 perguntas oficiais do IRPF 2026 e a pergunta de controle fora do escopo.
  2. Implementar `RateLimitFilter` utilizando **Bucket4j** (20 requisições por minuto por IP/usuário com resposta HTTP 429).
  3. Implementar `InputSanitizationFilter` para bloquear tentativas de *Prompt Injection* (delimitadores de sistema, tags maliciosas e excesso de caracteres).
  4. Configurar cabeçalhos HTTP de segurança (CSP, X-Frame-Options, HSTS).
- **Critério de Aceite**: 100% das 20 perguntas oficiais respondidas com fatos corretos, 100% de recusa na pergunta fora de escopo e bloqueio ativo de tentativas de prompt injection.

---

### ETAPA 12: Empacotamento Docker, Script `install.sh` e Documentação Final
- **Objetivo**: Entregar a solução completa empacotada para implantação em produção.
- **Tarefas**:
  1. Construir e testar o `Dockerfile` multi-stage com runtime JRE 25 enxuto e usuário não-root.
  2. Consolidar o script de instalação `install.sh` com:
     - `set -euo pipefail` e mensagens coloridas.
     - Validação de pré-requisitos (`docker`, `docker compose`, `curl`).
     - Leitura mascarada de chaves e criação do `.env`.
     - Flags `--help`, `--no-ingest` e `--uninstall`.
     - Healthcheck em loop aguardando status `UP`.
  3. Consolidar `README.md` completo com instruções operacionais em português.
- **Critério de Aceite**: Execução de `./install.sh` em máquina limpa sobe o ambiente completo e disponibiliza o sistema pronto para uso em `http://localhost:8080`.