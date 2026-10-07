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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.org.rivelino.exegese_ai.domain.entity.AiModelConfig;
import br.org.rivelino.exegese_ai.domain.enums.ModelProvider;

/**
 * Service orchestrating streaming inference across supported multi-model AI ecosystems.
 *
 * @author Rivelino Patrício
 */
@Service
public class LlmClientService {

    private static final Logger log = LoggerFactory.getLogger(LlmClientService.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmClientService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
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

        try {
            return switch (provider) {
                case GEMINI -> streamGemini(config, apiKey, systemPrompt, userPrompt, tokenConsumer);
                case CLAUDE -> streamClaude(config, apiKey, systemPrompt, userPrompt, tokenConsumer);
                case OPENAI, CEREBRAS, DEEPSEEK, NEMOTRON, OLLAMA_LOCAL ->
                        streamOpenAiCompatible(config, apiKey, systemPrompt, userPrompt, tokenConsumer);
            };
        } catch (IOException e) {
            log.error("I/O error during LLM streaming for provider {}: {}", provider, e.getMessage());
            return false;
        } catch (InterruptedException e) {
            log.warn("LLM streaming interrupted for provider {}: {}", provider, e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException e) {
            log.error("Runtime error during LLM streaming for provider {}: {}", provider, e.getMessage(), e);
            return false;
        }
    }

    private boolean streamGemini(AiModelConfig config,
                                 String apiKey,
                                 String systemPrompt,
                                 String userPrompt,
                                 Consumer<String> tokenConsumer) throws IOException, InterruptedException {
        String modelName = config.getModelName();
        if (modelName == null || modelName.isBlank() || "gemini-2.5-flash".equalsIgnoreCase(modelName)) {
            modelName = "gemini-3.5-flash-lite";
        }

        String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                ? config.getBaseUrl().replaceAll("/+$", "")
                : "https://generativelanguage.googleapis.com";

        String endpoint = baseUrl + "/v1beta/models/" + modelName + ":streamGenerateContent?alt=sse";

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
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        AtomicBoolean hasEmitted = new AtomicBoolean(false);

        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            log.error("Gemini API returned HTTP status {}: {}", response.statusCode(), endpoint);
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
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode candidates = root.get("candidates");
            if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
                JsonNode content = candidates.get(0).get("content");
                if (content != null && content.has("parts")) {
                    JsonNode parts = content.get("parts");
                    if (parts.isArray()) {
                        for (JsonNode part : parts) {
                            if (part.has("text")) {
                                String text = part.get("text").asText();
                                if (text != null && !text.isEmpty()) {
                                    tokenConsumer.accept(text);
                                    hasEmitted.set(true);
                                }
                            }
                        }
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse Gemini SSE data chunk: {}", e.getMessage());
        }
    }

    private boolean streamOpenAiCompatible(AiModelConfig config,
                                           String apiKey,
                                           String systemPrompt,
                                           String userPrompt,
                                           Consumer<String> tokenConsumer) throws IOException, InterruptedException {
        String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                ? config.getBaseUrl().replaceAll("/+$", "")
                : "https://api.openai.com/v1";

        String endpoint;
        if (baseUrl.endsWith("/chat/completions")) {
            endpoint = baseUrl;
        } else if (baseUrl.endsWith("/v1")) {
            endpoint = baseUrl + "/chat/completions";
        } else {
            endpoint = baseUrl + "/v1/chat/completions";
        }

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", config.getModelName());
        req.put("messages", messages);
        req.put("stream", true);

        if (config.getTemperature() != null) {
            req.put("temperature", config.getTemperature());
        }
        if (config.getMaxTokens() != null) {
            req.put("max_tokens", config.getMaxTokens());
        }

        String jsonBody = objectMapper.writeValueAsString(req);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }

        AtomicBoolean hasEmitted = new AtomicBoolean(false);

        HttpResponse<Stream<String>> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            log.error("OpenAI-compatible API returned HTTP status {}: {}", response.statusCode(), endpoint);
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
        try {
            JsonNode root = objectMapper.readTree(data);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null && delta.has("content") && !delta.get("content").isNull()) {
                    String content = delta.get("content").asText();
                    if (content != null && !content.isEmpty()) {
                        tokenConsumer.accept(content);
                        hasEmitted.set(true);
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse OpenAI-compatible SSE data chunk: {}", e.getMessage());
        }
    }

    private boolean streamClaude(AiModelConfig config,
                                 String apiKey,
                                 String systemPrompt,
                                 String userPrompt,
                                 Consumer<String> tokenConsumer) throws IOException, InterruptedException {
        String baseUrl = config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                ? config.getBaseUrl().replaceAll("/+$", "")
                : "https://api.anthropic.com";

        String endpoint = baseUrl.endsWith("/v1/messages") ? baseUrl : baseUrl + "/v1/messages";

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
                .header("anthropic-version", "2023-06-01")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        AtomicBoolean hasEmitted = new AtomicBoolean(false);

        HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            log.error("Claude API returned HTTP status {}: {}", response.statusCode(), endpoint);
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
        try {
            JsonNode root = objectMapper.readTree(data);
            String type = root.has("type") ? root.get("type").asText() : "";
            if ("content_block_delta".equals(type) && root.has("delta")) {
                JsonNode delta = root.get("delta");
                if (delta.has("text")) {
                    String text = delta.get("text").asText();
                    if (text != null && !text.isEmpty()) {
                        tokenConsumer.accept(text);
                        hasEmitted.set(true);
                    }
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse Claude SSE data chunk: {}", e.getMessage());
        }
    }
}
