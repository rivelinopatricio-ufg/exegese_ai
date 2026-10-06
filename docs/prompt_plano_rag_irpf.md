# Prompt — Plano de Implementação: Chatbot RAG "Perguntas e Respostas IRPF"

> Copie o bloco abaixo e envie para o modelo junto com o PDF **"Perguntas e Respostas – IRPF"** anexado.

---

````markdown
# PAPEL

Você é um **Arquiteto de Software Sênior** especialista em Java 25, Spring Boot 4.x, Spring AI,
sistemas de Retrieval-Augmented Generation (RAG), bancos vetoriais e containerização com Docker.
Você escreve planos técnicos objetivos, justificados e prontos para execução por uma equipe de desenvolvimento.

# OBJETIVO

Produzir um **Plano de Implementação completo** para um chatbot de perguntas e respostas baseado em RAG,
que responde **exclusivamente** com base no documento oficial anexado
**"Perguntas e Respostas – Imposto sobre a Renda da Pessoa Física (IRPF)"** da Receita Federal do Brasil.

Antes de detalhar a implementação, você deve **inferir e justificar a arquitetura ideal**,
comparando alternativas e explicando os trade-offs da escolha final.

# CONTEXTO DO DOCUMENTO-FONTE

- PDF extenso (centenas de páginas), em português, organizado em **capítulos temáticos** e
  **perguntas numeradas** (ex.: "001 — Quem está obrigado a apresentar a declaração?"), cada uma com
  resposta, notas, exemplos, tabelas e referências normativas (leis, INs, decretos).
- Contém cabeçalhos/rodapés repetidos, sumário, remissões cruzadas ("consulte a pergunta 123") e tabelas.
- O documento é atualizado anualmente (novo exercício), portanto a ingestão deve ser **reexecutável e versionada**.

Analise o PDF anexado para confirmar essa estrutura e use-a para definir a estratégia de chunking.

# REQUISITOS FUNCIONAIS

1. O usuário faz perguntas em linguagem natural (português) e recebe respostas coerentes e contextualizadas.
2. Toda resposta deve **citar as fontes** (número da pergunta, título e página do PDF).
3. Se a informação não estiver no documento, o assistente deve declarar isso explicitamente
   e **não inventar** respostas (mitigação de alucinação).
4. Suporte a conversa com histórico (perguntas de acompanhamento com memória de sessão).
5. Painel administrativo simples para (re)ingestão do PDF e visualização do status do índice.

# REQUISITOS TÉCNICOS (SUGERIDOS — VALIDE OU PROPONHA ALTERNATIVAS MELHORES)

- **Backend:** Java 25 (LTS) + Spring Boot 4.x (Spring Framework 7). Utilize recursos modernos quando agregarem
  valor (records, *pattern matching*, *virtual threads* para chamadas de I/O ao LLM). **Verifique e informe a
  compatibilidade** das bibliotecas de RAG (Spring AI / LangChain4j, drivers do banco vetorial, Testcontainers)
  com Spring Boot 4.x, indicando as versões exatas e eventuais pontos de migração (ex.: Jackson 3, Jakarta EE 11).
- **Frontend:** Thymeleaf (renderização server-side). Proponha a interface de usuário
  (layout, componentes, UX de chat, streaming de respostas, exibição de citações, estados de loading/erro,
  responsividade e acessibilidade). Avalie complementos leves como HTMX ou SSE para streaming sem SPA.
- **Orquestração RAG:** avalie **Spring AI** vs **LangChain4j** e escolha um, justificando.
- **LLM e Embeddings:** sugira modelos **com uso gratuito** (mesmo com limites diários). Compare ao menos:
  - Google Gemini API (free tier) — ex.: modelo Flash para geração e `gemini-embedding-001` para embeddings;
  - Groq / OpenRouter (modelos open-weight com free tier);
  - Ollama local (ex.: Llama 3.x / Qwen / Mistral + `nomic-embed-text` / `bge-m3`) — custo zero, sem limites.
  Apresente uma tabela com: qualidade em português, limites gratuitos (RPM/RPD/TPM), latência,
  dimensão do embedding, privacidade e requisitos de hardware. Recomende uma opção principal e uma de *fallback*,
  com a troca de provedor feita apenas por configuração (profiles Spring).
- **Banco vetorial:** compare PGVector, Chroma, Qdrant e o `SimpleVectorStore` em memória. Justifique a escolha.
- **Execução:** tudo deve rodar em **containers Docker** orquestrados via `docker compose`.

# USO DA IA GENERATIVA — DETALHE CADA ETAPA

Para cada item, descreva componentes, classes Java, interfaces, bibliotecas, configurações e pseudocódigo/código-chave:

1. **Processamento de documentos:** funções para extrair texto do PDF (ex.: Apache PDFBox, Apache Tika
   ou `PagePdfDocumentReader` / `ParagraphPdfDocumentReader` do Spring AI); limpeza de cabeçalhos/rodapés;
   tratamento de tabelas; **chunking semântico por pergunta** (uma pergunta + resposta = um chunk, com
   subdivisão para respostas longas e *overlap*); extração de metadados (capítulo, nº da pergunta, título,
   página, exercício/ano, referências normativas).
2. **Geração de embeddings:** modelo escolhido, dimensão, normalização, processamento em lotes respeitando
   *rate limits* (retry com backoff exponencial), cache/idempotência via hash do chunk e persistência no banco vetorial.
3. **Orquestração da cadeia RAG:** pipeline completo — reescrita da pergunta considerando o histórico
   (*query rewriting*), busca semântica top-k com limiar de similaridade, busca híbrida (vetorial + palavra-chave/BM25)
   quando aplicável, *re-ranking*, filtros por metadados, montagem do contexto respeitando a janela de tokens
   e memória de conversa (`ChatMemory` / Advisors).
4. **Geração de respostas:** *system prompt* completo (escreva-o integralmente) com regras de *grounding*,
   formato de citação, tom, idioma e recusa quando não houver contexto; parâmetros (temperature, max tokens);
   streaming via SSE; pós-processamento e validação das citações.
5. **Containerização:** `Dockerfile` multi-stage (build Maven + runtime JRE enxuto, usuário não-root),
   `docker-compose.yml` (app, banco vetorial, opcionalmente Ollama), *healthchecks*, volumes persistentes,
   variáveis de ambiente e gestão segura da API key (nunca versionada; `.env.example`).

# ENTREGÁVEIS DO PLANO (ESTRUTURA OBRIGATÓRIA)

1. **Resumo executivo.**
2. **Inferência da arquitetura ideal:** alternativas avaliadas, matriz de decisão e justificativa final
   (registre as decisões no formato ADR resumido: Contexto → Decisão → Consequências).
3. **Stack tecnológica final** com versões.
4. **Diagramas em Mermaid** (sintaxe válida, rótulos com caracteres especiais entre aspas):
   - Diagrama de contexto (C4 nível 1);
   - Diagrama de containers (C4 nível 2) com os serviços Docker;
   - Diagrama de componentes do backend Spring Boot;
   - Fluxo de **ingestão** (PDF → extração → chunking → embeddings → banco vetorial) — *flowchart*;
   - Fluxo de **consulta/resposta** (pergunta → reescrita → retrieval → prompt → LLM → resposta com citações) — *sequenceDiagram*;
   - Diagrama de classes das principais entidades/serviços;
   - Diagrama de implantação (containers, portas, volumes, rede).
5. **Estrutura de pastas e pacotes** do projeto Maven.
6. **Modelo de dados** (schema da tabela vetorial e metadados).
7. **Design da interface de usuário:** wireframe descritivo (ou ASCII), páginas Thymeleaf, fragmentos,
   CSS, comportamento do chat, exibição das fontes clicáveis e página de administração.
8. **Endpoints** (MVC e REST/SSE) com exemplos de request/response.
9. **Configuração** (`application.yml` com profiles por provedor de IA).
10. **Plano de testes:**
    - Unitários (JUnit 5 + Mockito) para extração, chunking, montagem de prompt;
    - Integração com **Testcontainers** (banco vetorial) e mocks do LLM (WireMock);
    - Testes de controller (MockMvc) e de UI (opcional: Playwright/Selenium);
    - **Avaliação de qualidade do RAG:** *golden dataset* com no mínimo 20 perguntas reais do documento,
      resposta esperada e pergunta-fonte; métricas (precisão@k / recall@k do retrieval, fidelidade,
      relevância da resposta, taxa de recusa correta para perguntas fora do escopo);
    - Liste os casos de teste em tabela: ID, descrição, pré-condição, entrada, resultado esperado, tipo.
11. **README.md completo** (escreva o conteúdo integral): visão geral, arquitetura (com diagrama), pré-requisitos,
    como obter a API key gratuita, instalação, configuração, execução, ingestão do PDF, uso, testes,
    troubleshooting, limitações e licença.
12. **Script bash de instalação** `install.sh` (escreva o conteúdo integral), que deve:
    - usar `set -euo pipefail` e mensagens coloridas de progresso;
    - verificar pré-requisitos (Docker, Docker Compose, curl) e versões mínimas;
    - criar o `.env` a partir do `.env.example` solicitando a API key de forma interativa (entrada oculta)
      ou aceitando-a via variável de ambiente/flag;
    - validar a presença do PDF (ou baixá-lo da URL oficial, se informada);
    - executar `docker compose build` e `up -d`, aguardar os *healthchecks*;
    - disparar a ingestão inicial e exibir a URL de acesso;
    - ser idempotente e oferecer flags `--help`, `--no-ingest` e `--uninstall`.
13. **Segurança, observabilidade e custos:** proteção da API key, limitação de taxa por usuário,
    sanitização de entrada contra *prompt injection*, logs estruturados, métricas (Actuator/Micrometer)
    e estratégia para operar dentro dos limites gratuitos.
14. **Roadmap em fases** (MVP → melhorias) com tarefas, estimativas e critérios de aceite.
15. **Riscos e mitigações.**

# RESTRIÇÕES E BOAS PRÁTICAS

- Injeção de dependências **por construtor**, campos `private final`.
- Código, comentários e JavaDoc em **inglês**; textos da interface e do README em **português**.
- Todos os arquivos em **UTF-8**.
- Não invente APIs: utilize somente classes e métodos existentes nas versões indicadas; quando houver
  incerteza sobre uma API, sinalize explicitamente.
- Prefira simplicidade: evite componentes sem justificativa clara (YAGNI).

# FORMATO DA RESPOSTA

- Markdown bem estruturado, com títulos numerados conforme os entregáveis acima.
- Tabelas para comparações e casos de teste.
- Blocos de código com linguagem indicada (`java`, `yaml`, `dockerfile`, `bash`, `mermaid`, `html`).
- Ao final, inclua um **checklist de verificação** de que todos os 15 entregáveis foram atendidos.
````
