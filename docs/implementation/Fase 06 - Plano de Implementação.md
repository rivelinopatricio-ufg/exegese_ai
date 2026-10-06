# FASE 06 — PLANO DE IMPLEMENTAÇÃO DETALHADO
## Engine de Ingestão de Documentos & Chunking Polimórfico

**Projeto**: Exegese AI (`exegese-ai`)  
**Pacote Base**: `br.org.rivelino.exegese_ai`  
**Runtime**: Java 25 LTS | Spring Boot 3.4.2 LTS | Apache PDFBox 3.0.4 | Spring AI  
**Data**: 2026-10-06  

---

## 1. OBJETIVO DA FASE 06
Construir a engine de ingestão documental de alta fidelidade do Exegese AI, englobando a extração com paginação via Apache PDFBox 3.0.4, o padrão Strategy para chunking polimórfico (perguntas e respostas estruturadas do IRPF, artigos de lei e segmentação recursiva), hashing criptográfico SHA-256 para garantia de idempotência e persistência relacional/vetorial de chunks com metadados exegéticos completos.

---

## 2. CHECKLIST DE TAREFAS

- [ ] **Tarefa 6.1: Extrator de Texto de PDFs (`PdfTextExtractor`)**:
  - Implementar `PdfTextExtractor.java` utilizando Apache PDFBox 3.0.4 (`Loader.loadPDF`).
  - Extrair páginas mapeadas com número de página e eliminação de ruídos (cabeçalhos e rodapés repetitivos).
  - Retornar estrutura com páginas individuais e texto consolidado.
- [ ] **Tarefa 6.2: DTOs e Estrutura de Chunks**:
  - `RawChunk.java` (Record contendo número sequencial, título, conteúdo, metadados, número de página e hash SHA-256).
- [ ] **Tarefa 6.3: Fábrica e Estratégias de Segmentação Polimórfica**:
  - Interface `SegmentationStrategy.java`: `List<RawChunk> segment(String text, Map<Integer, String> pages)`.
  - `StructuredQuestionSegmentationStrategy.java`:
    - Regex para marcadores canônicos da RFB `^\d{3}\s*[—–-]` isolando número da questão, pergunta, resposta e referências à legislação.
  - `LegalSectionSegmentationStrategy.java`:
    - Regex para diplomas legais: `(?i)^art(?:igo|\.)\s*\d+` e parágrafos `§\s*\d+`.
  - `GeneralSegmentationStrategy.java`:
    - Chunking recursivo por blocos de parágrafos com ~15% de overlap.
  - `SegmentationStrategyFactory.java`:
    - Resolução da estratégia com base no enum `SegmentationStrategyType`.
- [ ] **Tarefa 6.4: Serviço de Ingestão Documental (`DocumentIngestionService`)**:
  - `ingestDocument(String title, String fileName, InputStream inputStream, List<UUID> subjectIds, SegmentationStrategyType strategyType)`.
  - Cálculo de hash SHA-256 do arquivo original e de cada chunk individual.
  - Idempotência: não re-indexar chunks que possuam o mesmo `chunk_hash_sha256`.
  - Geração de embeddings via `EmbeddingModel`.
  - Persistência atômica transacional de `ExegeseDocument`, `DocumentSubject` e `ExegeseChunk`.
- [ ] **Tarefa 6.5: Suíte de Testes Automatizados da Engine**:
  - `DocumentIngestionIntegrationTest.java`:
    - Testar `StructuredQuestionSegmentationStrategy` identificando perguntas canônicas da RFB.
    - Testar `LegalSectionSegmentationStrategy` particionando artigos normativos.
    - Testar extração de texto via `PdfTextExtractor`.
    - Testar ingestão de documento completo com persistência idempotente e preservação de metadados.
- [ ] **Tarefa 6.6: Execução de Testes e Homologação**:
  - Execução de `mvn test` com 100% de sucesso.

---

## 3. CRITÉRIOS DE ACEITE
1. Extração estruturada de PDFs via PDFBox 3.0.4 preservando referências de página.
2. Segmentação polimórfica operando com as 3 estratégias especializadas.
3. Idempotência estrita por hash SHA-256 evitando duplicação vetorial.
4. Suíte de testes aprovada com 100% de sucesso.
