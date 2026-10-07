# FASE 03 — PLANO DE IMPLEMENTAÇÃO DETALHADO
## Autenticação Google OAuth2 / OIDC & Segurança RBAC

**Projeto**: Exegese AI (`exegese-ai`)  
**Pacote Base**: `br.org.rivelino.exegese_ai`  
**Runtime**: Java 25 LTS | Spring Boot 3.4.2 LTS | Spring Security 6.x | Google OAuth2 / OIDC  
**Data**: 2026-10-06  

---

## 1. OBJETIVO DA FASE 03
Implementar o pipeline de autenticação corporativa via Google OAuth2 / OIDC, sincronização automática de credenciais e avatar no banco relacional, atribuição automática do primeiro Administrador (`INITIAL_ADMIN_EMAIL`), isolamento RBAC com anotações de segurança de método (`@PreAuthorize`) e proteção completa das rotas da plataforma.

---

## 2. CHECKLIST DE TAREFAS

- [ ] **Tarefa 3.1: Serviço de Usuário (`UserService`)**:
  - Implementar `UserService.java` com injeção por construtor (`JAVA_CONSTRUCTOR_PARAMETER_INJECTION`).
  - Métodos para sincronizar usuário Google (`syncGoogleUser`), atualizar papéis e verificar existência.
- [ ] **Tarefa 3.2: OIDC User Service Customizado (`CustomOidcUserService`)**:
  - Implementar `CustomOidcUserService.java` estendendo `OidcUserService` para interceptar claims OIDC (email, name, picture).
- [ ] **Tarefa 3.3: Success Handler com Bootstrap de Administrador (`GoogleOAuth2SuccessHandler`)**:
  - Implementar `GoogleOAuth2SuccessHandler.java` executando a regra de negócio mandatória:
    - Se o e-mail autenticado coincidir com `INITIAL_ADMIN_EMAIL`, conceder `ROLE_ADMIN`.
    - Caso contrário, novos usuários recebem `ROLE_USER` e mantêm o papel previamente cadastrado.
    - Atualizar timestamp de `lastLoginAt`.
- [ ] **Tarefa 3.4: Facade de Contexto de Segurança (`SecurityContextFacade`)**:
  - Implementar `SecurityContextFacade.java` para recuperar e-mail e dados do usuário corrente de forma limpa e mockável.
- [ ] **Tarefa 3.5: Configuração de Segurança Spring (`SecurityConfiguration`)**:
  - Rotas públicas liberadas: `/login`, `/css/**`, `/js/**`, `/actuator/health`, `/favicon.ico`.
  - Rotas autenticadas exigindo login Google OAuth2.
  - Habilitar `@EnableMethodSecurity`.
  - Configurar formulário de login redirecionando para `/login`.
- [ ] **Tarefa 3.6: Controlador de Teste / Login Base**:
  - Criar `LoginViewController.java` servindo a página `/login` e endpoint administrativo protegido para verificação de RBAC.
- [ ] **Tarefa 3.7: Suíte de Testes Automatizados de Segurança**:
  - `SecurityIntegrationTest.java` com MockMvc validando:
    - Redirecionamento de rota não-autenticada para login.
    - Acesso anônimo liberado para recursos públicos.
    - Bloqueio (403) de usuário `ROLE_USER` em rota administrativa.
    - Acesso permitido (200) para `ROLE_ADMIN` em rota administrativa.
    - Bootstrap do primeiro administrador via `UserService`.
- [ ] **Tarefa 3.8: Execução de Testes e Aceite**:
  - Execução de `mvn test` validando 100% de sucesso.

---

## 3. CRITÉRIOS DE ACEITE
1. Rotas protegidas exigindo autenticação OAuth2.
2. Injeção estrita de dependências via construtor com campos `private final`.
3. Bootstrap do primeiro admin funcional e testado.
4. Suíte MockMvc aprovada sem falhas.
