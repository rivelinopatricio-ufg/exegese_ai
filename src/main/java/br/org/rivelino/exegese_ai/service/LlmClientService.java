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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import br.org.rivelino.exegese_ai.domain.entity.AiModelConfig;
import br.org.rivelino.exegese_ai.domain.enums.ModelProvider;

/**
 * Service orchestrating streaming inference across supported multi-model AI ecosystems.
 * <p>
 * Every outbound call first resolves the provider base URL through {@link LlmEndpointPolicy}: a URL that
 * is not https or whose host is outside the provider allowlist is refused before the API key leaves the
 * application. HTTP redirects are never followed.
 *
 * @author Rivelino Patrício
 */
@Service
public class LlmClientService {

    private static final Logger log = LoggerFactory.getLogger(LlmClientService.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration PING_TIMEOUT = Duration.ofSeconds(10);
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final JsonMapper objectMapper;
    private final LlmEndpointPolicy endpointPolicy;
    private final HttpClient httpClient;
    private final HttpClient pingHttpClient;

    public LlmClientService(JsonMapper objectMapper, LlmEndpointPolicy endpointPolicy) {
        this.objectMapper = objectMapper;
        this.endpointPolicy = endpointPolicy;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.pingHttpClient = HttpClient.newBuilder()
                .connectTimeout(PING_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Streams conversational response from the active AI provider, pushing tokens to the consumer.
     *
     * @param config Active model configuration
     * @param apiKey Decrypted API key (or empty for Ollama)
     * @param systemPrompt System instruction prompt
     * @param userPrompt User prompt containing context and question
     * @param tokenConsumer Callback receiving generated tokens in real time
     * @return true if streaming succeeded and at least one token was emitted; false otherwise
     */
    public boolean streamInference(AiModelConfig config,
                                   String apiKey,
                                   String systemPrompt,
                                   String userPrompt,
                                   Consumer<String> tokenConsumer) {
        if (config == null || !config.isActive()) {
            log.warn("Cannot stream inference: AI model config is null or inactive");
            return false;
        }

        ModelProvider provider = config.getProvider();
        if (provider != ModelProvider.OLLAMA_LOCAL && (apiKey == null || apiKey.isBlank())) {
            log.warn("Cannot stream inference: Missing API key for provider {}", provider);
            return false;
        }

        String baseUrl;
        try {
            baseUrl = endpointPolicy.resolveBaseUrl(provider, config.getBaseUrl());
        } catch (LlmEndpointRejectedException e) {
            log.warn("Refusing LLM call for provider {}: configured base URL violates the endpoint policy ({})",
                    provider, e.getReason());
            return false;
        }

        try {
            return switch (provider) {
                case GEMINI -> streamGemini(config, baseUrl, apiKey, systemPrompt, userPrompt, tokenConsumer);
                case CLAUDE -> streamClaude(config, baseUrl, apiKey, systemPrompt, userPrompt, tokenConsumer);
                case OPENAI, CEREBRAS, DEEPSEEK, NEMOTRON, OLLAMA_LOCAL ->
                        streamOpenAiCompatible(config, baseUrl, apiKey, systemPrompt, userPrompt, tokenConsumer);
            };
        } catch (IOException e) {
            log.error("I/O error during LLM streaming for provider {}: {}", provider, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            log.warn("LLM streaming interrupted for provider {}: {}", provider, e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        } catch (ChatStreamCancelledException e) {
            // Raised by the token consumer; leaving the lines stream closed the upstream HTTP connection
            log.debug("LLM streaming for provider {} aborted: client disconnected", provider);
            return false;
        } catch (RuntimeException e) {
            log.error("Runtime error during LLM streaming for provider {}: {}", provider, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Tests connectivity with a minimal, token-free request: the model metadata endpoint for Gemini and
     * Claude, the model list for OpenAI-compatible providers. Only the HTTP status is inspected; the response
     * body is discarded. The request times out after 10 seconds.
     *
     * @param config Provider configuration
     * @param apiKey Decrypted API key (may be empty for Ollama)
     * @return Outcome of the test
     */
    public LlmPingResult ping(AiModelConfig config, String apiKey) {
        ModelProvider provider = config.getProvider();
        if (provider != ModelProvider.OLLAMA_LOCAL && (apiKey == null || apiKey.isBlank())) {
            return LlmPingResult.of(LlmPingResult.Status.NOT_CONFIGURED);
        }

        String baseUrl;
        try {
            baseUrl = endpointPolicy.resolveBaseUrl(provider, config.getBaseUrl());
        } catch (LlmEndpointRejectedException e) {
            log.warn("Ping refused for provider {}: base URL violates the endpoint policy ({})", provider, e.getReason());
            return LlmPingResult.of(LlmPingResult.Status.ENDPOINT_REJECTED);
        }

        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder().timeout(PING_TIMEOUT).GET();
            String model = config.getModelName() != null ? config.getModelName().trim() : "";
            switch (provider) {
                case GEMINI -> builder.uri(URI.create(baseUrl + "/v1beta/models/" + model))
                        .header("x-goog-api-key", apiKey.trim());
                case CLAUDE -> builder.uri(URI.create(anthropicRoot(baseUrl) + "/v1/models/" + model))
                        .header("x-api-key", apiKey.trim())
                        .header("anthropic-version", ANTHROPIC_VERSION);
                case OPENAI, CEREBRAS, DEEPSEEK, NEMOTRON, OLLAMA_LOCAL -> {
                    builder.uri(URI.create(openAiApiRoot(baseUrl) + "/models"));
                    if (apiKey != null && !apiKey.isBlank()) {
                        builder.header("Authorization", "Bearer " + apiKey.trim());
                    }
                }
            }

            HttpResponse<Void> response = pingHttpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            LlmPingResult result = LlmPingResult.fromHttpStatus(response.statusCode());
            log.info("Ping of provider {} answered HTTP {}", provider, response.statusCode());
            return result;
        } catch (HttpTimeoutException e) {
            log.warn("Ping of provider {} timed out after {} s", provider, PING_TIMEOUT.toSeconds());
            return LlmPingResult.of(LlmPingResult.Status.TIMEOUT);
        } catch (IOException e) {
            log.warn("Ping of provider {} failed: {}", provider, e.getClass().getSimpleName());
            return LlmPingResult.of(LlmPingResult.Status.UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LlmPingResult.of(LlmPingResult.Status.ERROR);
        } catch (IllegalArgumentException e) {
            log.warn("Ping of provider {} could not build the request URI", provider);
            return LlmPingResult.of(LlmPingResult.Status.ERROR);
        }
    }

    private boolean streamGemini(AiModelConfig config,
                                 String baseUrl,
                                 String apiKey,
                                 String systemPrompt,
                                 String userPrompt,
                                 Consumer<String> tokenConsumer) throws IOException, InterruptedException {
        String endpoint = baseUrl + "/v1beta/models/" + config.getModelName().trim() + ":streamGenerateContent?alt=sse";

        Map<String, Object> req = new LinkedHashMap<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            req.put("systemInstruction", Map.of(
                    "parts", List.of(Map.of("text", systemPrompt))
            ));
        }

        req.put("contents", List.of(
                Map.of("role", "user", "parts", List.of(Map.of("text", userPrompt)))
        ));

        Map<String, Object> genConfig = new LinkedHashMap<>();
        if (config.getTemperature() != null) {
            genConfig.put("temperature", config.getTemperature());
        }
        if (config.getMaxTokens() != null) {
            genConfig.put("maxOutputTokens", config.getMaxTokens());
        }
        if (!genConfig.isEmpty()) {
            req.put("generationConfig", genConfig);
        }

        String jsonBody = objectMapper.writeValueAsString(req);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey.trim())
                .timeout(STREAM_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        AtomicBoolean hasEmitted = new AtomicBoolean(false);

        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            response.body().close();
            log.error("Gemini API returned HTTP status {} for model {}", response.statusCode(), config.getModelName());
            return false;
        }

        try (Stream<String> lines = response.body()) {
            lines.forEach(line -> {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (!data.isEmpty()) {
                        parseGeminiDataChunk(data, tokenConsumer, hasEmitted);
                    }
                }
            });
        }

        return hasEmitted.get();
    }

    private void parseGeminiDataChunk(String data, Consumer<String> tokenConsumer, AtomicBoolean hasEmitted) {
        JsonNode root;
        try {
            root = objectMapper.readTree(data);
        } catch (JacksonException e) {
            log.warn("Failed to parse Gemini SSE data chunk: {}", e.getOriginalMessage());
            return;
        }
        JsonNode candidates = root.get("candidates");
        if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
            JsonNode content = candidates.get(0).get("content");
            if (content != null && content.has("parts")) {
                JsonNode parts = content.get("parts");
                if (parts.isArray()) {
                    for (JsonNode part : parts) {
                        if (part.has("text")) {
                            String text = part.get("text").asString();
                            if (text != null && !text.isEmpty()) {
                                tokenConsumer.accept(text);
                                hasEmitted.set(true);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean streamOpenAiCompatible(AiModelConfig config,
                                           String baseUrl,
                                           String apiKey,
                                           String systemPrompt,
                                           String userPrompt,
                                           Consumer<String> tokenConsumer) throws IOException, InterruptedException {
        String endpoint = baseUrl.endsWith("/chat/completions") ? baseUrl : openAiApiRoot(baseUrl) + "/chat/completions";

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", config.getModelName());
        req.put("messages", messages);
        req.put("stream", true);

        boolean openAi = config.getProvider() == ModelProvider.OPENAI;
        // OpenAI reasoning models (gpt-5, o-series) only accept the default temperature
        if (config.getTemperature() != null && !(openAi && isOpenAiReasoningModel(config.getModelName()))) {
            req.put("temperature", config.getTemperature());
        }
        if (config.getMaxTokens() != null) {
            // OpenAI deprecated max_tokens (rejected by reasoning models); compatible providers still expect it
            req.put(openAi ? "max_completion_tokens" : "max_tokens", config.getMaxTokens());
        }

        String jsonBody = objectMapper.writeValueAsString(req);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(STREAM_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }

        AtomicBoolean hasEmitted = new AtomicBoolean(false);

        HttpResponse<Stream<String>> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            response.body().close();
            log.error("OpenAI-compatible API ({}) returned HTTP status {} for model {}",
                    config.getProvider(), response.statusCode(), config.getModelName());
            return false;
        }

        try (Stream<String> lines = response.body()) {
            lines.forEach(line -> {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (!data.isEmpty() && !"[DONE]".equals(data)) {
                        parseOpenAiDataChunk(data, tokenConsumer, hasEmitted);
                    }
                }
            });
        }

        return hasEmitted.get();
    }

    private void parseOpenAiDataChunk(String data, Consumer<String> tokenConsumer, AtomicBoolean hasEmitted) {
        JsonNode root;
        try {
            root = objectMapper.readTree(data);
        } catch (JacksonException e) {
            log.warn("Failed to parse OpenAI-compatible SSE data chunk: {}", e.getOriginalMessage());
            return;
        }
        JsonNode choices = root.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            JsonNode delta = choices.get(0).get("delta");
            if (delta != null && delta.has("content") && !delta.get("content").isNull()) {
                String content = delta.get("content").asString();
                if (content != null && !content.isEmpty()) {
                    tokenConsumer.accept(content);
                    hasEmitted.set(true);
                }
            }
        }
    }

    private boolean streamClaude(AiModelConfig config,
                                 String baseUrl,
                                 String apiKey,
                                 String systemPrompt,
                                 String userPrompt,
                                 Consumer<String> tokenConsumer) throws IOException, InterruptedException {
        String endpoint = anthropicRoot(baseUrl) + "/v1/messages";

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", config.getModelName());
        req.put("stream", true);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            req.put("system", systemPrompt);
        }
        req.put("messages", List.of(Map.of("role", "user", "content", userPrompt)));
        req.put("max_tokens", config.getMaxTokens() != null ? config.getMaxTokens() : 2048);
        if (config.getTemperature() != null) {
            req.put("temperature", config.getTemperature());
        }

        String jsonBody = objectMapper.writeValueAsString(req);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey.trim())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .timeout(STREAM_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        AtomicBoolean hasEmitted = new AtomicBoolean(false);

        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            response.body().close();
            log.error("Claude API returned HTTP status {} for model {}", response.statusCode(), config.getModelName());
            return false;
        }

        try (Stream<String> lines = response.body()) {
            lines.forEach(line -> {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (!data.isEmpty()) {
                        parseClaudeDataChunk(data, tokenConsumer, hasEmitted);
                    }
                }
            });
        }

        return hasEmitted.get();
    }

    private void parseClaudeDataChunk(String data, Consumer<String> tokenConsumer, AtomicBoolean hasEmitted) {
        JsonNode root;
        try {
            root = objectMapper.readTree(data);
        } catch (JacksonException e) {
            log.warn("Failed to parse Claude SSE data chunk: {}", e.getOriginalMessage());
            return;
        }
        String type = root.has("type") ? root.get("type").asString() : "";
        if ("content_block_delta".equals(type) && root.has("delta")) {
            JsonNode delta = root.get("delta");
            if (delta.has("text")) {
                String text = delta.get("text").asString();
                if (text != null && !text.isEmpty()) {
                    tokenConsumer.accept(text);
                    hasEmitted.set(true);
                }
            }
        }
    }

    /** Base URL of the OpenAI-compatible API version root (…/v1), whatever form the admin entered. */
    private static String openAiApiRoot(String baseUrl) {
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl.substring(0, baseUrl.length() - "/chat/completions".length());
        }
        return baseUrl.endsWith("/v1") ? baseUrl : baseUrl + "/v1";
    }

    /** Anthropic API root (without /v1/messages). */
    private static String anthropicRoot(String baseUrl) {
        return baseUrl.endsWith("/v1/messages")
                ? baseUrl.substring(0, baseUrl.length() - "/v1/messages".length())
                : baseUrl;
    }

    static boolean isOpenAiReasoningModel(String modelName) {
        if (modelName == null) {
            return false;
        }
        String model = modelName.trim().toLowerCase(Locale.ROOT);
        return model.startsWith("gpt-5") || model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4");
    }
}
