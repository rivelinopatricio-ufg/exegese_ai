# Fase 13 — Plano de Implementação: Migração para Spring Boot 4 (4.1.1), Java 25 Nativo, Proxy Reverso Hardened (SWAG / NGINX) e Gestão Autônoma de Certificados SSL/TLS

## 1. Visão Geral e Contexto

Este plano estabelece a estratégia detalhada, etapas de engenharia, dependências e critérios de aceite para alinhar o **Exegese AI** à sua diretiva arquitetural primária (estabelecida na documentação base do projeto e na skill operacional `exegese-ops`), executando simultaneamente:

1. **Migração do Framework para Spring Boot 4.x (4.1.1 - Versão Homologada)**:
   - Substituição do `spring-boot-starter-parent` `3.4.2` pela versão mais recente da linha 4.x (**Spring Boot 4.1.1**).
   - Atualização do ecossistema de dependências correlatas, em especial o **Spring AI** para a versão **2.0.1** (compatível com Spring Boot 4 e Spring Framework 7 / Jakarta EE 11).
2. **Compilação e Runtime em Java 25 Nativo**:
   - Elevação do alvo de bytecode de Java 21 para **Java 25 nativo** (`class file version 69`).
   - Otimizações de compilação (`maven-compiler-plugin` com release 25 e flags de reflexão).
   - Imagens de contêiner baseadas em JDK/JRE 25 (`eclipse-temurin:25-jdk-noble` / `eclipse-temurin:25-jre-noble`).
3. **Implantação de Proxy Reverso Hardened com NGINX e Certificados Autônomos (`linuxserver/swag`)**:
   - Inserção do gateway de segurança perimetral **SWAG** (*Secure Web Application Gateway* - LinuxServer.io), que consolida **NGINX**, cliente **Certbot integrado**, **s6-overlay init system** e **Fail2ban** em um único contêiner de borda.
   - Isolamento total da porta 8080 do Tomcat/Spring Boot na rede interna Docker (`exegese-net`).
   - Otimização determinística para Server-Sent Events (SSE) sem bufferização (`proxy_buffering off;`, `X-Accel-Buffering no;`).
   - **Customização de Host e Portas**: O instalador permite definir o nome do host (ex: `exegese.empresa.gov.br` ou `localhost`) e as portas públicas expostas (HTTP e HTTPS, permitindo portas alternativas como 8080/8443 caso as portas padrão 80/443 estejam ocupadas no servidor).
4. **Solicitação de Certificado no Startup e Renovação Automática ao Expirar**:
   - **Emissão na Inicialização**: O ciclo de inicialização do contêiner SWAG verifica a presença e validade do certificado SSL. Se ausente, ele solicita o certificado imediatamente à Let's Encrypt (ou ZeroSSL) via desafio ACME (`VALIDATION=http` ou `VALIDATION=dns`/`duckdns`) antes de disponibilizar o NGINX, eliminando a dependência circular e contêineres adicionais.
   - **Renovação Automática**: O daemon cron interno do SWAG executa checagens periódicas (2x ao dia). Caso o certificado expire em menos de 30 dias, o Certbot renova silenciosamente e executa o recarregamento suave (*reload*) do NGINX sem derrubar conexões ativas e sem requerer cron no sistema operacional do host.
5. **Configuração Integrada de Autenticação Google OAuth2 no Instalador**:
   - Coleta e validação (via parâmetros de linha de comando ou assistente interativo) das chaves canônicas:
     - `spring.security.oauth2.client.registration.google.client-id`
     - `spring.security.oauth2.client.registration.google.client-secret`
   - Cálculo dinâmico e exibição imediata da **URI de Redirecionamento Autorizada** (`https://${SERVER_NAME}:${HTTPS_PORT}/login/oauth2/code/google`), fornecendo as instruções exatas para registro no Google Cloud Console.
   - Persistência das credenciais no `.env` com permissões restritas (`600`) e suporte a binding duplo (canônico e relaxado do Spring Boot).

---

## 2. Matriz de Compatibilidade e Atualização Tecnológica

| Componente | Versão Anterior | Nova Versão Alvo | Justificativa |
| :--- | :--- | :--- | :--- |
| **Spring Boot** | 3.4.2 LTS | **4.1.1** (Última versão) | Diretiva original do projeto (`exegese-ops`); arquitetura baseada em Spring Framework 7 e Jakarta EE 11. |
| **Java Version** | 21 (bytecode target) | **25 Nativo** (Major 69) | Requisito explícito do usuário; aproveitamento integral dos recursos de runtime e compilador do Java 25. |
| **Spring AI** | 1.0.0-M5 | **2.0.1** (`spring-ai-bom`) | Linha oficial do Spring AI compatível com Spring Boot 4.x e Jackson 3. |
| **Jakarta EE** | 10 | **11** | Baseline do Spring Boot 4.x e Servlet 6.1. |
| **Proxy Reverso** | Ausente (porta 8080 exposta) | **SWAG (NGINX + Certbot + Fail2ban)** | Imagem `linuxserver/swag`: proxy de borda com emissão no startup, SSE unbuffered e Fail2ban. |
| **Gestão TLS/SSL** | Manual / Ausente | **Let's Encrypt Autônomo (SWAG)** | Emissão no startup e renovação periódica nativa no próprio ciclo de vida do contêiner NGINX. |

---

## 3. Justificativa Arquitetural da Escolha do `linuxserver/swag`

Em resposta à diretiva de fazer o próprio NGINX solicitar o certificado na inicialização e renová-lo automaticamente ao expirar, a imagem **SWAG** (LinuxServer.io) é a melhor solução técnica em relação a múltiplos contêineres desacoplados:

1. **Ciclo de Vida de Certificados Autônomo e Unificado**:
   - Elimina o problema da dependência circular (*chicken-and-egg*): na inicialização, o init system (`s6-overlay`) do SWAG intercepta a subida, verifica a validade do certificado e, caso ausente ou próximo de expirar, executa o Certbot antes de abrir as portas de serviço do NGINX.
   - Renovação autônoma: um processo cron interno verifica os certificados duas vezes ao dia e executa `certbot renew` com recarga suave do NGINX (`nginx -s reload`). Nenhum contêiner auxiliar de Certbot ou cron no host é necessário.
2. **Defesa em Profundidade com Fail2ban Integrado**:
   - Com o privilégio `cap_add: - NET_ADMIN`, o SWAG monitora os logs do NGINX e bloqueia automaticamente endereços IP suspeitos no nível de firewall (iptables), protegendo a aplicação contra varreduras e ataques de força bruta antes que atinjam a JVM.
3. **Controle Determinístico de Streaming SSE**:
   - O SWAG preserva toda a flexibilidade do NGINX: configuramos a rota `/api/chat/stream` com `proxy_buffering off;`, `proxy_cache off;`, `chunked_transfer_encoding off;` e `proxy_set_header X-Accel-Buffering no;`, garantindo a latência zero necessária para o streaming de tokens do RAG.
4. **Simplicidade Operacional e Persistência Única**:
   - Todo o estado (certificados, chaves privadas, logs, configurações e bloqueios) é concentrado em um único volume persistente (`proxy_config:/config`), facilitando rotinas de backup e restauração.

---

## 4. Diagrama da Topologia Alvo

```
                              CLIENTES WEB / NAVEGADORES
                                         │
                                         ▼ Portas Customizadas (${HTTP_PORT} / ${HTTPS_PORT})
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        PROXY REVERSO HARDENED (SWAG)                                   │
│                        (Imagem: linuxserver/swag:latest)                               │
│                         Dominio: ${SERVER_NAME} / ${URL}                               │
│                                                                                        │
│  - Gestão TLS/SSL Autônoma: Solicitação no Startup + Renovação Automática via Cron    │
│  - Proteção Ativa contra Intrusão: Fail2ban (cap_add: NET_ADMIN)                       │
│  - Ocultação de Assinatura: server_tokens off                                          │
│  - Otimização SSE: proxy_buffering off; proxy_read_timeout 3600s                       │
│  - Limites de Payload: 50MB (upload de PDF) / 10MB (geral)                             │
│  - Repasse de Cabeçalhos: X-Forwarded-For, X-Forwarded-Proto, X-Forwarded-Port         │
│  - Volume Persistente Único: proxy_config:/config                                      │
└───────────────────────────────────────┬────────────────────────────────────────────────┘
                                        │ Rede Interna Docker (exegese-net)
                                        │ Porta 8080 (ISOLADA)
                                        ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        APLICAÇÃO EXEGESE AI (SPRING BOOT 4.1.1)                        │
│                               Runtime: Java 25 Nativo                                  │
│                                                                                        │
│  - server.forward-headers-strategy: framework                                          │
│  - Spring AI 2.0.1 (RAG, ChatClient, RRF Search)                                       │
│  - RateLimitFilter (Bucket4j - 2ª camada de segurança)                                 │
│  - InputSanitizationFilter (Prompt Injection)                                          │
│  - Google OAuth2 Client (spring.security.oauth2.client.registration.google.*)          │
│  - Actuator (/actuator/health)                                                         │
└───────────────────────────────────────┬────────────────────────────────────────────────┘
                                        │ Porta 5432 (Interna)
                                        ▼
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                               POSTGRESQL 17 + PGVECTOR                                 │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Plano de Implementação Detalhado por Etapas

---

### ETAPA 1: Atualização de Dependências para Spring Boot 4.1.1 e Java 25

#### 1.1. Modificação do `pom.xml`
1. Atualizar o `<parent>` para Spring Boot 4.1.1:
   ```xml
   <parent>
     <groupId>org.springframework.boot</groupId>
     <artifactId>spring-boot-starter-parent</artifactId>
     <version>4.1.1</version>
     <relativePath/>
   </parent>
   ```
2. Atualizar as propriedades de linguagem e dependências para Java 25 nativo e Spring AI 2.0.1:
   ```xml
   <properties>
     <java.version>25</java.version>
     <maven.compiler.source>25</maven.compiler.source>
     <maven.compiler.target>25</maven.compiler.target>
     <maven.compiler.release>25</maven.compiler.release>
     <spring-ai.version>2.0.1</spring-ai.version>
     <pdfbox.version>3.0.4</pdfbox.version>
     <bucket4j.version>8.10.1</bucket4j.version>
     <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
   </properties>
   ```
3. Declarar explicitamente a configuração do `maven-compiler-plugin`:
   ```xml
   <build>
     <plugins>
       <plugin>
         <groupId>org.apache.maven.plugins</groupId>
         <artifactId>maven-compiler-plugin</artifactId>
         <configuration>
           <release>25</release>
           <compilerArgs>
             <arg>-parameters</arg>
           </compilerArgs>
         </configuration>
       </plugin>
       <plugin>
         <groupId>org.springframework.boot</groupId>
         <artifactId>spring-boot-maven-plugin</artifactId>
       </plugin>
     </plugins>
   </build>
   ```

#### 1.2. Validação de Compatibilidade de Código
- Validar compatibilidade dos starters com Jakarta EE 11 / Servlet 6.1.
- Validar injeção de dependências estrita via parâmetros de construtor em todos os componentes.

---

### ETAPA 2: Validação de Compilação e Bytecode Java 25

1. Executar compilação limpa do projeto:
   ```bash
   mvn clean compile
   ```
2. Inspecionar o bytecode gerado para certificar a versão 25 nativa:
   ```bash
   javap -v target/classes/br/org/rivelino/exegese_ai/ExegeseAiApplication.class
   ```
   *Critério de Aceite*: Campo `major version` deve retornar exatamente **`69`** (Java 25).

---

### ETAPA 3: Criação da Estrutura e Configuração do Proxy Hardened (`linuxserver/swag`)

#### 3.1. Estrutura de Diretórios
```text
docker/
  └── proxy/
      ├── Dockerfile
      └── config/
          └── default
```

#### 3.2. Dockerfile do Proxy Reverso (`docker/proxy/Dockerfile`)
Baseado na imagem oficial `linuxserver/swag`, endurecendo os cabeçalhos e aplicando a configuração customizada do site do Exegese AI:

```dockerfile
FROM linuxserver/swag:latest

LABEL maintainer="Rivelino Patrício <rivelinopatricio@gmail.com>"
LABEL description="Hardened NGINX Reverse Proxy with Integrated Let's Encrypt for Exegese AI"

# Remove NGINX version from HTTP response headers for fingerprinting prevention
RUN sed -i 's/# server_tokens off;/server_tokens off;/' /config/nginx/nginx.conf 2>/dev/null || true

RUN mkdir -p /config/nginx/site-confs

# Copy default site configuration for Exegese AI
COPY config/default /config/nginx/site-confs/default
```

#### 3.3. Configuração do Site do Exegese AI (`docker/proxy/config/default`)
Configura o NGINX interno do SWAG com redirecionamento HTTP->HTTPS, inclusão das diretivas de segurança TLS gerenciadas pelo SWAG (`ssl.conf`), desativação de bufferização para Server-Sent Events (SSE) e limites de payload por endpoint:

```nginx
# ==============================================================================
# Exegese AI - NGINX Site Configuration (SWAG / LinuxServer.io)
# ==============================================================================

# HTTP Block: Automatic redirect to HTTPS
server {
    listen 80 default_server;
    listen [::]:80 default_server;

    server_name _;

    return 301 https://$host:$server_port$request_uri;
}

# HTTPS Block: SWAG-managed TLS termination and Spring Boot upstream
server {
    listen 443 ssl http2 default_server;
    listen [::]:443 ssl http2 default_server;

    server_name _;

    # Standardized and secure TLS directives managed by SWAG (ciphers, protocols, certs)
    include /config/nginx/ssl.conf;

    client_max_body_size 10M;

    # 1. SSE Streaming Route for RAG (Mandatory buffering deactivation)
    location /api/chat/stream {
        proxy_pass http://app:8080;
        proxy_http_version 1.1;
        proxy_set_header Connection "";

        proxy_buffering off;
        proxy_cache off;
        chunked_transfer_encoding off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_set_header X-Accel-Buffering no;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Port $server_port;
    }

    # 2. Manuals and PDF Documents Upload Route (Expanded 50MB limit)
    location /admin/documents/upload {
        client_max_body_size 50M;
        proxy_pass http://app:8080;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Port $server_port;
    }

    # 3. General Application Route (Spring Boot Web UI and APIs)
    location / {
        proxy_pass http://app:8080;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Port $server_port;
    }
}
```

---

### ETAPA 4: Integração do Spring Boot com o Proxy Reverso

#### 4.1. Configuração do `application.properties`
Configurar o Spring Boot para processar os cabeçalhos `X-Forwarded-*` enviados pelo proxy reverso:
```properties
server.port=${PORT:8080}
server.forward-headers-strategy=framework
server.tomcat.remoteip.remote-ip-header=X-Forwarded-For
server.tomcat.remoteip.protocol-header=X-Forwarded-Proto
server.tomcat.remoteip.internal-proxies=10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2[0-9]|3[0-1])\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3}|127\\.0\\.0\\.1
```

---

### ETAPA 5: Atualização da Orquestração Docker Compose

#### 5.1. Modificação do `docker-compose.yml`
1. **Serviço `app`**:
   - Isolamento total na rede `exegese-net` (porta 8080 não exposta diretamente no host).
   - Injeção das variáveis de ambiente de OAuth2 e credenciais.
2. **Serviço `proxy` (Substitui NGINX e Certbot isolados)**:
   ```yaml
   proxy:
     container_name: exegese-ai-proxy
     build:
       context: ./docker/proxy
     environment:
       - PUID=1000
       - PGID=1000
       - TZ=America/Sao_Paulo
       - URL=${SERVER_NAME:-localhost}
       - SUBDOMAINS=
       - VALIDATION=${VALIDATION:-http}
       - EMAIL=${LETSENCRYPT_EMAIL:-admin@exegese.ai}
       - STAGING=${LETSENCRYPT_STAGING:-false}
     cap_add:
       - NET_ADMIN
     ports:
       - "${HTTP_PORT:-80}:80"
       - "${HTTPS_PORT:-443}:443"
     volumes:
       - proxy_config:/config
     depends_on:
       app:
         condition: service_healthy
     networks:
       - exegese-net
     restart: always
   ```
3. **Volumes Compartilhados Unificados**:
   ```yaml
   volumes:
     pgdata:
       driver: local
     storage_data:
       driver: local
     upload_data:
       driver: local
     proxy_config:
       driver: local
   ```

---

### ETAPA 6: Atualização do Script `install.sh`

#### 6.1. Suporte a Parâmetros de Linha de Comando e Interatividade
- Flags de linha de comando suportadas:
  - `-H, --host <hostname>`: Especifica o FQDN ou nome do host (ex: `exegese.empresa.gov.br` ou `localhost`).
  - `--http-port <porta>`: Especifica a porta pública HTTP (padrão: `80` ou `8080` caso ocupada).
  - `--https-port <porta>`: Especifica a porta pública HTTPS (padrão: `443` ou `8443` caso ocupada).
  - `--email-ssl <email>`: E-mail para registro e alertas do Let's Encrypt.
  - `--google-client-id <id>`: Especifica diretamente o Client ID do Google OAuth2 (`spring.security.oauth2.client.registration.google.client-id`).
  - `--google-client-secret <secret>`: Especifica diretamente o Client Secret do Google OAuth2 (`spring.security.oauth2.client.registration.google.client-secret`).
  - `--initial-admin <email>`: Especifica o e-mail do primeiro administrador do sistema (`INITIAL_ADMIN_EMAIL`).
  - `--gemini-key`, `--openai-key`, `--anthropic-key`: Chaves de API para provedores de LLM.
- Assistente interativo:
  - Pergunta o nome do Host/FQDN.
  - Verifica se a porta HTTP informada está disponível no host; se estiver ocupada, sugere e solicita porta alternativa (ex: 8080).
  - Verifica se a porta HTTPS informada está disponível no host; se estiver ocupada, sugere e solicita porta alternativa (ex: 8443).
  - Pergunta e-mail de contato para o certificado Let's Encrypt (quando host diferente de localhost).

#### 6.2. Configuração de Google OAuth2 / OIDC & Cálculo da URI de Redirecionamento
- Assistente interativo e suporte a CLI para as credenciais canônicas do Spring Security:
  - `spring.security.oauth2.client.registration.google.client-id`
  - `spring.security.oauth2.client.registration.google.client-secret` (com entrada mascarada no terminal caso via prompt interativo).
- **Cálculo Dinâmico da URI de Redirecionamento Autorizada** (Authorized Redirect URI):
  - Em produção HTTPS (porta 443 padrão): `https://${SERVER_NAME}/login/oauth2/code/google`
  - Em produção com porta HTTPS customizada: `https://${SERVER_NAME}:${HTTPS_PORT}/login/oauth2/code/google`
  - Em desenvolvimento local HTTP: `http://${SERVER_NAME}:${HTTP_PORT}/login/oauth2/code/google`
- **Instruções ao Administrador no Terminal**:
  - O script exibe explicitamente as URIs calculadas para configuração no Google Cloud Console:
    - *Origens JavaScript autorizadas*: `https://${SERVER_NAME}:${HTTPS_PORT}`
    - *URIs de redirecionamento autorizados*: `https://${SERVER_NAME}:${HTTPS_PORT}/login/oauth2/code/google`
- **Gravação Segura no `.env`**:
  - Mapeamento das variáveis canônicas e relaxadas do Spring Boot com permissões restritas (600):
    ```env
    # Google OAuth2 / OIDC
    GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID}
    GOOGLE_CLIENT_SECRET=${GOOGLE_CLIENT_SECRET}
    SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID}
    SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=${GOOGLE_CLIENT_SECRET}
    ```

#### 6.3. Inicialização Autônoma do SWAG e Healthcheck
1. **Inicialização Direta da Stack**:
   - `docker compose up -d --build`
   - O contêiner `exegese-ai-proxy` (SWAG) assume automaticamente o ciclo de vida TLS:
     - Na inicialização, verifica e emite o certificado Let's Encrypt para `URL=${SERVER_NAME}`.
     - Em ambiente de produção, ativa o cron interno para renovação periódica a cada 12 horas.
     - Inicializa o NGINX e o Fail2ban sem necessidade de intervenção do instalador.
2. **Healthcheck Loop**:
   - O instalador verifica `http://localhost:${HTTP_PORT}/actuator/health` ou `https://localhost:${HTTPS_PORT}/actuator/health -k` até o status retornar `UP`.
3. **Mensagem Final & Resumo**:
   - Exibe a URL acessível com as portas configuradas (`https://${SERVER_NAME}:${HTTPS_PORT}`), o status das chaves Google OAuth2 cadastradas e o Redirect URI oficial registrado.

---

### ETAPA 7: Execução da Suíte Completa de Testes Automatizados

Executar a suíte de 41 testes automatizados para atestar a estabilidade completa:
```bash
mvn clean test
```

Classes verificadas:
- `RagQualityEvaluationTest` (Golden Dataset oficial + controle fora de escopo)
- `SecurityHardeningIntegrationTest` (Rate limit e proteção contra prompt injection)
- `SecurityIntegrationTest` (OAuth2 e RBAC)
- `RagOrchestrationIntegrationTest` (RAG e SSE streaming)
- `MultiProviderModelIntegrationTest` (Roteamento entre os 6 provedores e criptografia AES-256)
- `DocumentIngestionIntegrationTest` (Extração e chunking polimórfico)
- `HybridSearchIntegrationTest` (Reciprocal Rank Fusion)
- `AdminUserManagementIntegrationTest` (Gestão administrativa e permissões)
- `SubjectAndDocumentCatalogIntegrationTest` (Catálogo e uploads)
- `ChatInterfaceIntegrationTest` (Interface conversacional)
- `EntityPersistenceIntegrationTest` (Mapeamentos relacionais)
- `ExegeseAiApplicationTests` (Carregamento de contexto)

---

### ETAPA 8: Atualização de Documentações e Guias Operacionais

- Atualizar [`README.md`](file:///d:/GIT/exegese_ai/README.md):
  - Badge e descritivos: Spring Boot 4.1.1, Java 25 Nativo, Proxy Hardened SWAG e Certificados Let's Encrypt Autônomos.
  - Atualização do diagrama de arquitetura e tabela de variáveis de ambiente (`SERVER_NAME`, `HTTP_PORT`, `HTTPS_PORT`, `LETSENCRYPT_EMAIL`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`).
  - Documentação das flags do script `install.sh` (`--host`, `--http-port`, `--https-port`, `--email-ssl`, `--google-client-id`, `--google-client-secret`).
- Atualizar `.agents/skills/exegese-ops/SKILL.md`:
  - Registrar a pilha Spring Boot 4.1.1 + Java 25 + SWAG (NGINX + Certbot + Fail2ban) e o fluxo de autenticação Google OAuth2.

---

## 6. Critérios de Aceite Globais

1. **Compilação**: `mvn clean compile` gera arquivos `.class` com `major version 69` (Java 25 nativo) sob Spring Boot 4.1.1.
2. **Testes**: 100% dos testes existentes (41/41) executados com sucesso (`BUILD SUCCESS`).
3. **Segurança de Borda & Isolamento**: Aplicação Spring Boot isolada na rede interna; apenas o proxy SWAG expõe as portas configuradas, com proteção ativa do Fail2ban.
4. **Portas e Host Customizados**: O usuário pode especificar qualquer host e portas (ex: 8080/8443 em vez de 80/443), e o ambiente sobe perfeitamente mapeado.
5. **Certificados Let's Encrypt Autônomos (SWAG)**: O contêiner de proxy solicita o certificado Let's Encrypt de forma totalmente autônoma na inicialização para o domínio informado e gerencia a renovação periódica silenciosa via daemon cron interno (2x ao dia), sem necessidade de contêineres adicionais de Certbot ou intervenção do operador.
6. **Autenticação Google OAuth2 no Instalador**: O instalador aceita e valida `spring.security.oauth2.client.registration.google.client-id` e `spring.security.oauth2.client.registration.google.client-secret` tanto via linha de comando (`--google-client-id`, `--google-client-secret`) quanto de modo interativo, orientando o cadastro correto do Authorized Redirect URI no Google Cloud Console e persistindo as credenciais no `.env` com permissões restritas (`600`).
7. **Streaming SSE**: Fluxo contínuo sem bufferização no endpoint `/api/chat/stream`.
8. **Automação**: Script `install.sh` sobe o ambiente completo com SWAG e valida o healthcheck via proxy.
