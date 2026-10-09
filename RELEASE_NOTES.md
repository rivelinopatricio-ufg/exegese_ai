# Release Notes — Exegese AI v1.0.0 (General Availability)

**Data de Lançamento**: Outubro de 2026  
**Status**: Versão de Produção / General Availability (GA)  
**Repositório**: [exegese_ai](https://github.com/rivelinopatricio-ufg/exegese_ai)

---

## 📌 Visão Geral da Versão

O **Exegese AI v1.0.0** marca o lançamento oficial da plataforma corporativa e institucional de **Recuperação Aumentada por Geração (RAG)** focada em cenários de **rigor exegético, auditoria documental e fundamentação normativa estrita**.

Diferente de assistentes genéricos, o Exegese AI foi concebido sob o princípio inegociável de **Tolerância Zero a Alucinações (*Zero Hallucination*)**, onde nenhuma informação é gerada sem lastro em fontes oficiais indexadas e devidamente auditáveis.

---

## 🚀 Principais Módulos e Funcionalidades

### 1. Motor RAG Especialista & Grounding Normativo
- **Política de Tolerância Zero a Alucinações**: Mecanismo de corte (*similarity threshold*) parametrizado em `0.65`. Caso os trechos recuperados não atinjam o limiar semântico, o sistema recusa-se a responder e emite formalmente a declaração canônica de ausência de documentação no idioma do usuário.
- **Busca Híbrida RRF (Reciprocal Rank Fusion)**: Combina a precisão semântica de embeddings densos (HNSW com distância por cosseno) com busca textual clássica (*Full-Text Search* em português com `tsvector` e `tsquery`), eliminando o risco de vetores nulos.
- **Rastreabilidade e Citações Canônicas Clicáveis**: Cada parágrafo respondido traz chips interativos apontando o documento oficial, a pergunta/artigo e a página do PDF original.
- **Modal de Auditoria e Evidência Canônica**: Exibe a proveniência exata do trecho, o dispositivo legal fundamentador, o hash criptográfico SHA-256 e a pontuação matemática de similaridade por cosseno.

### 2. Arquitetura Multi-Provedor AI (7 Ecossistemas Integrados)
- **Roteamento Dinâmico em Tempo de Execução**: `LlmProviderRouter` permite alternar dinamicamente entre os principais ecossistemas do mercado sem interrupção de serviço:
  - **Google Gemini**: Modelos `gemini-2.5-flash`, `gemini-1.5-pro` e `gemini-embedding-001`.
  - **Anthropic Claude**: `claude-sonnet-5-5` e `claude-opus-5-5`.
  - **OpenAI ChatGPT**: `gpt-5-mini` e `gpt-4o`.
  - **Cerebras Inference**: `gpt-oss-120b` rodando em arquitetura Wafer-Scale Engine para inferência ultrarrápida.
  - **NVIDIA Nemotron**: Modelos corporativos Llama 3.1 Nemotron.
  - **DeepSeek AI**: Modelos `deepseek-chat` (V3) e `deepseek-reasoner` (R1).
  - **Ollama Local**: Execução privada on-premises (`qwen2.5:7b`, `llama3.2`).
- **Gestão Segura de Chaves com AES-256**: Credenciais de API podem ser configuradas via arquivo de ambiente ou gerenciadas diretamente pelo painel administrativo com criptografia simétrica no banco de dados.

### 3. Pipeline de Ingestão Polimórfica de Documentos
- **Extração com Apache PDFBox 3.0.4**: Suporte nativo a arquivos PDF de grande porte (limite configurado de até 50 MB), como manuais e compêndios normativos da Receita Federal.
- **Segmentação Especializada por Domínio**:
  - `STRUCTURED_QA`: Segmentador específico para manuais de Perguntas e Respostas (ex: *IRPF 2026*), mantendo o binômio pergunta/resposta atomicamente preservado.
  - `LEGAL_SECTION`: Segmentador por Artigos, Parágrafos e Incisos para leis, decretos e instruções normativas.
  - `RECURSIVE`: Segmentação recursiva com controle de janela e overlap semântico para textos corridos.
- **Deduplicação Idempotente**: Hash SHA-256 por arquivo e por trecho (*chunk*), evitando duplicidade de vetores e retrabalho na base de conhecimento.
- **Processamento Assíncrono com Tolerância a Quotas (HTTP 429)**: Geração de embeddings em segundo plano com controle de taxa (*rate limiting* interno), pausas automáticas e backoff inteligente para compatibilidade com planos gratuitos do Google Gemini.

### 4. Controle de Acesso Baseado em Papéis (RBAC) & Governança
- **Autenticação Federada Google OAuth2 / OIDC**: Login seguro corporativo integrado às contas Google dos usuários.
- **Bootstrap do Primeiro Administrador**: Inicialização segura via variável `INITIAL_ADMIN_EMAIL`, concedendo privilégios `ROLE_ADMIN` no primeiro login.
- **Hierarquia de 3 Papéis Operacionais**:
  - `ROLE_ADMIN`: Gestão plena do sistema, usuários e chaves de IA.
  - `ROLE_OPERATOR`: Permissão para upload e curadoria documental.
  - `ROLE_USER`: Acesso às consultas e ao chat regulatório.
- **Bloqueio e Reativação Instantânea**: Filtro de segurança de sessão (`AccountStatusFilter`) e cache de integridade de conta (`UserAccountStatusCache`) que revogam acessos suspensos em tempo real.
- **Delegação N:N de Assuntos**: Organização de acervos por tópicos regulatórios com atribuição de permissões (Leitura, Escrita, Gerenciamento).

### 5. Frontend Reativo, Streaming SSE & Acessibilidade
- **Streaming em Dois Passos com Proteção CSRF**: Validação segura da pergunta seguida de canal Server-Sent Events (SSE) dedicado em Virtual Thread.
- **Digitação Suave Token a Token**: Cursor visual animado com preservação de blocos Markdown e equações matemáticas.
- **Acessibilidade Universal (WCAG 2.1 AA)**: Alto contraste, navegação completa por teclado, modais acessíveis e alternador nativo de temas (Claro / Escuro).
- **Internacionalização Completa (i18n)**: Interface, avisos e prompts especialistas resolvidos dinamicamente em **Português (pt-BR)**, **Inglês (en)** e **Espanhol (es)**.

### 6. Infraestrutura, Proxy Reverso & Segurança Perimetral
- **Runtime Moderno Java 25 & Spring Boot 4.1.1 GA**: Alto rendimento com virtual threads e compilação nativa Java 25 (bytecode *major version 69*).
- **SWAG Reverse Proxy (LinuxServer.io)**: Camada de borda com NGINX, Fail2ban integrado, cabeçalhos de segurança estritos e emissão/renovação autônoma de certificados TLS Let's Encrypt.
- **Isolamento de Rede Interna**: Apenas as portas de borda do proxy (80/443 ou alternativas) são expostas; Tomcat (8080) e PostgreSQL (5432) operam isolados na rede virtual Docker (`exegese-net`).
- **Instalador Automatizado (`install.sh`)**: Script de implantação com assistente interativo, detecção de portas, configuração de firewall UFW e suporte a execuções não-interativas.
- **Migrações Versionadas com Flyway**: Banco de dados evoluído por scripts versionados e idempotentes em `db/migration/`.

---

## 📊 Matriz de Componentes e Dependências

| Componente | Versão Homologada | Função Principal |
| :--- | :--- | :--- |
| **Java Platform** | OpenJDK 25 (Major 69) | Linguagem e runtime com suporte a Virtual Threads |
| **Spring Boot** | 4.1.1 GA | Framework core, injeção de dependências e MVC |
| **Spring AI** | 2.0.1 | Orquestração de modelos LLM, RAG e conectores |
| **PostgreSQL** | 17.x | Banco de dados relacional e transacional |
| **pgvector** | 0.8.7 | Indexação e busca vetorial de embeddings (HNSW) |
| **Apache PDFBox** | 3.0.4 | Extração e normalização estruturada de PDFs |
| **Flyway** | Integrado ao Spring Boot | Controle de versionamento do esquema de banco |
| **SWAG Proxy** | 5.8.0 (LinuxServer.io) | Gateway NGINX, Certbot SSL e Fail2ban |
| **Tailwind CSS** | 3.4.17 (Build Local) | Design System utilitário self-hosted (sem CDN externa) |

---

## 🧪 Qualidade de Software e Auditoria de Segurança

- **205+ Testes Automatizados**: Cobertura abrangente com JUnit 5, Mockito e Testcontainers (PostgreSQL + pgvector).
- **Mitigação de Prompt Injection**: Filtro de sanitização de entradas (`InputSanitizationFilter`) isolando o conteúdo de perguntas de instruções do sistema.
- **Proteção contra IDOR e Ataques Web**: Validação estrita de posse de sessões de chat, cabeçalhos CSP sem `'unsafe-inline'`, HSTS ativado e proteção contra clickjacking.
- **Rate Limiting Defensivo**: Limitação de taxa em 20 requisições/minuto por conta/IP via Bucket4j para proteção de rotas de API.

---

## 📚 Documentação e Recursos Disponíveis

- 📖 **Guia Completo**: [`README.md`](README.md)
- 🖥️ **Apresentação Visual Interativa (Slide Deck)**: [`docs/presentation/index.html`](docs/presentation/index.html)
- 📄 **Apresentação dos Fluxos com Prints**: [`docs/presentation/APRESENTACAO_FLUXOS_EXEGESE_AI.md`](docs/presentation/APRESENTACAO_FLUXOS_EXEGESE_AI.md)
- 🛡️ **Relatório Consolidado de Auditoria**: [`docs/SEGURANCA_AUDITORIA_2026-10.md`](docs/SEGURANCA_AUDITORIA_2026-10.md)
- ⚙️ **Runbook Operacional do Desenvolvedor**: [`.agents/skills/exegese-ops/SKILL.md`](.agents/skills/exegese-ops/SKILL.md)
