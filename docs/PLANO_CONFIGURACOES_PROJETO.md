# Plano de Configurações e Diretrizes do Projeto Exegese AI

Este documento consolida a arquitetura e a padronização de configurações para o ecossistema do **Exegese AI** (`exegese-ai`).

---

## 1. Escopo de Configurações

O projeto foi configurado com:
1. **Tooling de Desenvolvimento & IDE**: Suporte unificado para VS Code e Eclipse/Spring Tools Suite (STS), com padronização de encodings em UTF-8 e integração Maven.
2. **Diretrizes de Agentes de IA**: Governança para Antigravity (`GEMINI.md`) e Claude (`CLAUDE.md`), fixando licença MIT, anotação `@author Rivelino Patrício`, injeção obrigatória por construtor e política de não-comprometimento autônomo do Git.
3. **Regras e Skills dos Agentes**: Instalação das 38 skills de fluxo de trabalho (Matt Pocock) e skill operacional dedicada (`exegese-ops`).
4. **Orquestração de Containers**: PostgreSQL 17 com pgvector para armazenamento vetorial e híbrido, além de suporte opcional a Ollama para inferência local com ou sem GPU.
5. **Estrutura Base de Build**: `pom.xml` padronizado para Spring Boot 3.4.2 com Spring AI 1.0.0-M6 e Apache PDFBox 3.0.4.

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
