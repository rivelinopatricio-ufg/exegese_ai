# FASE 07 — PLANO DE IMPLEMENTAÇÃO DETALHADO
## Mecanismo de Busca Híbrida & Fusão de Rankings (RRF)

**Projeto**: Exegese AI (`exegese-ai`)  
**Pacote Base**: `br.org.rivelino.exegese_ai`  
**Runtime**: Java 25 LTS | Spring Boot 3.4.2 LTS | Spring AI | PostgreSQL 17 + pgvector (prod) / H2 (test)  
**Data**: 2026-10-06  

---

## 1. OBJETIVO DA FASE 07
Desenvolver o serviço central de busca híbrida (`HybridSearchService`) combinando recuperação semântica vetorial (HNSW com distância de cosseno) e recuperação léxica por palavras-chave (Full Text Search em português), unificadas pelo algoritmo de fusão **Reciprocal Rank Fusion (RRF)** com constante $k = 60$, com particionamento obrigatório por assuntos documentais selecionados (`subject_id IN (...)`).

---

## 2. CHECKLIST DE TAREFAS

- [ ] **Tarefa 7.1: DTO de Resultados de Busca (`SearchResultChunk`)**:
  - Implementar record/DTO contendo `chunkId`, `documentId`, `documentTitle`, `chunkTitle`, `content`, `sequenceNumber`, `metadataJson`, `score` (RRF), `vectorRank` e `textRank`.
- [ ] **Tarefa 7.2: Algoritmo de Fusão de Rankings (RRF)**:
  - Implementar cálculo RRF:
    $$Score_{RRF}(d) = \sum_{m \in \{vec, txt\}} \frac{1}{60 + rank_m(d)}$$
  - Consolidação e desempate determinístico ordenando decrescentemente por score RRF.
- [ ] **Tarefa 7.3: Serviço de Busca Híbrida (`HybridSearchService`)**:
  - Implementar `search(String query, List<UUID> subjectIds, int topK)`:
  - Consulta Semântica Vetorial:
    - Gerar embedding da consulta via `EmbeddingModel`.
    - Executar consulta vetorial HNSW com filtro de assuntos.
  - Consulta Léxica Textual (FTS):
    - Executar consulta com stemmer em português e ranqueamento `ts_rank_cd`.
  - Abstração adaptativa de banco:
    - Execução nativa pgvector/TSVECTOR no PostgreSQL.
    - Estratégia de fallback compatível no H2 para suítes de teste automatizadas.
  - Fusão RRF e corte no Top-K (padrão Top-4).
- [ ] **Tarefa 7.4: Suíte de Testes Automatizados (`HybridSearchIntegrationTest`)**:
  - Testar fusão RRF com pesos e ordenação corretos.
  - Testar isolamento por assuntos documentais (`document_subject`).
  - Testar recuperação de chunks por termos exatos e similaridade semântica.
- [ ] **Tarefa 7.5: Execução de Testes e Homologação**:
  - Execução de `mvn test` garantindo 100% de sucesso.
- [ ] **Tarefa 7.6: Resumo Técnico e Commit**:
  - Gerar `docs/implementation/FASE 07 - Resumo Técnico.md`.
  - Realizar commit convencional em inglês.

---

## 3. CRITÉRIOS DE ACEITE
1. Busca híbrida combinando vetores e texto com fórmula RRF ($k=60$).
2. Filtragem estrita por assuntos (`subject_id IN (...)`).
3. Retorno estruturado dos Top-K chunks com scores consolidados e metadados.
4. Suíte de testes aprovada com 100% de sucesso no `mvn test`.
