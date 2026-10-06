# FASE 10 — PLANO DE IMPLEMENTAÇÃO DETALHADO
## Interface Conversacional do Usuário (Thymeleaf + HTMX + SSE)

**Projeto**: Exegese AI (`exegese-ai`)  
**Pacote Base**: `br.org.rivelino.exegese_ai`  
**Runtime**: Java 25 LTS | Spring Boot 3.4.2 LTS | Thymeleaf | Server-Sent Events (SSE)  
**Data**: 2026-10-06  

---

## 1. OBJETIVO DA FASE 10
Desenvolver a interface conversacional reativa da plataforma Exegese AI, implementando o layout Clean & Authoritative, suporte a temas Claro/Escuro, navegação e histórico de sessões de chat, seletor de múltiplos assuntos e streaming de respostas em tempo real via Server-Sent Events (SSE) com modais para inspeção de citações canônicas.

---

## 2. CHECKLIST DE TAREFAS

- [ ] **Tarefa 10.1: Controlador da View Conversacional (`ChatViewController`)**:
  - Mapear rotas `/` e `/chat/{sessionId}` para renderização da página principal.
  - Criar endpoint `POST /chat/new` para abertura de novas conversas.
  - Vincular sessões e histórico de mensagens ao usuário autenticado via `SecurityContextFacade`.
- [ ] **Tarefa 10.2: Endpoint de Streaming Reativo (`ChatApiController`)**:
  - Mapear `/api/chat/stream` com suporte a `GET` e `POST`, gerando `MediaType.TEXT_EVENT_STREAM_VALUE`.
  - Execução assíncrona integrada ao `RagOrchestrationService`.
- [ ] **Tarefa 10.3: Design System e Estilização (`app.css`)**:
  - Tokens CSS institucionais para temas claro e escuro.
  - Animação de cursor pulsante para efeito de digitação (`.cursor-blink`).
  - Chips de citação oficial (`.citation-chip`) e modal acessível com backdrop blur.
- [ ] **Tarefa 10.4: Template Thymeleaf (`index.html`)**:
  - Barra lateral com filtro de múltiplos assuntos e histórico de sessões.
  - Balões de conversa responsivos para usuário e assistente.
  - Script cliente consumindo SSE com EventSource.
  - Modal interativo para abertura dos metadados da citação ao clicar no chip da fonte.
- [ ] **Tarefa 10.5: Suíte de Testes Automatizados (`ChatInterfaceIntegrationTest`)**:
  - Testar carregamento da rota principal com modelo populado.
  - Testar criação de nova sessão de chat.
  - Testar resposta assíncrona do endpoint SSE.
  - Testar redirecionamento de usuários não autenticados.
- [ ] **Tarefa 10.6: Execução de Testes e Homologação**:
  - Execução de `mvn test` garantindo 100% de sucesso.
- [ ] **Tarefa 10.7: Resumo Técnico e Commit**:
  - Gerar `docs/implementation/FASE 10 - Resumo Técnico.md`.
  - Realizar commit convencional em inglês.

---

## 3. CRITÉRIOS DE ACEITE
1. Interface do chat reativa e acessível exibindo histórico e filtros.
2. Streaming SSE em tempo real com cursor visual e chips de citação clicáveis.
3. Abertura de modal detalhando fontes oficiais e fundamentos legais.
4. Suíte de testes aprovada com 100% de sucesso no `mvn test`.
