# FASE 05 — PLANO DE IMPLEMENTAÇÃO DETALHADO
## Gestão de Assuntos (Topics) e Catálogo de Documentos

**Projeto**: Exegese AI (`exegese-ai`)  
**Pacote Base**: `br.org.rivelino.exegese_ai`  
**Runtime**: Java 25 LTS | Spring Boot 3.4.2 LTS | Spring Security 6.x | Thymeleaf  
**Data**: 2026-10-06  

---

## 1. OBJETIVO DA FASE 05
Construir os módulos administrativos de Gestão de Assuntos (`/admin/subjects`) e Catálogo de Documentos (`/admin/documents`), viabilizando a parametrização temática dos acervos regulatórios e a supervisão dos arquivos indexados com relacionamento N:N e metadados estruturais.

---

## 2. CHECKLIST DE TAREFAS

- [ ] **Tarefa 5.1: Modelagem de DTOs de Assuntos e Documentos**:
  - `SubjectDTO.java` (Record para transferência de código, nome e descrição do assunto).
  - `DocumentSummaryDTO.java` (Record para exibição de título, arquivo, tamanho, páginas, status e tags de assuntos vinculados).
- [ ] **Tarefa 5.2: Criação do Serviço de Catálogo (`SubjectCatalogService`)**:
  - CRUD de assuntos (`createSubject`, `updateSubject`, `toggleActive`, `findAllActive`, `findAll`).
  - Associação de assuntos a documentos.
- [ ] **Tarefa 5.3: Controlador de Assuntos (`AdminSubjectController`)**:
  - Mapeamento `/admin/subjects` protegido por `@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")`.
  - `GET /admin/subjects`: Listagem de assuntos e formulário de novo cadastro.
  - `POST /admin/subjects`: Cadastro com validação de unicidade de `code`.
  - `POST /admin/subjects/{id}/toggle`: Alternância de ativo/inativo.
- [ ] **Tarefa 5.4: Controlador de Documentos (`AdminDocumentController`)**:
  - Mapeamento `/admin/documents` protegido por `@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")`.
  - `GET /admin/documents`: Listagem com filtros por assunto e status.
- [ ] **Tarefa 5.5: Views Thymeleaf**:
  - `admin/subjects.html`: Tabela de assuntos, crachás de status e formulário de adição rápida.
  - `admin/documents.html`: Catálogo de documentos, tags dos assuntos associados, tamanho formatado e status de indexação.
- [ ] **Tarefa 5.6: Suíte de Testes Automatizados**:
  - `SubjectAndDocumentCatalogIntegrationTest.java`:
    - Cadastro e listagem do assunto canônico "Tributário - IRPF 2026".
    - Validação de filtros e visualização do acervo de documentos vinculados.
    - Teste de controle de acesso (bloqueio a `ROLE_USER`).
- [ ] **Tarefa 5.7: Execução de Testes e Homologação**:
  - Execução de `mvn test` com 100% de sucesso.

---

## 3. CRITÉRIOS DE ACEITE
1. Assunto "Tributário - IRPF 2026" cadastrado e recuperável.
2. Catálogo de documentos exibindo metadados e tags N:N com assuntos vinculados.
3. Injeção estrita por construtor e padrões de `GEMINI.md` cumpridos.
4. Suíte de testes aprovada.
