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
import br.org.rivelino.exegese_ai.service.LlmProviderRouter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for Multi-Provider AI routing, AES-256 key management, and admin views.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MultiProviderModelIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private LlmProviderRouter providerRouter;

    @Autowired
    private AiModelConfigRepository modelConfigRepository;

    @Test
    @DisplayName("All 7 AI model provider ecosystems are bootstrapped on startup")
    void testBootstrapOfAllSevenProviders() {
        List<AiModelConfig> all = modelConfigRepository.findAll();
        assertThat(all).hasSize(7);

        assertThat(modelConfigRepository.findByProvider(ModelProvider.GEMINI)).isPresent();
        assertThat(modelConfigRepository.findByProvider(ModelProvider.CLAUDE)).isPresent();
        assertThat(modelConfigRepository.findByProvider(ModelProvider.OPENAI)).isPresent();
        assertThat(modelConfigRepository.findByProvider(ModelProvider.NEMOTRON)).isPresent();
        assertThat(modelConfigRepository.findByProvider(ModelProvider.DEEPSEEK)).isPresent();
        assertThat(modelConfigRepository.findByProvider(ModelProvider.OLLAMA_LOCAL)).isPresent();
        assertThat(modelConfigRepository.findByProvider(ModelProvider.CEREBRAS)).isPresent();

        Optional<AiModelConfig> defaultModel = modelConfigRepository.findByIsDefaultTrue();
        assertThat(defaultModel).isPresent();
    }

    @Test
    @DisplayName("API Key is encrypted at rest using AES-256 and decrypted on retrieval")
    void testApiKeyEncryptionAndDecryption() {
        String secretKey = "sk-ant-test-super-secret-key-12345";

        providerRouter.updateConfig(
                ModelProvider.CLAUDE,
                "Anthropic Claude",
                "claude-3-7-sonnet",
                "https://api.anthropic.com",
                secretKey,
                new BigDecimal("0.10"),
                1024
        );

        AiModelConfig config = modelConfigRepository.findByProvider(ModelProvider.CLAUDE).orElseThrow();
        assertThat(config.getApiKeyEncrypted()).isNotNull();
        assertThat(config.getApiKeyEncrypted()).isNotEqualTo(secretKey);

        String decryptedKey = providerRouter.resolveApiKey(ModelProvider.CLAUDE);
        assertThat(decryptedKey).isEqualTo(secretKey);
    }

    @Test
    @DisplayName("Cerebras API Key is encrypted with AES-256 and decrypted on retrieval")
    void testCerebrasApiKeyEncryptionAndDecryption() {
        String cerebrasKey = "csk-test-secret-key-98765";

        providerRouter.updateConfig(
                ModelProvider.CEREBRAS,
                "Cerebras Inference",
                "gpt-oss-120b",
                "https://api.cerebras.ai/v1",
                cerebrasKey,
                new BigDecimal("0.10"),
                2048
        );

        AiModelConfig config = modelConfigRepository.findByProvider(ModelProvider.CEREBRAS).orElseThrow();
        assertThat(config.getApiKeyEncrypted()).isNotNull();
        assertThat(config.getApiKeyEncrypted()).isNotEqualTo(cerebrasKey);

        String decryptedKey = providerRouter.resolveApiKey(ModelProvider.CEREBRAS);
        assertThat(decryptedKey).isEqualTo(cerebrasKey);
    }

    @Test
    @DisplayName("Admin dynamically switches default active AI model provider")
    void testSwitchDefaultProvider() {
        providerRouter.setDefaultProvider(ModelProvider.OPENAI);

        AiModelConfig defaultModel = providerRouter.getDefaultProvider();
        assertThat(defaultModel.getProvider()).isEqualTo(ModelProvider.OPENAI);

        AiModelConfig gemini = modelConfigRepository.findByProvider(ModelProvider.GEMINI).orElseThrow();
        assertThat(gemini.isDefault()).isFalse();

        // Switch to Cerebras
        providerRouter.setDefaultProvider(ModelProvider.CEREBRAS);
        AiModelConfig cerebrasModel = providerRouter.getDefaultProvider();
        assertThat(cerebrasModel.getProvider()).isEqualTo(ModelProvider.CEREBRAS);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("Admin accesses /admin/models and invokes ping connectivity test")
    void testAdminControllerEndpoints() throws Exception {
        mockMvc.perform(get("/admin/models"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/admin/models/OLLAMA_LOCAL/ping")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/admin/models/CEREBRAS/ping")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("Standard user without ADMIN role is forbidden on /admin/models (403)")
    void testUserForbiddenOnModelsAdmin() throws Exception {
        mockMvc.perform(get("/admin/models"))
                .andExpect(status().isForbidden());
    }
}
