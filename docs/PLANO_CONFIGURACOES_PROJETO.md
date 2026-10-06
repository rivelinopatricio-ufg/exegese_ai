# Plano de Configurações e Diretrizes do Projeto Exegese AI

Este documento consolida a arquitetura e a padronização de configurações para o ecossistema do **Exegese AI** (`exegese-ai`), pacote base `br.org.rivelino.exegese_ai`.

---

## 1. Escopo de Configurações e Parâmetros Base

O projeto é configurado com as seguintes diretrizes essenciais:
1. **Identidade e Nomenclatura**:
   - Nome da Aplicação: **Exegese AI**
   - Maven GroupId: `br.org.rivelino`
   - Maven ArtifactId: `exegese-ai`
   - Pacote Base do Código-Fonte: `br.org.rivelino.exegese_ai`
2. **Tooling de Desenvolvimento & IDE**: Suporte unificado para VS Code e Eclipse/Spring Tools Suite (STS), com padronização de encodings estritamente em UTF-8 sem BOM e integração Maven.
3. **Diretrizes de Agentes de IA**: Governança para Antigravity (`GEMINI.md`) e Claude (`CLAUDE.md`), fixando licença MIT, anotação `@author Rivelino Patrício`, injeção obrigatória por construtor e política de não-comprometimento autônomo do Git.
4. **Módulo Administrativo & Governança**:
   - Gestão de usuários e permissões com RBAC (`ROLE_ADMIN`, `ROLE_OPERATOR`, `ROLE_USER`).
   - Manutenção de documentos e categorização N:N por Assuntos.
   - Configuração dinâmica de modelos de IA (Google Gemini, Anthropic Claude, OpenAI, NVIDIA Nemotron, DeepSeek AI, Processamento Local via Ollama).
5. **Autenticação**: Google OAuth2 / OpenID Connect (OIDC) com provisionamento automático e bootstrap do primeiro administrador via variável de ambiente `INITIAL_ADMIN_EMAIL`.
6. **Orquestração de Containers**: PostgreSQL 17 com pgvector para armazenamento vetorial e híbrido, além de suporte opcional a Ollama para inferência local com ou sem GPU.
7. **Estrutura Base de Build**: `pom.xml` padronizado para Java 25 (LTS), Spring Boot 4.x / 3.4.2 LTS, Spring AI 1.0.0-M6 e Apache PDFBox 3.0.4.

---

## 2. Inventário de Arquivos e Diretórios

| Arquivo / Diretório | Finalidade |
| :--- | :--- |
| `.vscode/settings.json` | Ajustes de compilação Java e salvamento no VS Code |
| `.project`, `.classpath`, `.settings/` | Metadados do Eclipse/STS com naturezas Java e Maven |
| `.gitignore`, `.dockerignore` | Regras de exclusão de artefatos temporários, IDEs e volumes de dados |
| `GEMINI.md` | Diretrizes de desenvolvimento para o Google Antigravity |
| `CLAUDE.md`, `.claude/` | Comandos operacionais e permissões para Claude Code |
| `.agents/rules/` | Regras modulares de código, git, apresentação de planos e UTF-8 |
| `.agents/skills/`, `skills-lock.json` | Catálogo de skills de engenharia de software e skill `exegese-ops` |
| `docker-compose.yml`, `docker-compose.override.ai*.yml` | Orquestração do PostgreSQL 17 + pgvector e Ollama |
| `ollama_init.sh`, `.env.example` | Script de inicialização de modelos e variáveis de ambiente |
| `pom.xml` | Configuração Maven com Spring Boot, Spring AI e dependências core |
| `docs/` | Documentação técnica, especificações de requisitos, planos de implementação e diagramas Mermaid |