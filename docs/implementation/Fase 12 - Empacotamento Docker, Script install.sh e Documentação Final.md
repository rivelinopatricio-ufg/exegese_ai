# Fase 12 — Plano de Implementação: Empacotamento Docker, Script install.sh e Documentação Final

## 1. Contexto e Objetivos
A Fase 12 conclui o plano de implementação do **Exegese AI**, empacotando toda a aplicação para entrega e implantação contínua em ambientes de produção. O objetivo principal é fornecer uma experiência de instalação "zero touch" ou assistida com um único comando (`./install.sh`), garantindo conteinerização segura com imagem Docker enxuta, usuário não-root, runtime Java 25, orquestração via Docker Compose com PostgreSQL 17 + pgvector, e documentação operacional completa em português.

---

## 2. Escopo Detalhado

### 2.1. Dockerfile Multi-Stage de Produção
- **Stage 1 (Builder)**: Utiliza JDK 25 (`eclipse-temurin:25-jdk-noble`) com Maven para compilar o projeto, otimizar cache de dependências e gerar o arquivo JAR final (`exegese-ai-1.0.0-SNAPSHOT.jar`).
- **Stage 2 (Runtime)**: Utiliza JRE 25 enxuto (`eclipse-temurin:25-jre-noble`).
- **Segurança Operacional**:
  - Criação de usuário e grupo de sistema não-root (`appuser:appgroup`, UID 10001).
  - Execução restrita sem privilégios de superusuário.
  - Parâmetros JVM de alta performance: `-XX:+UseZGC -XX:+ZGenerational -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8`.
  - Configuração de `HEALTHCHECK` nativo do Docker consultando `/actuator/health`.
  - Exposição exclusiva da porta `8080`.

### 2.2. Orquestração Docker Compose Completa
- Atualizar [`docker-compose.yml`](file:///d:/GIT/exegese_ai/docker-compose.yml) para incluir:
  - Serviço de banco de dados `postgres` (PostgreSQL 17 + pgvector) com healthcheck rigoroso (`pg_isready`).
  - Serviço da aplicação `app` apontando para o build local do `Dockerfile`.
  - Condição de dependência `condition: service_healthy` garantindo que a aplicação Spring Boot só inicie após o PostgreSQL estar 100% pronto.
  - Rede isolada `exegese-network` e persistência de volumes `pgdata` e `storage_data`.

### 2.3. Script de Instalação e Orquestração (`install.sh`)
- Padrões rigorosos de shell script: `set -euo pipefail`.
- Funções utilitárias de saída colorida (`INFO`, `SUCCESS`, `WARN`, `ERROR`).
- Suporte a argumentos de linha de comando:
  - `--help` / `-h`: Exibe manual de uso e opções disponíveis.
  - `--uninstall`: Desmonta contêineres, remove volumes e limpa artefatos temporários.
  - `--no-ingest`: Inicia o ambiente sem disparar a ingestão automática do acervo padrão.
- Validação estrita de pré-requisitos:
  - Presença de `docker` e `docker compose`.
  - Presença de utilitários de rede como `curl`.
- Gestão e Criação do `.env`:
  - Solicitação interativa com leitura mascarada para chaves de API (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `INITIAL_ADMIN_EMAIL`, `GEMINI_API_KEY`, `OPENAI_API_KEY`, etc.).
  - Geração automática de chave secreta criptográfica AES-256 (`EXEGESE_AES_SECRET`).
- Startup e Healthcheck Polling:
  - Execução de `docker compose up -d --build`.
  - Loop de sondagem aguardando o endpoint `http://localhost:8080/actuator/health` retornar status `UP`.
- Mensagem de boas-vindas com link direto para o navegador e credenciais de acesso.

### 2.4. Documentação Operacional (`README.md`)
- Manual completo em português:
  - Visão geral da plataforma e proposição de valor (rigor exegético e política anti-alucinação).
  - Stack tecnológica e arquitetura (Spring Boot 3.4.2 LTS, Java 25, PostgreSQL 17 + pgvector, Tailwind CSS).
  - Requisitos de hardware e software.
  - Guia de início rápido (`./install.sh`).
  - Guia de execução manual com Maven e Docker Compose.
  - Procedimentos de administração e cadastro de provedores de IA.
  - Verificação e execução de testes automatizados (`mvn test`).

---

## 3. Critérios de Aceite
1. Arquivo `Dockerfile` criado e sintaticamente válido para Java 25 multi-stage.
2. Arquivo `docker-compose.yml` orquestrando banco e aplicação conjuntamente.
3. Script `install.sh` implementado com tratamento de erros, flags e healthcheck em loop.
4. `README.md` abrangente e atualizado em português.
5. Execução limpa e com 100% de sucesso da suíte completa de testes (`mvn test`).
