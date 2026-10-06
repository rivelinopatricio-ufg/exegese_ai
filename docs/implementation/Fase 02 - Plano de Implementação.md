# FASE 02 — PLANO DE IMPLEMENTAÇÃO DETALHADO
## Banco de Dados, Migrações e Modelo de Entidades

**Projeto**: Exegese AI (`exegese-ai`)  
**Pacote Base**: `br.org.rivelino.exegese_ai`  
**Runtime**: Java 25 LTS | Spring Boot 3.4.2 LTS | Spring Data JPA | PostgreSQL 17 + pgvector  
**Data**: 2026-10-06  

---

## 1. OBJETIVO DA FASE 02
Modelar e implementar todo o ecossistema de persistência relacional e vetorial do Exegese AI, englobando o script DDL oficial (`schema.sql`), as entidades JPA com mapeamento de chaves primárias UUID, relacionamento N:N entre Documentos e Assuntos, repositórios Spring Data JPA e a suíte de testes de persistência.

---

## 2. CHECKLIST DE TAREFAS

- [ ] **Tarefa 2.1: Criação do Script DDL Canônico (`schema.sql`)**:
  - `src/main/resources/schema.sql` definindo:
    - Extensões `uuid-ossp` e `vector`.
    - Tabelas: `exegese_user`, `exegese_subject`, `user_subject_permission`, `exegese_document`, `document_subject`, `exegese_chunk`, `ai_model_config`, `chat_session`, `chat_message`.
    - Índices: `idx_exegese_chunk_hnsw` (HNSW cosseno), `idx_exegese_chunk_tsv` (GIN sobre TSVECTOR), `idx_exegese_chunk_metadata` (GIN JSONB).
- [ ] **Tarefa 2.2: Implementação dos Enums de Domínio**:
  - `UserRole.java`: `ROLE_ADMIN`, `ROLE_OPERATOR`, `ROLE_USER`.
  - `SegmentationStrategyType.java`: `STRUCTURED_QA`, `LEGAL_SECTION`, `RECURSIVE`.
  - `ModelProvider.java`: `GEMINI`, `CLAUDE`, `OPENAI`, `NEMOTRON`, `DEEPSEEK`, `OLLAMA_LOCAL`.
- [ ] **Tarefa 2.3: Implementação das Entidades JPA**:
  - `ExegeseUser.java`
  - `ExegeseSubject.java`
  - `UserSubjectPermission.java` (e chave composta `UserSubjectPermissionId.java`)
  - `ExegeseDocument.java`
  - `DocumentSubject.java` (e chave composta `DocumentSubjectId.java`)
  - `ExegeseChunk.java`
  - `AiModelConfig.java`
  - `ChatSession.java`
  - `ChatMessage.java`
- [ ] **Tarefa 2.4: Implementação dos Repositórios Spring Data JPA**:
  - `ExegeseUserRepository.java`
  - `ExegeseSubjectRepository.java`
  - `ExegeseDocumentRepository.java`
  - `ExegeseChunkRepository.java`
  - `AiModelConfigRepository.java`
  - `ChatSessionRepository.java`
  - `ChatMessageRepository.java`
- [ ] **Tarefa 2.5: Criação da Suíte de Testes de Persistência**:
  - `EntityPersistenceIntegrationTest.java` validando:
    - Persistência e busca de usuários e papéis.
    - Criação de documento associado a múltiplos assuntos (relação N:N via `document_subject`).
    - Persistência e busca de chunks com metadados.
    - Integridade referencial e deleção em cascata.
- [ ] **Tarefa 2.6: Execução de Testes e Aceite**:
  - Execução de `mvn test` validando 100% dos testes.

---

## 3. CRITÉRIOS DE ACEITE
1. Todas as entidades JPA implementadas rigorosamente com cabeçalho de licença MIT e `@author Rivelino Patrício`.
2. Repositórios Spring Data JPA criados para todas as entidades fundamentais.
3. Relação N:N entre Documentos e Assuntos operando com chave composta e constraints.
4. Suíte de testes automatizados executando com sucesso e validando a integridade das entidades.
