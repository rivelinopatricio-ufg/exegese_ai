# FASE 08 — PLANO DE IMPLEMENTAÇÃO DETALHADO
## Roteamento Multi-Provedor de IA & Gestão de Credenciais

**Projeto**: Exegese AI (`exegese-ai`)  
**Pacote Base**: `br.org.rivelino.exegese_ai`  
**Runtime**: Java 25 LTS | Spring Boot 3.4.2 LTS | Spring AI | AES-256-GCM  
**Data**: 2026-10-06  

---

## 1. OBJETIVO DA FASE 08
Construir o mecanismo dinâmico de roteamento multi-provedor (`LlmProviderRouter`), suportando os 6 ecossistemas de IA (Google Gemini, Anthropic Claude, OpenAI, NVIDIA Nemotron, DeepSeek e Ollama Local), com bootstrap automático das configurações, gestão segura de chaves de API com criptografia simétrica AES-256-GCM e tela administrativa para teste de conectividade (ping).

---

## 2. CHECKLIST DE TAREFAS

- [ ] **Tarefa 8.1: DTO de Configuração de Modelos (`ModelConfigDTO`)**:
  - DTO para tráfego seguro de configurações, mascaramento de API keys e parâmetros de inferência (temperatura, maxTokens).
- [ ] **Tarefa 8.2: Roteador de Provedores (`LlmProviderRouter`)**:
  - Inicialização/bootstrap dos 6 ecossistemas na tabela `ai_model_config`:
    1. `GEMINI` (Google Gemini) - Padrão inicial
    2. `CLAUDE` (Anthropic Claude)
    3. `OPENAI` (OpenAI ChatGPT)
    4. `NEMOTRON` (NVIDIA Nemotron)
    5. `DEEPSEEK` (DeepSeek AI)
    6. `OLLAMA_LOCAL` (Ollama Local)
  - Resolução híbrida de credenciais:
    - Recuperação e decriptação via `CryptoService` (AES-256-GCM).
    - Fallback para variáveis de ambiente do sistema (`GEMINI_API_KEY`, etc.).
  - Alternância atômica do provedor padrão (`setDefaultProvider`).
  - Execução de teste de conectividade (`pingModel`).
- [ ] **Tarefa 8.3: Controller Administrativo (`AdminModelController`)**:
  - Mapear rotas protegidas `@PreAuthorize("hasRole('ADMIN')")`:
    - `GET /admin/models`: Listagem dos 6 modelos.
    - `POST /admin/models/{provider}/save`: Atualização de chave, URL e parâmetros.
    - `POST /admin/models/{provider}/set-default`: Ativação como padrão do sistema.
    - `POST /admin/models/{provider}/ping`: Teste de conectividade.
- [ ] **Tarefa 8.4: View Thymeleaf (`admin/models.html`)**:
  - Interface moderna com cards visuais para cada um dos 6 provedores, indicação do modelo ativo/padrão, modal de configuração de chave e feedback visual de ping.
- [ ] **Tarefa 8.5: Suíte de Testes Automatizados (`MultiProviderModelIntegrationTest`)**:
  - Validar bootstrap dos 6 provedores.
  - Validar encriptação e decriptação de chaves em repouso.
  - Validar chaveamento dinâmico do modelo padrão.
  - Validar endpoints do controller administrativo via MockMvc.
- [ ] **Tarefa 8.6: Execução de Testes e Homologação**:
  - Execução de `mvn test` garantindo 100% de sucesso.
- [ ] **Tarefa 8.7: Resumo Técnico e Commit**:
  - Gerar `docs/implementation/FASE 08 - Resumo Técnico.md`.
  - Realizar commit convencional em inglês.

---

## 3. CRITÉRIOS DE ACEITE
1. Os 6 provedores devidamente inicializados no banco com seus metadados.
2. Chaves de API armazenadas com criptografia AES-256-GCM e resolução transparente de variáveis de ambiente.
3. Alternância do modelo padrão sem reinicialização da aplicação.
4. Painel administrativo funcional com feedback de teste.
5. Suíte de testes com 100% de sucesso.
