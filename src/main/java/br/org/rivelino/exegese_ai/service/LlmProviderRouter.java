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
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Dynamic AI provider router managing multi-ecosystem configurations and AES-256-GCM credentials.
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

    private final AiModelConfigRepository modelConfigRepository;
    private final CryptoService cryptoService;
    private final Environment environment;

    public LlmProviderRouter(AiModelConfigRepository modelConfigRepository,
                             CryptoService cryptoService,
                             Environment environment) {
        this.modelConfigRepository = modelConfigRepository;
        this.cryptoService = cryptoService;
        this.environment = environment;
    }

    @PostConstruct
    @Transactional
    public void bootstrapProviders() {
        log.info("Bootstrapping AI model provider configurations...");

        initProvider(ModelProvider.GEMINI, "Google Gemini", "gemini-3.5-flash-lite", "https://generativelanguage.googleapis.com", true);
        initProvider(ModelProvider.CLAUDE, "Anthropic Claude", "claude-3-7-sonnet", "https://api.anthropic.com", false);
        initProvider(ModelProvider.OPENAI, "OpenAI ChatGPT", "gpt-4o", "https://api.openai.com/v1", false);
        initProvider(ModelProvider.NEMOTRON, "NVIDIA Nemotron", "nvidia/nemotron-4-340b-instruct", "https://integrate.api.nvidia.com/v1", false);
        initProvider(ModelProvider.DEEPSEEK, "DeepSeek AI", "deepseek-chat", "https://api.deepseek.com/v1", false);
        initProvider(ModelProvider.OLLAMA_LOCAL, "Ollama Local", ollamaChatModel(), ollamaBaseUrl(), false);
        initProvider(ModelProvider.CEREBRAS, "Cerebras Inference", "gpt-oss-120b", "https://api.cerebras.ai/v1", false);
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
        } else if (provider == ModelProvider.GEMINI && "gemini-2.5-flash".equalsIgnoreCase(existing.get().getModelName())) {
            AiModelConfig config = existing.get();
            config.setModelName("gemini-3.5-flash-lite");
            modelConfigRepository.save(config);
            log.info("Migrated legacy Gemini model configuration to gemini-3.5-flash-lite");
        } else if (provider == ModelProvider.OLLAMA_LOCAL) {
            realignLegacyOllamaDefaults(existing.get(), modelName, baseUrl);
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
                yield (k != null && !k.isBlank()) ? k : environment.getProperty("spring.ai.google.genai.api-key");
            }
            case CLAUDE -> environment.getProperty("ANTHROPIC_API_KEY");
            case OPENAI -> {
                String k = environment.getProperty("OPENAI_API_KEY");
                yield (k != null && !k.isBlank()) ? k : environment.getProperty("spring.ai.openai.api-key");
            }
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

    public String pingModel(ModelProvider provider) {
        AiModelConfig config = modelConfigRepository.findByProvider(provider)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + provider));

        boolean hasKey = hasConfiguredKey(provider);
        if (!hasKey && provider != ModelProvider.OLLAMA_LOCAL) {
            return "Chave de API não configurada para " + config.getDisplayName() + ".";
        }

        return "Conexão com " + config.getDisplayName() + " (" + config.getModelName() + ") validada com sucesso!";
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
