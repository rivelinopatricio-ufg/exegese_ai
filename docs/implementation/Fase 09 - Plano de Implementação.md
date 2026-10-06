# FASE 09 — PLANO DE IMPLEMENTAÇÃO DETALHADO
## Orquestração do RAG, Anti-Alucinação & Streaming SSE

**Projeto**: Exegese AI (`exegese-ai`)  
**Pacote Base**: `br.org.rivelino.exegese_ai`  
**Runtime**: Java 25 LTS | Spring Boot 3.4.2 LTS | Spring AI | Server-Sent Events (SSE)  
**Data**: 2026-10-06  

---

## 1. OBJETIVO DA FASE 09
Desenvolver o pipeline completo de orquestração RAG do Exegese AI, englobando a reescrita contextual de queries (`QueryRewritingService`), a guarda contra alucinações baseada em limiar mínimo de similaridade (`AntiHallucinationGuard`), a montagem do System Prompt institucional com ancoragem canônica estrita e o streaming reativo em tempo real via Server-Sent Events (`event: token`, `event: citation`, `event: complete`).

---

## 2. CHECKLIST DE TAREFAS

- [ ] **Tarefa 9.1: Reescrita Contextual de Query (`QueryRewritingService`)**:
  - Resolver anáforas e referências baseadas no histórico recente da sessão (`ChatSession`/`ChatMessage`).
  - Preservar termos tributários e referências legislativas.
- [ ] **Tarefa 9.2: Guarda Anti-Alucinação (`AntiHallucinationGuard`)**:
  - Avaliar o ranking de chunks retornados pelo `HybridSearchService`.
  - Aplicar limiar de corte configurado (`exegese.similarity-threshold` = 0.65).
  - Em caso de ausência de contexto ou score abaixo do limiar, disparar a recusa canônica:
    *"Essa informação não consta nos documentos dos assuntos selecionados."*
- [ ] **Tarefa 9.3: DTOs e Estrutura de Citações Canônicas**:
  - `CanonicalCitationDTO.java`: `documentTitle`, `chunkTitle`, `pageNumber`, `questionNumber`, `legalBasis`.
- [ ] **Tarefa 9.4: Orquestrador de RAG e Streaming SSE (`RagOrchestrationService`)**:
  - Injetar `HybridSearchService`, `QueryRewritingService`, `AntiHallucinationGuard`, `LlmProviderRouter`, `ChatSessionRepository` e `ChatMessageRepository`.
  - Método `streamResponse(UUID sessionId, String userQuestion, List<UUID> subjectIds, SseEmitter emitter)`.
  - Montagem de Prompt Exegético com delimitação formal de contexto documental.
  - Emissão de eventos SSE:
    - `event: token`: emissão progressiva de cada token de resposta.
    - `event: citation`: lista consolidada de fontes oficiais para abertura de modal na UI.
    - `event: complete`: finalização do fluxo.
  - Persistência das mensagens (`USER` e `ASSISTANT`) na sessão ativa.
- [ ] **Tarefa 9.5: Suíte de Testes Automatizados (`RagOrchestrationIntegrationTest`)**:
  - Testar recusa estrita de pergunta fora de escopo (Zero Hallucination).
  - Testar fluxo de resposta com contexto válido e emissão de citações.
  - Testar persistência de mensagens na sessão de chat.
- [ ] **Tarefa 9.6: Execução de Testes e Homologação**:
  - Execução de `mvn test` garantindo 100% de sucesso.
- [ ] **Tarefa 9.7: Resumo Técnico e Commit**:
  - Gerar `docs/implementation/FASE 09 - Resumo Técnico.md`.
  - Realizar commit convencional em inglês.

---

## 3. CRITÉRIOS DE ACEITE
1. Recusa imediata quando a busca não recuperar documentos com score relevante.
2. Citações canônicas emitidas via SSE com referência de página e pergunta/artigo.
3. Histórico de mensagens mantido e associado à sessão do usuário.
4. Suíte de testes aprovada com 100% de sucesso no `mvn test`.
