# FASE 01 — PLANO DE IMPLEMENTAÇÃO DETALHADO
## Setup do Projeto Base Maven, Pacotes e Infraestrutura Docker

**Projeto**: Exegese AI (`exegese-ai`)  
**Pacote Base**: `br.org.rivelino.exegese_ai`  
**Runtime**: Java 25 LTS | Spring Boot 4.x / 3.4.x | Spring AI  
**Data**: 2026-10-06  

---

## 1. OBJETIVO DA FASE 01
Inicializar a estrutura definitiva do repositório, configurando o `pom.xml` com as coordenadas corporativas oficiais (`br.org.rivelino:exegese-ai`), pacotes Java canônicos, infraestrutura Docker Compose para o PostgreSQL 17 com pgvector e a classe de inicialização da aplicação Spring Boot acompanhada da suíte de teste de integridade.

---

## 2. CHECKLIST DE TAREFAS

- [ ] **Tarefa 1.1: Ajuste do `pom.xml`**:
  - Corrigir `groupId` para `br.org.rivelino`.
  - Garantir dependências de Web, Thymeleaf, Spring AI, PostgreSQL driver, PDFBox 3.0.4, Bucket4j 8.10.1, Spring Data JPA, Spring Security / OAuth2 Client, H2 Database (para testes locais em ambientes sem container ativo) e Testcontainers.
- [ ] **Tarefa 1.2: Infraestrutura Docker Compose e Variáveis de Ambiente**:
  - Atualizar `docker-compose.yml` alinhado ao container `exegese-ai-db` com `pgvector/pgvector:pg17` e healthcheck.
  - Atualizar `.env.example` com todas as variáveis obrigatórias: banco, OAuth2 Google (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `INITIAL_ADMIN_EMAIL`), portas e chaves dos 6 provedores de IA.
- [ ] **Tarefa 1.3: Estrutura Canônica de Diretórios e Pacotes Java**:
  - Criar estrutura `src/main/java/br/org/rivelino/exegese_ai/`:
    - `config/`
    - `controller/`
    - `domain/entity/`
    - `domain/dto/`
    - `domain/enums/`
    - `repository/`
    - `security/`
    - `service/`
  - Criar `src/main/resources/` com `application.yml` e scripts DDL base.
  - Criar `src/test/resources/` com `application-test.yml`.
- [ ] **Tarefa 1.4: Classe Principal de Inicialização**:
  - Implementar `ExegeseAiApplication.java` contendo cabeçalho de licença MIT padrão, javadoc com `@author Rivelino Patrício` e anotações `@SpringBootApplication`.
- [ ] **Tarefa 1.5: Suíte de Testes Unitários de Inicialização**:
  - Implementar `ExegeseAiApplicationTests.java` em `src/test/java/br/org/rivelino/exegese_ai/` validando o bootstrap do contexto com profile de teste isolado.
- [ ] **Tarefa 1.6: Verificação de Build e Testes**:
  - Executar `mvn clean test` garantindo 100% de sucesso.

---

## 3. CRITÉRIOS DE ACEITE
1. Compilação Maven (`mvn clean compile`) executada com sucesso sob JDK 25.
2. Execução de testes (`mvn test`) com sucesso sem erros ou falhas.
3. Repositório organizado segundo as diretrizes de `GEMINI.md` (licença, JavaDoc com `@author Rivelino Patrício`, sem injeção de campos).
