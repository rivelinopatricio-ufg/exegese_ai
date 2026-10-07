# Fase 14 — Resumo Técnico: Internacionalização (i18n), Suporte Multilíngue (pt-BR, en, es) e Seletor Pré-Login

## 1. Visão Geral

A Fase 14 consolidou a internacionalização (*i18n*) nativa no **Exegese AI**. A arquitetura garante que a experiência de acesso institucional e os serviços da plataforma operem em Português do Brasil (`pt-BR`), Inglês (`en`) ou Espanhol (`es`), resolvendo o idioma tanto por parâmetro de URL (`lang`) quanto por cookie HTTP durável (`EXEGESE_LOCALE`), preservando a preferência do operador através de fluxos de redirecionamento do Google OAuth2.

---

## 2. Entregas Técnicas Realizadas

### 2.1. Arquivos de Propriedades i18n em UTF-8
- **Diretório**: `src/main/resources/i18n/`
- **Arquivos criados**:
  - [`messages.properties`](file:///d:/GIT/exegese_ai/src/main/resources/i18n/messages.properties): Fallback padrão institucional da JVM (Português do Brasil).
  - [`messages_pt_BR.properties`](file:///d:/GIT/exegese_ai/src/main/resources/i18n/messages_pt_BR.properties): Localização em Português (`pt-BR`).
  - [`messages_en.properties`](file:///d:/GIT/exegese_ai/src/main/resources/i18n/messages_en.properties): Localização em Inglês (`en`).
  - [`messages_es.properties`](file:///d:/GIT/exegese_ai/src/main/resources/i18n/messages_es.properties): Localização em Espanhol (`es`).
- **Escopo do Dicionário**: Abrange branding, seletor de idiomas, tela de login, alertas, navegação comum, chat e painel administrativo.

### 2.2. Configuração Web MVC do Spring Boot
- **Implementação**: [`I18nConfiguration.java`](file:///d:/GIT/exegese_ai/src/main/java/br/org/rivelino/exegese_ai/config/I18nConfiguration.java)
- **Componentes Registrados**:
  - `CookieLocaleResolver`: Cookie `EXEGESE_LOCALE` com validade de 30 dias, caminho `/`, flag `HttpOnly`, e padrão `Locale.of("pt", "BR")`.
  - `LocaleChangeInterceptor`: Interceptador vinculado ao parâmetro `lang`.
- **Configuração de Propriedades**:
  - [`application.properties`](file:///d:/GIT/exegese_ai/src/main/resources/application.properties) e [`application-test.properties`](file:///d:/GIT/exegese_ai/src/test/resources/application-test.properties) configurados com `spring.messages.basename=i18n/messages` e `spring.messages.encoding=UTF-8`.

### 2.3. Interface de Login Acessível e Responsiva
- **Implementação**: [`login.html`](file:///d:/GIT/exegese_ai/src/main/resources/templates/login.html)
- **Recursos**:
  - Seletor de idiomas pré-login acessível (WCAG 2.1 AA) em segmented control com bandeiras e identificação de estado ativo (`bg-sky-600 text-white`).
  - Todas as mensagens estáticas migradas para expressões Thymeleaf `#{...}`.
  - Atributo dinâmico de idioma na tag raiz: `th:lang="${#locale.language}"`.

### 2.4. Validação e Testes Automatizados
- **Implementação**: [`I18nWebIntegrationTest.java`](file:///d:/GIT/exegese_ai/src/test/java/br/org/rivelino/exegese_ai/I18nWebIntegrationTest.java)
- **Resultados**: 4 testes de integração específicos aprovados com 100% de sucesso.
- **Suite Geral**: 45 testes executados sem falhas (`mvn test`).
