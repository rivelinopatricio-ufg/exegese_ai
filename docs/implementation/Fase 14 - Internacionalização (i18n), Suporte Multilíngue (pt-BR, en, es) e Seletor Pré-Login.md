# Fase 14 — Plano de Implementação: Internacionalização (i18n), Suporte Multilíngue (pt-BR, en, es) e Seletor Pré-Login

## 1. Contexto e Objetivos

A Fase 14 introduz o suporte à internacionalização corporativa (*i18n*) na plataforma **Exegese AI**, estabelecendo o Português do Brasil (`pt-BR`) como padrão institucional e provendo pacotes de localização completos para Inglês (`en`) e Espanhol (`es`).

O objetivo central é possibilitar que usuários e auditores internacionais interajam com a plataforma em seu idioma de preferência, permitindo a seleção prévia do idioma diretamente na tela institucional de login antes de autenticar via Google OAuth2/OIDC, com persistência da preferência em cookie seguro de longa duração.

---

## 2. Escopo Detalhado

### 2.1. Arquitetura de Resolução de Idioma e Interceptação
- **LocaleResolver via Cookie**: Implementação de `CookieLocaleResolver` configurado com o cookie `EXEGESE_LOCALE`, path `/`, validade de 30 dias (`Duration.ofDays(30)`) e flag `HttpOnly`.
- **LocaleChangeInterceptor**: Interceptador registrado para monitorar o parâmetro de requisição HTTP `lang` (ex.: `/login?lang=en`, `/login?lang=es`, `/login?lang=pt_BR`).
- **Padrão Institucional Fallback**: `Locale.of("pt", "BR")` como padrão obrigatório do sistema.
- **Configuração Spring MVC**: Criação de [`I18nConfiguration.java`](file:///d:/GIT/exegese_ai/src/main/java/br/org/rivelino/exegese_ai/config/I18nConfiguration.java) implementando `WebMvcConfigurer`.

### 2.2. Geração dos Pacotes de Recursos (`.properties`)
Armazenamento no diretório `src/main/resources/i18n` em formato de propriedades padrão Java com codificação estrita em UTF-8:
- [`messages.properties`](file:///d:/GIT/exegese_ai/src/main/resources/i18n/messages.properties): Pacote fallback padrão da JVM em Português do Brasil.
- [`messages_pt_BR.properties`](file:///d:/GIT/exegese_ai/src/main/resources/i18n/messages_pt_BR.properties): Pacote localizado em Português do Brasil.
- [`messages_en.properties`](file:///d:/GIT/exegese_ai/src/main/resources/i18n/messages_en.properties): Pacote localizado em Inglês.
- [`messages_es.properties`](file:///d:/GIT/exegese_ai/src/main/resources/i18n/messages_es.properties): Pacote localizado em Espanhol.

### 2.3. Configuração de Propriedades da Aplicação
Configuração do `ResourceBundleMessageSource` em [`application.properties`](file:///d:/GIT/exegese_ai/src/main/resources/application.properties) e [`application-test.properties`](file:///d:/GIT/exegese_ai/src/test/resources/application-test.properties):
```properties
# Internationalization (i18n) Resource Bundles
spring.messages.basename=i18n/messages
spring.messages.encoding=UTF-8
spring.messages.fallback-to-system-locale=false
spring.messages.cache-duration=3600s
```

### 2.4. Reformulação da Interface de Login (`login.html`)
- **Seletor de Idiomas Pré-Login**:
  - Segmented control / pílulas com ícones de bandeiras (🇧🇷 PT, 🇺🇸 EN, 🇪🇸 ES) e rótulos acessíveis (WCAG 2.1 AA).
  - Destaque visual do idioma atualmente ativo utilizando a classe `bg-sky-600 text-white font-semibold shadow`.
  - Links dinâmicos apontando para `/login(lang='...')` preservando eventuais parâmetros de erro ou logout.
- **Substituição de Strings Estáticas**:
  - Título da página, títulos do card, alertas de autenticação, texto do botão Google OAuth2 e rodapé institucional convertidos para expressões Thymeleaf `#{...}`.
  - Atributo dinâmico de linguagem na raiz: `<html th:lang="${#locale.language}" ...>`.

### 2.5. Testes Automatizados de Integração
- Criação de [`I18nWebIntegrationTest.java`](file:///d:/GIT/exegese_ai/src/test/java/br/org/rivelino/exegese_ai/I18nWebIntegrationTest.java):
  - Validação do locale padrão `pt-BR` em acessos anônimos sem parâmetros.
  - Validação da troca de idioma para inglês via `?lang=en` com checagem de emissão do cookie `EXEGESE_LOCALE`.
  - Validação da troca de idioma para espanhol via `?lang=es` com checagem de emissão do cookie `EXEGESE_LOCALE`.
  - Validação da retenção do idioma preferido do usuário via envio prévio do cookie `EXEGESE_LOCALE=en`.

---

## 3. Matriz de Chaves e Dicionário i18n

| Chave | Descrição | pt-BR | en | es |
| :--- | :--- | :--- | :--- | :--- |
| `app.title` | Título da aplicação | Exegese AI | Exegese AI | Exegese AI |
| `app.tagline` | Slogan corporativo | Plataforma RAG de Rigor Exegético e Grounding Normativo | RAG Platform with Exegetical Rigor and Regulatory Grounding | Plataforma RAG de Rigor Exegético y Fundamentación Normativa |
| `lang.selector.label` | Rótulo de acessibilidade do seletor | Selecionar Idioma | Select Language | Seleccionar Idioma |
| `lang.pt_BR` | Rótulo Português | Português | English | Español |
| `lang.en` | Rótulo Inglês | Português | English | Español |
| `lang.es` | Rótulo Espanhol | Português | English | Español |
| `login.page.title` | Título da aba do browser | Exegese AI — Acesso Institucional | Exegese AI — Institutional Access | Exegese AI — Acceso Institucional |
| `login.header.title` | Título do card de login | Exegese AI | Exegese AI | Exegese AI |
| `login.header.subtitle` | Subtítulo do card de login | Plataforma RAG de Rigor Exegético e Grounding Normativo | RAG Platform with Exegetical Rigor and Regulatory Grounding | Plataforma RAG de Rigor Exegético y Fundamentación Normativa |
| `login.alert.error` | Alerta de falha de login | Falha na autenticação corporativa. Tente novamente. | Corporate authentication failed. Please try again. | Error en la autenticación corporativa. Inténtelo de nuevo. |
| `login.alert.logout` | Alerta de logout com sucesso | Sessão encerrada com sucesso. | Session ended successfully. | Sesión cerrada con éxito. |
| `login.button.google` | Botão Google OAuth2 | Entrar com Google | Sign in with Google | Iniciar sesión con Google |
| `login.footer.notice` | Aviso de conformidade LGPD | Acesso restrito e auditado. Em conformidade com a LGPD e padrões de segurança corporativa. | Restricted and audited access. In compliance with LGPD and corporate security standards. | Acceso restringido y auditado. De conformidad con la LGPD y estándares de seguridad corporativa. |

---

## 4. Critérios de Aceite e Validação

1. **Compilação**: `mvn test-compile` deve concluir com sucesso sem erros ou advertências impeditivas.
2. **Resolução de Idioma Padrão**: Uma requisição pura `GET /login` deve renderizar o conteúdo em Português do Brasil.
3. **Alternância Dinâmica**: Uma requisição com `?lang=en` ou `?lang=es` deve alterar o conteúdo na mesma resposta e gerar o cookie `EXEGESE_LOCALE`.
4. **Persistência via Cookie**: Requisições subsequentes sem parâmetro `lang` devem manter o idioma contido no cookie `EXEGESE_LOCALE`.
5. **Zero Regressões**: Toda a suíte de testes de integração (`mvn test`) deve executar com 100% de sucesso.
