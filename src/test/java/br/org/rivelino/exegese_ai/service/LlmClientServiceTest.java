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

import br.org.rivelino.exegese_ai.domain.entity.AiModelConfig;
import br.org.rivelino.exegese_ai.domain.enums.ModelProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LlmClientService} without any external network access: endpoint policy enforcement
 * before every call (M3) and the connectivity test (T7), exercised against a loopback HTTP server standing in
 * for a local Ollama and mock LLM backends.
 *
 * @author Rivelino Patrício
 */
class LlmClientServiceTest {

    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private final CountDownLatch releaseStalledResponse = new CountDownLatch(1);
    private LlmClientService client;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body;
            String path = exchange.getRequestURI().getPath();
            if (status.get() == 200) {
                if (path.endsWith("/chat/completions")) {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    body = ("data: {\"choices\":[{\"delta\":{\"content\":\"Olá\"}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{\"content\":\" mundo\"}}]}\n\n"
                            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                } else if (path.endsWith(":streamGenerateContent")) {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    body = ("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Gemini token\"}]}}]}\n\n")
                            .getBytes(StandardCharsets.UTF_8);
                } else if (path.endsWith("/v1/messages")) {
                    exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                    body = ("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Claude token\"}}\n\n")
                            .getBytes(StandardCharsets.UTF_8);
                } else {
                    body = "{\"error\":\"secret upstream detail\"}".getBytes(StandardCharsets.UTF_8);
                }
            } else {
                body = "{\"error\":\"secret upstream detail\"}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        // Stalled upstream: headers and a first token, then the body stays open without any further data
        server.createContext("/stall/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"Olá\"}}]}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                releaseStalledResponse.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // client gave up on the stalled body
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new LlmClientService(JsonMapper.builder().build(), new LlmEndpointPolicy("", baseUrl));
    }

    @AfterEach
    void stopServer() {
        releaseStalledResponse.countDown();
        server.stop(0);
    }

    private static AiModelConfig config(ModelProvider provider, String model, String baseUrl) {
        AiModelConfig config = new AiModelConfig(provider, provider.name(), model);
        config.setBaseUrl(baseUrl);
        config.setActive(true);
        return config;
    }

    private LlmClientService createPermissiveClient() {
        LlmEndpointPolicy permissivePolicy = new LlmEndpointPolicy("", baseUrl) {
            @Override
            public String resolveBaseUrl(ModelProvider provider, String configuredBaseUrl) {
                return baseUrl;
            }

            @Override
            public void validate(ModelProvider provider, String candidate) {
                // Permissive for loopback unit tests
            }
        };
        return new LlmClientService(JsonMapper.builder().build(), permissivePolicy);
    }

    @Test
    @DisplayName("Ping succeeds with a minimal GET on the model list, reporting only the HTTP status")
    void testPingOk() {
        LlmPingResult result = client.ping(config(ModelProvider.OLLAMA_LOCAL, "llama3.2", baseUrl), "");

        assertThat(result.status()).isEqualTo(LlmPingResult.Status.OK);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(requests).containsExactly("GET /v1/models");
    }

    @Test
    @DisplayName("Ping exercises provider switches for Gemini, Claude, and cloud OpenAI-compatibles")
    void testPingProviders() {
        LlmClientService permissive = createPermissiveClient();

        LlmPingResult geminiPing = permissive.ping(config(ModelProvider.GEMINI, "gemini-2.5-flash", baseUrl), "key-gemini");
        assertThat(geminiPing.status()).isEqualTo(LlmPingResult.Status.OK);

        LlmPingResult claudePing = permissive.ping(config(ModelProvider.CLAUDE, "claude-3-7-sonnet", baseUrl), "key-claude");
        assertThat(claudePing.status()).isEqualTo(LlmPingResult.Status.OK);

        LlmPingResult openAiPing = permissive.ping(config(ModelProvider.OPENAI, "gpt-4o", baseUrl), "key-openai");
        assertThat(openAiPing.status()).isEqualTo(LlmPingResult.Status.OK);

        LlmPingResult cerebrasPing = permissive.ping(config(ModelProvider.CEREBRAS, "gpt-oss", baseUrl), "key-cerebras");
        assertThat(cerebrasPing.status()).isEqualTo(LlmPingResult.Status.OK);
    }

    @Test
    @DisplayName("Ping reports an illegal argument error when model name produces an invalid URI")
    void testPingInvalidUri() {
        LlmClientService permissive = createPermissiveClient();
        LlmPingResult result = permissive.ping(config(ModelProvider.GEMINI, "invalid uri model with space ^|", baseUrl), "key-test");
        assertThat(result.status()).isEqualTo(LlmPingResult.Status.ERROR);
    }

    @Test
    @DisplayName("Ping maps refused credentials and other HTTP errors without exposing the upstream body")
    void testPingHttpErrors() {
        status.set(401);
        LlmPingResult unauthorized = client.ping(config(ModelProvider.OLLAMA_LOCAL, "llama3.2", baseUrl), "");
        assertThat(unauthorized).isEqualTo(new LlmPingResult(LlmPingResult.Status.AUTH_FAILED, 401));

        status.set(503);
        LlmPingResult unavailable = client.ping(config(ModelProvider.OLLAMA_LOCAL, "llama3.2", baseUrl), "");
        assertThat(unavailable).isEqualTo(new LlmPingResult(LlmPingResult.Status.HTTP_ERROR, 503));
        assertThat(unavailable.toString()).doesNotContain("secret");
    }

    @Test
    @DisplayName("Ping reports an unreachable endpoint")
    void testPingUnreachable() {
        String closedPort = baseUrl;
        server.stop(0);
        LlmPingResult result = client.ping(config(ModelProvider.OLLAMA_LOCAL, "llama3.2", closedPort), "");
        assertThat(result.status()).isEqualTo(LlmPingResult.Status.UNREACHABLE);
    }

    @Test
    @DisplayName("Ping without an API key, or with a disallowed endpoint, sends nothing")
    void testPingRefusedLocally() {
        assertThat(client.ping(config(ModelProvider.OPENAI, "gpt-5-mini", null), " ").status())
                .isEqualTo(LlmPingResult.Status.NOT_CONFIGURED);
        assertThat(client.ping(config(ModelProvider.OPENAI, "gpt-5-mini", "https://attacker.example.com/v1"), "sk-test").status())
                .isEqualTo(LlmPingResult.Status.ENDPOINT_REJECTED);
        assertThat(client.ping(config(ModelProvider.OPENAI, "gpt-5-mini", baseUrl), "sk-test").status())
                .isEqualTo(LlmPingResult.Status.ENDPOINT_REJECTED);
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("Streaming refuses a base URL outside the allowlist before sending the API key")
    void testStreamingRefusesDisallowedEndpoint() {
        List<String> tokens = new ArrayList<>();
        boolean streamed = client.streamInference(config(ModelProvider.DEEPSEEK, "deepseek-chat", baseUrl),
                "sk-secret", "system", "question", tokens::add);

        assertThat(streamed).isFalse();
        assertThat(tokens).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("OpenAI-compatible streaming (local Ollama) parses the SSE deltas with the managed JSON mapper")
    void testOpenAiCompatibleStreaming() {
        List<String> tokens = new ArrayList<>();
        boolean streamed = client.streamInference(config(ModelProvider.OLLAMA_LOCAL, "llama3.2", baseUrl),
                "", "system", "question", tokens::add);

        assertThat(streamed).isTrue();
        assertThat(String.join("", tokens)).isEqualTo("Olá mundo");
        assertThat(requests).contains("POST /v1/chat/completions");
        assertThat(requestBodies.get(0)).contains("\"max_tokens\":1024").contains("\"model\":\"llama3.2\"");
    }

    @Test
    @DisplayName("Gemini and Claude HTTP streaming execute end-to-end and process generation options")
    void testGeminiAndClaudeHttpStreaming() {
        LlmClientService permissive = createPermissiveClient();

        // Gemini
        List<String> geminiTokens = new ArrayList<>();
        AiModelConfig geminiCfg = config(ModelProvider.GEMINI, "gemini-2.5-flash", baseUrl);
        geminiCfg.setTemperature(new BigDecimal("0.7"));
        geminiCfg.setMaxTokens(500);
        boolean geminiOk = permissive.streamInference(geminiCfg, "key-gemini", "sys", "quest", geminiTokens::add);
        assertThat(geminiOk).isTrue();
        assertThat(geminiTokens).containsExactly("Gemini token");

        // Claude
        List<String> claudeTokens = new ArrayList<>();
        AiModelConfig claudeCfg = config(ModelProvider.CLAUDE, "claude-3-7-sonnet", baseUrl);
        claudeCfg.setTemperature(new BigDecimal("0.5"));
        claudeCfg.setMaxTokens(1000);
        boolean claudeOk = permissive.streamInference(claudeCfg, "key-claude", "sys", "quest", claudeTokens::add);
        assertThat(claudeOk).isTrue();
        assertThat(claudeTokens).containsExactly("Claude token");
    }

    @Test
    @DisplayName("Gemini and Claude return false on upstream HTTP error")
    void testGeminiAndClaudeHttpErrors() {
        status.set(500);
        LlmClientService permissive = createPermissiveClient();

        List<String> geminiTokens = new ArrayList<>();
        boolean geminiOk = permissive.streamInference(config(ModelProvider.GEMINI, "gemini-2.5-flash", baseUrl),
                "key-gemini", "sys", "quest", geminiTokens::add);
        assertThat(geminiOk).isFalse();

        List<String> claudeTokens = new ArrayList<>();
        boolean claudeOk = permissive.streamInference(config(ModelProvider.CLAUDE, "claude-3-7-sonnet", baseUrl),
                "key-claude", "sys", "quest", claudeTokens::add);
        assertThat(claudeOk).isFalse();
    }

    @Test
    @DisplayName("OpenAI reasoning models are detected (max_completion_tokens, default temperature)")
    void testReasoningModelDetection() {
        assertThat(LlmClientService.isOpenAiReasoningModel("gpt-5-mini")).isTrue();
        assertThat(LlmClientService.isOpenAiReasoningModel("o3-mini")).isTrue();
        assertThat(LlmClientService.isOpenAiReasoningModel("gpt-4.1")).isFalse();
        assertThat(LlmClientService.isOpenAiReasoningModel(null)).isFalse();
    }

    @Test
    @DisplayName("Gemini streaming parses SSE candidates correctly")
    void testGeminiStreaming() {
        List<String> tokens = new ArrayList<>();
        AtomicBoolean hasEmitted = new AtomicBoolean(false);
        String sseChunk = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Gemini token\"}]}}]}";

        client.parseGeminiDataChunk(sseChunk, tokens::add, hasEmitted);

        assertThat(hasEmitted.get()).isTrue();
        assertThat(tokens).containsExactly("Gemini token");
    }

    @Test
    @DisplayName("Claude streaming parses content_block_delta correctly")
    void testClaudeStreaming() {
        List<String> tokens = new ArrayList<>();
        AtomicBoolean hasEmitted = new AtomicBoolean(false);
        String sseChunk = "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Claude token\"}}";

        client.parseClaudeDataChunk(sseChunk, tokens::add, hasEmitted);

        assertThat(hasEmitted.get()).isTrue();
        assertThat(tokens).containsExactly("Claude token");
    }

    @Test
    @DisplayName("Streaming returns false when API key is missing for providers that require key")
    void testStreamMissingApiKeyReturnsFalse() {
        List<String> tokens = new ArrayList<>();
        boolean streamed = client.streamInference(config(ModelProvider.GEMINI, "gemini-2.5-flash", baseUrl),
                "", "system", "question", tokens::add);

        assertThat(streamed).isFalse();
        assertThat(tokens).isEmpty();
    }

    @Test
    @DisplayName("Streaming returns false on HTTP error response from upstream")
    void testStreamHttpErrorReturnsFalse() {
        status.set(500);
        List<String> tokens = new ArrayList<>();
        boolean streamed = client.streamInference(config(ModelProvider.OLLAMA_LOCAL, "llama3.2", baseUrl),
                "", "system", "question", tokens::add);

        assertThat(streamed).isFalse();
        assertThat(tokens).isEmpty();
    }

    @Test
    @DisplayName("Streaming handles ChatStreamCancelledException when consumer aborts")
    void testStreamClientCancelled() {
        boolean streamed = client.streamInference(config(ModelProvider.OLLAMA_LOCAL, "llama3.2", baseUrl),
                "", "system", "question", token -> {
                    throw new ChatStreamCancelledException();
                });

        assertThat(streamed).isFalse();
    }

    @Test
    @DisplayName("A response body that stalls after the headers is abandoned by the watchdog, freeing the worker")
    void testStalledResponseBodyIsAbandoned() {
        LlmClientService watchdogClient = new LlmClientService(JsonMapper.builder().build(),
                new LlmEndpointPolicy("", baseUrl), Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofMillis(50));
        try {
            List<String> tokens = new ArrayList<>();
            long start = System.nanoTime();
            boolean streamed = watchdogClient.streamInference(
                    config(ModelProvider.OLLAMA_LOCAL, "llama3.2", baseUrl + "/stall"), "", "system", "question", tokens::add);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(streamed).isFalse();
            assertThat(tokens).containsExactly("Olá");
            assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            watchdogClient.shutdownWatchdog();
        }
    }
}
