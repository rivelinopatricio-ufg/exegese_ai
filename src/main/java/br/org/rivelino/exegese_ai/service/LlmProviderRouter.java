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

import br.org.rivelino.exegese_ai.domain.dto.ModelConfigDTO;
import br.org.rivelino.exegese_ai.domain.entity.AiModelConfig;
import br.org.rivelino.exegese_ai.domain.enums.ModelProvider;
import br.org.rivelino.exegese_ai.repository.AiModelConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Dynamic AI provider router managing multi-ecosystem configurations and AES-256-GCM credentials.
 * <p>
 * Provider rows are seeded and migrated by {@link #bootstrapProviders()}, invoked by
 * {@link LlmProviderBootstrapRunner} once the context is ready, so that it runs in a real transaction.
 * Base URLs are checked against the {@link LlmEndpointPolicy} when saved.
 *
 * @author Rivelino Patrício
 */
@Service
public class LlmProviderRouter {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderRouter.class);

    /** Defaults seeded by earlier versions for OLLAMA_LOCAL; rows still holding them are realigned on startup. */
    private static final String LEGACY_OLLAMA_MODEL = "qwen2.5:7b";
    private static final String LEGACY_OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String DEFAULT_OLLAMA_MODEL = "llama3.2";

    static final String DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite";
    static final String DEFAULT_CLAUDE_MODEL = "claude-sonnet-5-5";
    static final String DEFAULT_OPENAI_MODEL = "gpt-5-mini";

    /**
     * Retired or invalid model ids seeded by earlier versions, per provider, mapped to the current default.
     * Rows are updated at bootstrap only while they still hold one of these ids (admin choices are kept).
     */
    static final Map<ModelProvider, Map<String, String>> LEGACY_MODEL_IDS = Map.of(
            ModelProvider.GEMINI, Map.of("gemini-2.5-flash", DEFAULT_GEMINI_MODEL),
            ModelProvider.CLAUDE, Map.of("claude-3-7-sonnet", DEFAULT_CLAUDE_MODEL),
            ModelProvider.OPENAI, Map.of("gpt-4o", DEFAULT_OPENAI_MODEL)
    );

    private final AiModelConfigRepository modelConfigRepository;
    private final CryptoService cryptoService;
    private final Environment environment;
    private final LlmEndpointPolicy endpointPolicy;
    private final LlmClientService llmClientService;

    public LlmProviderRouter(AiModelConfigRepository modelConfigRepository,
                             CryptoService cryptoService,
                             Environment environment,
                             LlmEndpointPolicy endpointPolicy,
                             LlmClientService llmClientService) {
        this.modelConfigRepository = modelConfigRepository;
        this.cryptoService = cryptoService;
        this.environment = environment;
        this.endpointPolicy = endpointPolicy;
        this.llmClientService = llmClientService;
    }

    /**
     * Seeds missing provider rows and migrates known-legacy defaults. Must be called through the Spring proxy
     * (see {@link LlmProviderBootstrapRunner}) so that it runs in a transaction.
     */
    @Transactional
    public void bootstrapProviders() {
        log.info("Bootstrapping AI model provider configurations...");

        initProvider(ModelProvider.GEMINI, "Google Gemini", DEFAULT_GEMINI_MODEL, endpointPolicy.defaultBaseUrl(ModelProvider.GEMINI), true);
        initProvider(ModelProvider.CLAUDE, "Anthropic Claude", DEFAULT_CLAUDE_MODEL, endpointPolicy.defaultBaseUrl(ModelProvider.CLAUDE), false);
        initProvider(ModelProvider.OPENAI, "OpenAI ChatGPT", DEFAULT_OPENAI_MODEL, endpointPolicy.defaultBaseUrl(ModelProvider.OPENAI), false);
        initProvider(ModelProvider.NEMOTRON, "NVIDIA Nemotron", "nvidia/nemotron-4-340b-instruct", endpointPolicy.defaultBaseUrl(ModelProvider.NEMOTRON), false);
        initProvider(ModelProvider.DEEPSEEK, "DeepSeek AI", "deepseek-chat", endpointPolicy.defaultBaseUrl(ModelProvider.DEEPSEEK), false);
        initProvider(ModelProvider.OLLAMA_LOCAL, "Ollama Local", ollamaChatModel(), ollamaBaseUrl(), false);
        initProvider(ModelProvider.CEREBRAS, "Cerebras Inference", "gpt-oss-120b", endpointPolicy.defaultBaseUrl(ModelProvider.CEREBRAS), false);
    }

    private void initProvider(ModelProvider provider, String displayName, String modelName, String baseUrl, boolean isDefault) {
        Optional<AiModelConfig> existing = modelConfigRepository.findByProvider(provider);
        if (existing.isEmpty()) {
            AiModelConfig config = new AiModelConfig(provider, displayName, modelName);
            config.setBaseUrl(baseUrl);
            config.setActive(true);
            config.setDefault(isDefault);
            modelConfigRepository.save(config);
            log.info("Initialized default configuration for provider {}", provider);
        } else if (provider == ModelProvider.OLLAMA_LOCAL) {
            realignLegacyOllamaDefaults(existing.get(), modelName, baseUrl);
        } else {
            migrateLegacyModelId(existing.get());
        }
    }

    /**
     * Replaces a known-legacy model id (see {@link #LEGACY_MODEL_IDS}) with its current replacement.
     */
    private void migrateLegacyModelId(AiModelConfig config) {
        Map<String, String> legacyIds = LEGACY_MODEL_IDS.getOrDefault(config.getProvider(), Map.of());
        String current = config.getModelName() != null ? config.getModelName().trim().toLowerCase(Locale.ROOT) : "";
        String replacement = legacyIds.get(current);
        if (replacement != null) {
            String previous = config.getModelName();
            config.setModelName(replacement);
            config.setUpdatedAt(Instant.now());
            modelConfigRepository.save(config);
            log.info("Migrated legacy {} model id {} to {}", config.getProvider(), previous, replacement);
        }
    }

    /**
     * Replaces the untouched legacy OLLAMA_LOCAL defaults (a model never pulled by ollama_init.sh and a
     * localhost URL unreachable from the app container) with the configured ones. Admin edits are kept.
     */
    private void realignLegacyOllamaDefaults(AiModelConfig config, String modelName, String baseUrl) {
        boolean changed = false;
        if (LEGACY_OLLAMA_MODEL.equals(config.getModelName()) && !LEGACY_OLLAMA_MODEL.equals(modelName)) {
            config.setModelName(modelName);
            changed = true;
        }
        if (LEGACY_OLLAMA_BASE_URL.equals(config.getBaseUrl()) && !LEGACY_OLLAMA_BASE_URL.equals(baseUrl)) {
            config.setBaseUrl(baseUrl);
            changed = true;
        }
        if (changed) {
            config.setUpdatedAt(Instant.now());
            modelConfigRepository.save(config);
            log.info("Realigned legacy Ollama defaults to model {} at {}", config.getModelName(), config.getBaseUrl());
        }
    }

    private String ollamaBaseUrl() {
        String url = environment.getProperty("exegese.ollama.base-url");
        return (url != null && !url.isBlank()) ? url.trim() : LEGACY_OLLAMA_BASE_URL;
    }

    private String ollamaChatModel() {
        String model = environment.getProperty("exegese.ollama.chat-model");
        return (model != null && !model.isBlank()) ? model.trim() : DEFAULT_OLLAMA_MODEL;
    }

    public List<ModelConfigDTO> getAllConfigs() {
        return modelConfigRepository.findAll().stream()
                .sorted(Comparator.comparing(c -> c.getProvider().ordinal()))
                .map(this::toDTO)
                .toList();
    }

    public ModelConfigDTO getConfig(ModelProvider provider) {
        AiModelConfig config = modelConfigRepository.findByProvider(provider)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + provider));
        return toDTO(config);
    }

    public AiModelConfig getDefaultProvider() {
        return modelConfigRepository.findByIsDefaultTrue()
                .orElseGet(() -> modelConfigRepository.findByProvider(ModelProvider.GEMINI)
                        .orElseThrow(() -> new IllegalStateException("No default AI provider configured")));
    }

    @Transactional
    public void setDefaultProvider(ModelProvider provider) {
        List<AiModelConfig> all = modelConfigRepository.findAll();
        for (AiModelConfig config : all) {
            config.setDefault(config.getProvider() == provider);
            config.setUpdatedAt(Instant.now());
        }
        modelConfigRepository.saveAll(all);
        log.info("Default AI provider switched to {}", provider);
    }

    @Transactional
    public void updateConfig(ModelProvider provider, String displayName, String modelName,
                             String baseUrl, String newApiKey, BigDecimal temperature, Integer maxTokens) {
        AiModelConfig config = modelConfigRepository.findByProvider(provider)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + provider));

        // Rejected before anything is changed: https only and host in the provider allowlist (M3)
        endpointPolicy.validate(provider, baseUrl);

        if (displayName != null && !displayName.isBlank()) {
            config.setDisplayName(displayName.trim());
        }
        if (modelName != null && !modelName.isBlank()) {
            config.setModelName(modelName.trim());
        }
        if (baseUrl != null) {
            config.setBaseUrl(baseUrl.trim());
        }
        if (temperature != null) {
            config.setTemperature(temperature);
        }
        if (maxTokens != null) {
            config.setMaxTokens(maxTokens);
        }
        if (newApiKey != null && !newApiKey.isBlank()) {
            config.setApiKeyEncrypted(cryptoService.encrypt(newApiKey.trim()));
            log.info("Encrypted and stored new API key for provider {}", provider);
        }

        config.setUpdatedAt(Instant.now());
        modelConfigRepository.save(config);
    }

    public String resolveApiKey(ModelProvider provider) {
        Optional<AiModelConfig> configOpt = modelConfigRepository.findByProvider(provider);
        if (configOpt.isPresent() && configOpt.get().getApiKeyEncrypted() != null && !configOpt.get().getApiKeyEncrypted().isBlank()) {
            return cryptoService.decrypt(configOpt.get().getApiKeyEncrypted());
        }

        // Fallback to system environment variable or Spring configuration properties
        String envKey = switch (provider) {
            case GEMINI -> {
                String k = environment.getProperty("GEMINI_API_KEY");
                yield (k != null && !k.isBlank()) ? k : environment.getProperty("exegese.gemini.api-key");
            }
            case CLAUDE -> environment.getProperty("ANTHROPIC_API_KEY");
            case OPENAI -> environment.getProperty("OPENAI_API_KEY");
            case NEMOTRON -> environment.getProperty("NVIDIA_API_KEY");
            case DEEPSEEK -> environment.getProperty("DEEPSEEK_API_KEY");
            case OLLAMA_LOCAL -> "";
            case CEREBRAS -> {
                String k = environment.getProperty("CEREBRAS_API_KEY");
                yield (k != null && !k.isBlank()) ? k : environment.getProperty("cerebras.api-key");
            }
        };

        if (envKey != null && (envKey.isBlank() || "dummy-key".equalsIgnoreCase(envKey.trim()))) {
            return "";
        }

        return envKey != null ? envKey.trim() : "";
    }

    public boolean hasConfiguredKey(ModelProvider provider) {
        if (provider == ModelProvider.OLLAMA_LOCAL) {
            return true;
        }
        String key = resolveApiKey(provider);
        return key != null && !key.isBlank();
    }

    /**
     * Tests the connectivity of a provider with a minimal request (see {@link LlmClientService#ping}).
     *
     * @param provider Provider to test
     * @return Outcome of the test, without key material nor upstream bodies
     */
    public LlmPingResult pingModel(ModelProvider provider) {
        AiModelConfig config = modelConfigRepository.findByProvider(provider)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + provider));

        if (!hasConfiguredKey(provider)) {
            return LlmPingResult.of(LlmPingResult.Status.NOT_CONFIGURED);
        }
        try {
            endpointPolicy.validate(provider, config.getBaseUrl());
        } catch (LlmEndpointRejectedException e) {
            log.warn("Ping refused for provider {}: base URL violates the endpoint policy ({})", provider, e.getReason());
            return LlmPingResult.of(LlmPingResult.Status.ENDPOINT_REJECTED);
        }

        LlmPingResult result = llmClientService.ping(config, resolveApiKey(provider));
        return result != null ? result : LlmPingResult.of(LlmPingResult.Status.ERROR);
    }

    private ModelConfigDTO toDTO(AiModelConfig entity) {
        boolean hasKey = hasConfiguredKey(entity.getProvider());
        return new ModelConfigDTO(
                entity.getProvider(),
                entity.getDisplayName(),
                entity.getModelName(),
                entity.getBaseUrl(),
                null, // Never expose raw API key in DTO
                hasKey,
                entity.isActive(),
                entity.isDefault(),
                entity.getTemperature(),
                entity.getMaxTokens()
        );
    }
}
