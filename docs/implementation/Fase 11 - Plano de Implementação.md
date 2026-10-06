# FASE 11 — PLANO DE IMPLEMENTAÇÃO DETALHADO
## Avaliação de Qualidade (Golden Dataset) & Hardening de Segurança

**Projeto**: Exegese AI (`exegese-ai`)  
**Pacote Base**: `br.org.rivelino.exegese_ai`  
**Runtime**: Java 25 LTS | Spring Boot 3.4.2 LTS | Bucket4j 8.10.1 | Spring Security  
**Data**: 2026-10-06  

---

## 1. OBJETIVO DA FASE 11
Fortalecer as proteções de segurança em nível de rede e aplicação (Rate Limiting via Bucket4j, Sanitização contra Prompt Injection, cabeçalhos de segurança HTTP rigorosos) e validar formalmente a acurácia factual da plataforma através do **Golden Dataset** de 20 perguntas oficiais do IRPF 2026 e 1 pergunta de controle fora do escopo com recusa canônica mandatória.

---

## 2. CHECKLIST DE TAREFAS

- [ ] **Tarefa 11.1: Filtro de Limite de Taxa (`RateLimitFilter`)**:
  - Implementar filtro baseado em **Bucket4j** (20 requisições por minuto por IP/identificador).
  - Retornar HTTP 429 Too Many Requests com mensagem clara e cabeçalho `Retry-After`.
- [ ] **Tarefa 11.2: Filtro de Sanitização contra Prompt Injection (`InputSanitizationFilter`)**:
  - Validar tamanho máximo de perguntas ($\le 2000$ caracteres).
  - Bloquear padrões conhecidos de ataque generativo (`ignore previous instructions`, `ignore todas as instruções`, `system prompt override`, delimitadores não autorizados).
  - Retornar HTTP 400 Bad Request em caso de violação.
- [ ] **Tarefa 11.3: Hardening de Cabeçalhos HTTP (`SecurityConfiguration`)**:
  - Configurar Content-Security-Policy (CSP), HSTS, X-Content-Type-Options (`nosniff`) e X-Frame-Options (`DENY`).
- [ ] **Tarefa 11.4: Suíte do Golden Dataset (`RagQualityEvaluationTest`)**:
  - Implementar teste parametrizado com as 20 perguntas oficiais do manual IRPF 2026:
    1. Obrigatoriedade (Perg. 001)
    2. Desobrigados (Perg. 002)
    3. Limite de Instrução (Perg. 401)
    4. Dedução por Dependente (Perg. 123/340)
    5. Filho de 25 anos na Faculdade (Perg. 350)
    6. Prótese de Silicone (Perg. 367)
    7. Teste de Covid em Farmácia (Perg. 381)
    8. Prazo de Entrega 2026 (Perg. 021)
    9. Multa Mínima sem Imposto Devido (Perg. 024)
    10. Pensão Alimentícia ADI 5422 (Perg. 223)
    11. Teto do Desconto Simplificado (Perg. 012)
    12. Declaração de Criptoativos (Perg. 473)
    13. Saldo em Poupança > R$ 800 mil (Perg. 010)
    14. Isenção Ganho de Capital Único Imóvel (Perg. 575/683)
    15. Isenção Ações Mercado à Vista (Perg. 707)
    16. Pensão de Ex-combatente FEB (Perg. 189)
    17. Apostas Bets Quota Fixa (Perg. 318)
    18. Cursinho Pré-Vestibular / Concurso (Perg. 414)
    19. Tabela Progressiva Anual 2026 (Perg. 061)
    20. Previdência Complementar (Perg. 187)
  - Incluir Pergunta 21 de controle fora do escopo (ICMS Combustíveis) com recusa 100% mandatória.
- [ ] **Tarefa 11.5: Suíte de Testes de Segurança (`SecurityHardeningIntegrationTest`)**:
  - Testar acionamento de bloqueio por rate limit HTTP 429.
  - Testar rejeição de prompt injection HTTP 400.
  - Testar presença dos cabeçalhos de segurança HTTP.
- [ ] **Tarefa 11.6: Execução de Testes e Homologação**:
  - Execução de `mvn test` garantindo 100% de sucesso.
- [ ] **Tarefa 11.7: Resumo Técnico e Commit**:
  - Gerar `docs/implementation/FASE 11 - Resumo Técnico.md`.
  - Realizar commit convencional em inglês.

---

## 3. CRITÉRIOS DE ACEITE
1. 20 requisições/minuto aplicadas no RateLimitFilter.
2. Injeções de prompt bloqueadas antes de atingir o orquestrador RAG.
3. 100% de aprovação na suíte de 21 perguntas do Golden Dataset.
4. Cabeçalhos de segurança HTTP ativos.
