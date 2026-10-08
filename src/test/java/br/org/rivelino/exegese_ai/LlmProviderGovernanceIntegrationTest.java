/*******************************************************************************
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software 
 * and associated documentation files (the "Software"), to deal in the Software without 
 * restriction, including without limitation the rights to use, copy, modify, merge, publish, 
 * distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the 
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or 
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR 
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS 
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR 
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN 
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION 
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * This software uses third-party components, distributed accordingly to their own licenses.
 *******************************************************************************/
package br.org.rivelino.exegese_ai;

import br.org.rivelino.exegese_ai.domain.entity.AiModelConfig;
import br.org.rivelino.exegese_ai.domain.enums.ModelProvider;
import br.org.rivelino.exegese_ai.repository.AiModelConfigRepository;
import br.org.rivelino.exegese_ai.service.LlmClientService;
import br.org.rivelino.exegese_ai.service.LlmPingResult;
import br.org.rivelino.exegese_ai.service.LlmProviderBootstrapRunner;
import br.org.rivelino.exegese_ai.service.LlmProviderRouter;
import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * Integration tests for LLM provider governance: base URL allowlist on save (M3), real connectivity test
 * reported through localized (default pt-BR) messages (T7), legacy model id migration at bootstrap and the transactional
 * bootstrap runner (T6). The outbound client is the global Mockito mock: no network call is made.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LlmProviderGovernanceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiModelConfigRepository modelConfigRepository;

    @Autowired
    private LlmProviderRouter providerRouter;

    @Autowired
    private LlmClientService llmClientService;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Saving a base URL outside the allowlist is refused with a flash error and nothing changes")
    void testDisallowedBaseUrlRejectedOnSave() throws Exception {
        AiModelConfig before = modelConfigRepository.findByProvider(ModelProvider.OPENAI).orElseThrow();
        String originalUrl = before.getBaseUrl();
        String originalModel = before.getModelName();

        mockMvc.perform(post("/admin/models/OPENAI/save").with(csrf())
                        .param("displayName", "OpenAI")
                        .param("modelName", "exfiltration-model")
                        .param("baseUrl", "https://attacker.example.com/v1")
                        .param("apiKey", "sk-should-not-be-stored"))
                .andExpect(redirectedUrl("/admin/models"))
                .andExpect(flash().attribute("errorMessage", allOf(
                        containsString("OPENAI"), containsString("api.openai.com"))));

        AiModelConfig after = modelConfigRepository.findByProvider(ModelProvider.OPENAI).orElseThrow();
        assertThat(after.getBaseUrl()).isEqualTo(originalUrl);
        assertThat(after.getModelName()).isEqualTo(originalModel);

        mockMvc.perform(post("/admin/models/OPENAI/save").with(csrf())
                        .param("displayName", "OpenAI")
                        .param("modelName", "gpt-5-mini")
                        .param("baseUrl", "http://api.openai.com/v1"))
                .andExpect(redirectedUrl("/admin/models"))
                .andExpect(flash().attributeExists("errorMessage"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Saving an allowlisted https base URL succeeds")
    void testAllowedBaseUrlSaved() throws Exception {
        mockMvc.perform(post("/admin/models/CEREBRAS/save").with(csrf())
                        .param("displayName", "Cerebras")
                        .param("modelName", "gpt-oss-120b")
                        .param("baseUrl", "https://api.cerebras.ai/v1"))
                .andExpect(redirectedUrl("/admin/models?saved=true"));

        assertThat(modelConfigRepository.findByProvider(ModelProvider.CEREBRAS).orElseThrow().getBaseUrl())
                .isEqualTo("https://api.cerebras.ai/v1");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Ping performs a real connectivity test and reports the HTTP status, not a canned success")
    void testPingReportsProviderStatus() throws Exception {
        when(llmClientService.ping(any(), anyString())).thenReturn(LlmPingResult.fromHttpStatus(200));

        mockMvc.perform(post("/admin/models/GEMINI/ping").with(csrf()))
                .andExpect(redirectedUrl("/admin/models"))
                .andExpect(flash().attribute("pingResult", allOf(containsString("GEMINI"), containsString("HTTP 200"))))
                .andExpect(flash().attribute("pingSuccess", true));

        when(llmClientService.ping(any(), anyString())).thenReturn(LlmPingResult.fromHttpStatus(401));
        mockMvc.perform(post("/admin/models/GEMINI/ping").with(csrf()))
                .andExpect(flash().attribute("pingResult", containsString("recusou a chave de API (HTTP 401)")))
                .andExpect(flash().attribute("pingSuccess", false));

        when(llmClientService.ping(any(), anyString())).thenReturn(LlmPingResult.of(LlmPingResult.Status.TIMEOUT));
        mockMvc.perform(post("/admin/models/GEMINI/ping").with(csrf()))
                .andExpect(flash().attribute("pingResult", containsString("Tempo esgotado")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Ping is refused locally when the stored base URL violates the endpoint policy")
    void testPingRefusedForDisallowedStoredUrl() throws Exception {
        AiModelConfig gemini = modelConfigRepository.findByProvider(ModelProvider.GEMINI).orElseThrow();
        gemini.setBaseUrl("https://attacker.example.com");
        modelConfigRepository.saveAndFlush(gemini);

        mockMvc.perform(post("/admin/models/GEMINI/ping").with(csrf()))
                .andExpect(flash().attribute("pingResult", containsString("não é permitido pela política de segurança")));

        verify(llmClientService, never()).ping(any(), any());
        assertThat(providerRouter.pingModel(ModelProvider.GEMINI).status())
                .isEqualTo(LlmPingResult.Status.ENDPOINT_REJECTED);
    }

    @Test
    @DisplayName("Bootstrap migrates known-legacy model ids only, keeping administrator choices")
    void testLegacyModelIdsMigrated() {
        setModel(ModelProvider.CLAUDE, "claude-3-7-sonnet");
        setModel(ModelProvider.OPENAI, "GPT-4o");
        setModel(ModelProvider.GEMINI, "gemini-2.5-flash");
        setModel(ModelProvider.DEEPSEEK, "deepseek-reasoner");

        providerRouter.bootstrapProviders();

        assertThat(modelOf(ModelProvider.CLAUDE)).isEqualTo("claude-sonnet-5-5");
        assertThat(modelOf(ModelProvider.OPENAI)).isEqualTo("gpt-5-mini");
        assertThat(modelOf(ModelProvider.GEMINI)).isEqualTo("gemini-3.5-flash-lite");
        assertThat(modelOf(ModelProvider.DEEPSEEK)).isEqualTo("deepseek-reasoner");

        setModel(ModelProvider.CLAUDE, "claude-opus-5-5");
        providerRouter.bootstrapProviders();
        assertThat(modelOf(ModelProvider.CLAUDE)).isEqualTo("claude-opus-5-5");
    }

    @Test
    @DisplayName("Provider bootstrap runs from an ApplicationRunner through the transactional proxy")
    void testBootstrapRunsThroughTransactionalProxy() throws Exception {
        assertThat(applicationContext.getBeansOfType(LlmProviderBootstrapRunner.class)).hasSize(1);
        assertThat(AopUtils.isAopProxy(providerRouter)).isTrue();

        Method bootstrap = LlmProviderRouter.class.getMethod("bootstrapProviders");
        assertThat(bootstrap.isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(Arrays.stream(LlmProviderRouter.class.getDeclaredMethods())
                .noneMatch(m -> m.isAnnotationPresent(PostConstruct.class))).isTrue();
    }

    private void setModel(ModelProvider provider, String model) {
        AiModelConfig config = modelConfigRepository.findByProvider(provider).orElseThrow();
        config.setModelName(model);
        modelConfigRepository.saveAndFlush(config);
    }

    private String modelOf(ModelProvider provider) {
        return modelConfigRepository.findByProvider(provider).orElseThrow().getModelName();
    }
}
