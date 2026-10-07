# Fase 13 — Resumo Técnico: Migração para Spring Boot 4 (4.1.1), Java 25 Nativo, Proxy Reverso Hardened (SWAG / NGINX) e Gestão Autônoma de Certificados SSL/TLS

## 1. Visão Geral

A Fase 13 alinhou o **Exegese AI** à sua diretiva arquitetural primária, promovendo a migração do framework base para **Spring Boot 4.1.1** com **Spring AI 2.0.1** sobre **Java 25 Nativo** (`class file version 69`), acoplado a um gateway de segurança perimetral baseado em **SWAG (NGINX + Certbot + Fail2ban)** da LinuxServer.io com solicitação autônoma no startup e renovação automática de certificados Let's Encrypt.

---

## 2. Entregas Técnicas Realizadas

### 2.1. Framework, Compilação e Runtime Java 25 Nativo
- **Alvo de Bytecode**: Atualização do `pom.xml` para `<java.version>25</java.version>`, com `maven-compiler-plugin` compilando para bytecode Java 25 nativo (`major version 69`).
- **Framework Base**: Spring Boot 4.1.1 com Spring Framework 7 e Jakarta EE 11 / Servlet 6.1.
- **RAG Engine**: Spring AI 2.0.1 com suporte nativo ao ecossistema do Spring Boot 4.x.
- **Contêineres**: `Dockerfile` de produção multi-stage construído sobre `eclipse-temurin:25-jdk-noble` (builder) e `eclipse-temurin:25-jre-noble` (runtime).

### 2.2. Proxy Reverso Hardened (SWAG) e Isolamento Perimetral
- **Imagem Base**: `linuxserver/swag:latest` com privilégio `cap_add: NET_ADMIN` para mitigação de força bruta via Fail2ban integrado.
- **Configuração NGINX (`docker/proxy/config/default`)**:
  - Redirecionamento forçado de HTTP para HTTPS.
  - Rota `/api/chat/stream` com bufferização desativada (`proxy_buffering off;`, `X-Accel-Buffering no;`, `proxy_read_timeout 3600s`) para latência zero no streaming SSE do RAG.
  - Limite de payload estendido para 50MB em `/admin/documents/upload` e 10MB geral.
  - Repasse completo dos cabeçalhos `X-Forwarded-*`.
- **Isolamento de Rede**: Porta 8080 do contêiner Spring Boot totalmente isolada na rede interna Docker `exegese-net`.

### 2.3. Gestão Autônoma de Certificados TLS/SSL
- **Emissão no Startup**: O ciclo de inicialização do contêiner SWAG solicita o certificado à Let's Encrypt (desafio HTTP ou DNS) antes da subida dos serviços, sem dependência circular.
- **Renovação Silenciosa**: Daemon cron interno (2x ao dia) renova automaticamente certificados a menos de 30 dias do vencimento com reload suave do NGINX.

### 2.4. Instalador Automatizado (`install.sh`) e Google OAuth2
- **Parâmetros Customizáveis**: Suporte a host (`--host`) e portas alternativas (`--http-port`, `--https-port`, como 8080/8443).
- **Google OAuth2 Integrado**:
  - Parâmetros `--google-client-id`, `--google-client-secret` e assistente interativo com entrada mascarada.
  - Cálculo dinâmico e exibição da Authorized Redirect URI para configuração no Google Cloud Console.
  - Gravação protegida no `.env` com permissões `600`.

### 2.5. Validação e Qualidade
- Suíte completa de testes automatizados executando com 100% de sucesso (`mvn test`).
