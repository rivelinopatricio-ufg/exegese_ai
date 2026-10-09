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
package br.org.rivelino.exegese_ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.core.env.Environment;

import br.org.rivelino.exegese_ai.domain.dto.ModelConfigDTO;
import br.org.rivelino.exegese_ai.domain.entity.AiModelConfig;
import br.org.rivelino.exegese_ai.domain.enums.ModelProvider;
import br.org.rivelino.exegese_ai.repository.AiModelConfigRepository;

/**
 * Unit tests for {@link LlmProviderRouter} validating bootstrap migrations, key resolution, and configuration updates.
 *
 * @author Rivelino Patrício
 */
class LlmProviderRouterTest {

    @Mock
    private AiModelConfigRepository modelConfigRepository;

    @Mock
    private CryptoService cryptoService;

    @Mock
    private Environment environment;

    @Mock
    private LlmEndpointPolicy endpointPolicy;

    @Mock
    private LlmClientService llmClientService;

    private LlmProviderRouter router;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(endpointPolicy.defaultBaseUrl(any())).thenReturn("https://api.example.com");
        router = new LlmProviderRouter(modelConfigRepository, cryptoService, environment, endpointPolicy, llmClientService);
    }

    @Test
    @DisplayName("bootstrapProviders seeds all providers when repository is empty")
    void testBootstrapProvidersEmpty() {
        when(modelConfigRepository.findByProvider(any())).thenReturn(Optional.empty());

        router.bootstrapProviders();

        verify(modelConfigRepository, org.mockito.Mockito.times(7)).save(any(AiModelConfig.class));
    }

    @Test
    @DisplayName("bootstrapProviders migrates legacy model IDs for cloud providers")
    void testBootstrapProvidersMigratesLegacyModels() {
        AiModelConfig geminiConfig = new AiModelConfig(ModelProvider.GEMINI, "Gemini", "gemini-2.5-flash");
        AiModelConfig claudeConfig = new AiModelConfig(ModelProvider.CLAUDE, "Claude", "claude-3-7-sonnet");
        AiModelConfig openAiConfig = new AiModelConfig(ModelProvider.OPENAI, "OpenAI", "gpt-4o");

        when(modelConfigRepository.findByProvider(ModelProvider.GEMINI)).thenReturn(Optional.of(geminiConfig));
        when(modelConfigRepository.findByProvider(ModelProvider.CLAUDE)).thenReturn(Optional.of(claudeConfig));
        when(modelConfigRepository.findByProvider(ModelProvider.OPENAI)).thenReturn(Optional.of(openAiConfig));
        when(modelConfigRepository.findByProvider(ModelProvider.NEMOTRON)).thenReturn(Optional.of(new AiModelConfig(ModelProvider.NEMOTRON, "Nemotron", "current-model")));
        when(modelConfigRepository.findByProvider(ModelProvider.DEEPSEEK)).thenReturn(Optional.of(new AiModelConfig(ModelProvider.DEEPSEEK, "DeepSeek", "deepseek-chat")));
        when(modelConfigRepository.findByProvider(ModelProvider.CEREBRAS)).thenReturn(Optional.of(new AiModelConfig(ModelProvider.CEREBRAS, "Cerebras", "gpt-oss-120b")));
        when(modelConfigRepository.findByProvider(ModelProvider.OLLAMA_LOCAL)).thenReturn(Optional.of(new AiModelConfig(ModelProvider.OLLAMA_LOCAL, "Ollama", "llama3.2")));

        router.bootstrapProviders();

        assertThat(geminiConfig.getModelName()).isEqualTo(LlmProviderRouter.DEFAULT_GEMINI_MODEL);
        assertThat(claudeConfig.getModelName()).isEqualTo(LlmProviderRouter.DEFAULT_CLAUDE_MODEL);
        assertThat(openAiConfig.getModelName()).isEqualTo(LlmProviderRouter.DEFAULT_OPENAI_MODEL);
    }

    @Test
    @DisplayName("bootstrapProviders realigns legacy Ollama defaults")
    void testBootstrapProvidersRealignsOllama() {
        AiModelConfig legacyOllama = new AiModelConfig(ModelProvider.OLLAMA_LOCAL, "Ollama Local", "qwen2.5:7b");
        legacyOllama.setBaseUrl("http://localhost:11434");

        when(modelConfigRepository.findByProvider(ModelProvider.OLLAMA_LOCAL)).thenReturn(Optional.of(legacyOllama));
        when(environment.getProperty("exegese.ollama.base-url")).thenReturn("http://ollama:11434");
        when(environment.getProperty("exegese.ollama.chat-model")).thenReturn("llama3.2");

        router.bootstrapProviders();

        assertThat(legacyOllama.getModelName()).isEqualTo("llama3.2");
        assertThat(legacyOllama.getBaseUrl()).isEqualTo("http://ollama:11434");
    }

    @Test
    @DisplayName("getAllConfigs returns sorted list and getConfig returns provider DTO")
    void testGetConfigs() {
        AiModelConfig gemini = new AiModelConfig(ModelProvider.GEMINI, "Gemini", "gemini-flash");
        AiModelConfig openai = new AiModelConfig(ModelProvider.OPENAI, "OpenAI", "gpt-5");

        when(modelConfigRepository.findAll()).thenReturn(List.of(openai, gemini));
        when(modelConfigRepository.findByProvider(ModelProvider.GEMINI)).thenReturn(Optional.of(gemini));

        List<ModelConfigDTO> all = router.getAllConfigs();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).provider()).isEqualTo(ModelProvider.GEMINI);

        ModelConfigDTO dto = router.getConfig(ModelProvider.GEMINI);
        assertThat(dto.displayName()).isEqualTo("Gemini");

        when(modelConfigRepository.findByProvider(ModelProvider.CLAUDE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> router.getConfig(ModelProvider.CLAUDE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getDefaultProvider returns default or fallback to Gemini")
    void testGetDefaultProvider() {
        AiModelConfig gemini = new AiModelConfig(ModelProvider.GEMINI, "Gemini", "gemini-flash");
        when(modelConfigRepository.findByIsDefaultTrue()).thenReturn(Optional.empty());
        when(modelConfigRepository.findByProvider(ModelProvider.GEMINI)).thenReturn(Optional.of(gemini));

        AiModelConfig result = router.getDefaultProvider();
        assertThat(result.getProvider()).isEqualTo(ModelProvider.GEMINI);

        when(modelConfigRepository.findByProvider(ModelProvider.GEMINI)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> router.getDefaultProvider())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("setDefaultProvider updates all configs")
    void testSetDefaultProvider() {
        AiModelConfig gemini = new AiModelConfig(ModelProvider.GEMINI, "Gemini", "gemini-flash");
        AiModelConfig openai = new AiModelConfig(ModelProvider.OPENAI, "OpenAI", "gpt-5");
        gemini.setDefault(true);

        when(modelConfigRepository.findAll()).thenReturn(List.of(gemini, openai));

        router.setDefaultProvider(ModelProvider.OPENAI);

        assertThat(gemini.isDefault()).isFalse();
        assertThat(openai.isDefault()).isTrue();
        verify(modelConfigRepository).saveAll(any());
    }

    @Test
    @DisplayName("updateConfig modifies and saves entity with encrypted key")
    void testUpdateConfig() {
        AiModelConfig gemini = new AiModelConfig(ModelProvider.GEMINI, "Gemini", "old-model");
        when(modelConfigRepository.findByProvider(ModelProvider.GEMINI)).thenReturn(Optional.of(gemini));
        when(cryptoService.encrypt("secret-key")).thenReturn("encrypted-key");

        router.updateConfig(ModelProvider.GEMINI, "New Gemini", "new-model", "https://api.new.com",
                "secret-key", new BigDecimal("0.7"), 2000);

        assertThat(gemini.getDisplayName()).isEqualTo("New Gemini");
        assertThat(gemini.getModelName()).isEqualTo("new-model");
        assertThat(gemini.getBaseUrl()).isEqualTo("https://api.new.com");
        assertThat(gemini.getApiKeyEncrypted()).isEqualTo("encrypted-key");
        assertThat(gemini.getTemperature()).isEqualTo(new BigDecimal("0.7"));
        assertThat(gemini.getMaxTokens()).isEqualTo(2000);
        verify(modelConfigRepository).save(gemini);
    }

    @Test
    @DisplayName("resolveApiKey decrypts stored key or falls back to environment variables")
    void testResolveApiKeyFallback() {
        // Stored decrypted key
        AiModelConfig claude = new AiModelConfig(ModelProvider.CLAUDE, "Claude", "model");
        claude.setApiKeyEncrypted("enc-key");
        when(modelConfigRepository.findByProvider(ModelProvider.CLAUDE)).thenReturn(Optional.of(claude));
        when(cryptoService.decrypt("enc-key")).thenReturn("decrypted-claude-key");

        assertThat(router.resolveApiKey(ModelProvider.CLAUDE)).isEqualTo("decrypted-claude-key");

        // Stored unreadable key fallback to environment
        when(cryptoService.decrypt("enc-key")).thenThrow(new IllegalStateException("Master key rotated"));
        when(environment.getProperty("ANTHROPIC_API_KEY")).thenReturn("env-claude-key");
        assertThat(router.resolveApiKey(ModelProvider.CLAUDE)).isEqualTo("env-claude-key");

        // Environment fallback for other providers
        when(modelConfigRepository.findByProvider(ModelProvider.GEMINI)).thenReturn(Optional.empty());
        when(environment.getProperty("GEMINI_API_KEY")).thenReturn("gemini-env-key");
        assertThat(router.resolveApiKey(ModelProvider.GEMINI)).isEqualTo("gemini-env-key");

        when(modelConfigRepository.findByProvider(ModelProvider.OPENAI)).thenReturn(Optional.empty());
        when(environment.getProperty("OPENAI_API_KEY")).thenReturn("openai-env-key");
        assertThat(router.resolveApiKey(ModelProvider.OPENAI)).isEqualTo("openai-env-key");

        when(modelConfigRepository.findByProvider(ModelProvider.CEREBRAS)).thenReturn(Optional.empty());
        when(environment.getProperty("CEREBRAS_API_KEY")).thenReturn("dummy-key");
        assertThat(router.resolveApiKey(ModelProvider.CEREBRAS)).isEmpty();

        when(modelConfigRepository.findByProvider(ModelProvider.OLLAMA_LOCAL)).thenReturn(Optional.empty());
        assertThat(router.resolveApiKey(ModelProvider.OLLAMA_LOCAL)).isEmpty();
        assertThat(router.hasConfiguredKey(ModelProvider.OLLAMA_LOCAL)).isTrue();
    }

    @Test
    @DisplayName("pingModel tests connectivity and handles unconfigured or rejected endpoints")
    void testPingModel() {
        AiModelConfig gemini = new AiModelConfig(ModelProvider.GEMINI, "Gemini", "gemini-model");
        when(modelConfigRepository.findByProvider(ModelProvider.GEMINI)).thenReturn(Optional.of(gemini));
        when(environment.getProperty("GEMINI_API_KEY")).thenReturn(null);
        when(environment.getProperty("exegese.gemini.api-key")).thenReturn(null);

        // Not configured
        LlmPingResult resNotConfigured = router.pingModel(ModelProvider.GEMINI);
        assertThat(resNotConfigured.status()).isEqualTo(LlmPingResult.Status.NOT_CONFIGURED);

        // Endpoint rejected
        when(environment.getProperty("GEMINI_API_KEY")).thenReturn("valid-key");
        org.mockito.Mockito.doThrow(new LlmEndpointRejectedException(ModelProvider.GEMINI, "Disallowed host"))
                .when(endpointPolicy).validate(eq(ModelProvider.GEMINI), any());
        LlmPingResult resRejected = router.pingModel(ModelProvider.GEMINI);
        assertThat(resRejected.status()).isEqualTo(LlmPingResult.Status.ENDPOINT_REJECTED);

        // Successful ping delegation
        org.mockito.Mockito.doNothing().when(endpointPolicy).validate(eq(ModelProvider.GEMINI), any());
        when(llmClientService.ping(gemini, "valid-key")).thenReturn(LlmPingResult.of(LlmPingResult.Status.OK));
        LlmPingResult resOk = router.pingModel(ModelProvider.GEMINI);
        assertThat(resOk.status()).isEqualTo(LlmPingResult.Status.OK);
    }
}
