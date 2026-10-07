# FASE 04 — PLANO DE IMPLEMENTAÇÃO DETALHADO
## Painel Administrativo — Gestão de Usuários e Permissões

**Projeto**: Exegese AI (`exegese-ai`)  
**Pacote Base**: `br.org.rivelino.exegese_ai`  
**Runtime**: Java 25 LTS | Spring Boot 3.4.2 LTS | Spring Security 6.x | Thymeleaf  
**Data**: 2026-10-06  

---

## 1. OBJETIVO DA FASE 04
Consolidar o módulo administrativo de usuários (`/admin/users`), disponibilizando gerenciamento completo de papéis (`ROLE_ADMIN`, `ROLE_OPERATOR`, `ROLE_USER`), ativação/suspensão de contas e delegação granular de permissões por assunto (`user_subject_permission`), permitindo ao administrador governar os acervos acessíveis por cada membro da organização.

---

## 2. CHECKLIST DE TAREFAS

- [ ] **Tarefa 4.1: Modelagem de DTOs de Governança de Usuários**:
  - `UserDTO.java` (Record/Classe imutável para transferência de dados do usuário).
  - `UserPermissionUpdateDTO.java` (Transferência de permissões por assunto: lista de `subjectIds` e `permissionLevel`).
- [ ] **Tarefa 4.2: Expansão do `UserService`**:
  - Métodos para gestão de permissões por assunto:
    - `grantSubjectPermission(UUID userId, UUID subjectId, String permissionLevel)`.
    - `revokeSubjectPermission(UUID userId, UUID subjectId)`.
    - `getUserPermissions(UUID userId)`.
    - `updateRole(UUID userId, UserRole newRole)`.
    - `toggleActive(UUID userId)`.
- [ ] **Tarefa 4.3: Expansão de `AdminUserController`**:
  - `GET /admin/users`: Carrega lista de usuários, catálogo de assuntos e permissões vigentes.
  - `POST /admin/users/{id}/role`: Atualiza papel com validação.
  - `POST /admin/users/{id}/permissions`: Atualiza permissões granulares por assunto.
  - `POST /admin/users/{id}/toggle-active`: Inverte o status ativo/suspenso.
- [ ] **Tarefa 4.4: Enriquecimento da View Thymeleaf `admin/users.html`**:
  - Formulários de troca de papel via dropdown.
  - Seção/modal para atribuição de permissões por assunto.
  - Botão de ativação/suspensão estilizado com feedback visual.
- [ ] **Tarefa 4.5: Criação da Suíte de Testes de Gestão de Usuários**:
  - `AdminUserManagementIntegrationTest.java`:
    - Testar promoção de `ROLE_USER` para `ROLE_OPERATOR` e `ROLE_ADMIN`.
    - Testar concessão de permissão de leitura (`READ`) e gestão (`MANAGE`) em assunto específico.
    - Testar alternância de status ativo/inativo.
    - Testar bloqueio a usuários sem `ROLE_ADMIN`.
- [ ] **Tarefa 4.6: Execução de Testes e Homologação**:
  - Execução de `mvn test` com 100% de sucesso.

---

## 3. CRITÉRIOS DE ACEITE
1. Administrador altera papel de um usuário e concede acesso a assuntos específicos.
2. Injeção estrita por construtor preservada.
3. Suíte de testes automatizados executando com sucesso e validando as operações do painel.
