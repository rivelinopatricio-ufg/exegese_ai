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

import br.org.rivelino.exegese_ai.domain.dto.CanonicalCitationDTO;
import br.org.rivelino.exegese_ai.domain.dto.SearchResultChunk;
import br.org.rivelino.exegese_ai.domain.entity.AiModelConfig;
import br.org.rivelino.exegese_ai.domain.entity.ChatMessage;
import br.org.rivelino.exegese_ai.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service orchestrating RAG inference, anti-hallucination guard rails, and SSE streaming.
 * <p>
 * The pipeline is not transactional: messages are persisted through {@link ChatTranscriptService} in short
 * transactions, so no database connection is held while the LLM generates the answer. The system prompt is
 * resolved per request from the i18n bundles with the locale captured in the web thread; there is no
 * shared, mutable prompt.
 * <p>
 * When the client goes away mid-answer, the upstream LLM stream is aborted and the partial answer is
 * discarded (not persisted): the transcript keeps the question without an answer.
 *
 * @author Rivelino Patrício
 */
@Service
public class RagOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RagOrchestrationService.class);

    public static final String SYSTEM_PROMPT_KEY = "rag.specialist.system.prompt";
    public static final Locale DEFAULT_LOCALE = Locale.of("pt", "BR");

    /** Delimiter tags that separate untrusted data (retrieved excerpts, user question) from instructions. */
    static final String CONTEXT_TAG = "official_context";
    static final String QUESTION_TAG = "user_question";

    private static final Pattern DELIMITER_TAG_PATTERN = Pattern.compile(
            "<(\\s*/?\\s*(?:official_context|source|title|excerpt|user_question)\\b)",
            Pattern.CASE_INSENSITIVE);

    private final HybridSearchService hybridSearchService;
    private final QueryRewritingService queryRewritingService;
    private final AntiHallucinationGuard antiHallucinationGuard;
    private final LlmProviderRouter providerRouter;
    private final LlmClientService llmClientService;
    private final ChatSessionAccessService sessionAccessService;
    private final ChatMessageRepository messageRepository;
    private final ChatTranscriptService transcriptService;
    private final JsonMapper objectMapper;
    private final MessageSource messageSource;

    public RagOrchestrationService(HybridSearchService hybridSearchService,
                                   QueryRewritingService queryRewritingService,
                                   AntiHallucinationGuard antiHallucinationGuard,
                                   LlmProviderRouter providerRouter,
                                   LlmClientService llmClientService,
                                   ChatSessionAccessService sessionAccessService,
                                   ChatMessageRepository messageRepository,
                                   ChatTranscriptService transcriptService,
                                   JsonMapper objectMapper,
                                   MessageSource messageSource) {
        this.hybridSearchService = hybridSearchService;
        this.queryRewritingService = queryRewritingService;
        this.antiHallucinationGuard = antiHallucinationGuard;
        this.providerRouter = providerRouter;
        this.llmClientService = llmClientService;
        this.sessionAccessService = sessionAccessService;
        this.messageRepository = messageRepository;
        this.transcriptService = transcriptService;
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
    }

    /**
     * Resolves the specialist system prompt for the given locale (pt-BR when null). Unsupported locales
     * fall back to the default bundle.
     *
     * @param locale Locale of the request that asked the question
     * @return The localized system prompt
     */
    public String resolveSystemPrompt(Locale locale) {
        return messageSource.getMessage(SYSTEM_PROMPT_KEY, null, locale != null ? locale : DEFAULT_LOCALE);
    }

    /**
     * Executes end-to-end RAG pipeline, emitting Server-Sent Events to the client.
     *
     * @param sessionId Active chat session identifier
     * @param userId Identifier of the authenticated user, resolved in the request thread; the session must belong to it
     * @param userQuestion Question asked by the user
     * @param subjectIds Active subject filters
     * @param locale Locale of the request, captured in the web thread (system prompt and messages)
     * @param emitter Spring MVC SseEmitter instance
     * @param cancelled Signals that the client went away; checked between steps and for every LLM token
     * @throws IllegalArgumentException when the session does not exist or belongs to another user (nothing is
     *         persisted nor emitted in that case)
     */
    public void streamRagResponse(UUID sessionId,
                                  UUID userId,
                                  String userQuestion,
                                  List<UUID> subjectIds,
                                  Locale locale,
                                  SseEmitter emitter,
                                  BooleanSupplier cancelled) {
        long startTime = System.currentTimeMillis();
        Locale effectiveLocale = locale != null ? locale : DEFAULT_LOCALE;

        // Ownership is checked again here (defense in depth): a foreign session behaves as a missing one
        if (sessionAccessService.findOwnedSession(sessionId, userId).isEmpty()) {
            throw new IllegalArgumentException("Chat session not found: " + sessionId);
        }

        List<ChatMessage> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        // 1. Persist User Message (own short transaction)
        transcriptService.saveUserMessage(sessionId, userQuestion, serializeSubjectIds(subjectIds));

        try {
            // 2. Contextual Query Rewriting
            String effectiveQuery = queryRewritingService.rewriteQuery(userQuestion, history);

            // 3. Hybrid Search Retrieval
            List<SearchResultChunk> chunks = hybridSearchService.search(effectiveQuery, subjectIds, 4);
            if (cancelled.getAsBoolean()) {
                logCancellation(sessionId);
                return;
            }

            // 4. Anti-Hallucination Guard Evaluation
            if (!antiHallucinationGuard.isGrounded(chunks)) {
                log.debug("No grounded evidence for session {}: {} candidates, best vector similarity {}",
                        sessionId, chunks.size(), antiHallucinationGuard.bestSimilarity(chunks));
                String refusal = antiHallucinationGuard.getRefusalMessage();
                emitToken(emitter, refusal);
                emitComplete(emitter);

                transcriptService.saveAssistantMessage(sessionId, refusal, null, "anti-hallucination-guard",
                        (int) (System.currentTimeMillis() - startTime));
                return;
            }

            // 5. Canonical Citations Extraction
            List<CanonicalCitationDTO> citations = extractCitations(chunks);
            String citationsJson = objectMapper.writeValueAsString(citations);
            emitEvent(emitter, "citation", citationsJson);

            // 6. Grounded Answer Synthesis & Streaming via Multi-Provider LLM (no transaction open here)
            AiModelConfig activeModel = providerRouter.getDefaultProvider();
            String apiKey = providerRouter.resolveApiKey(activeModel.getProvider());
            boolean hasKey = providerRouter.hasConfiguredKey(activeModel.getProvider());

            StringBuilder streamedAnswer = new StringBuilder();
            AtomicBoolean aborted = new AtomicBoolean(false);
            boolean llmStreamed = false;

            if (hasKey) {
                String systemPrompt = resolveSystemPrompt(effectiveLocale);
                String userPrompt = buildUserPromptWithContext(userQuestion, chunks);

                llmStreamed = llmClientService.streamInference(
                        activeModel,
                        apiKey,
                        systemPrompt,
                        userPrompt,
                        token -> {
                            if (aborted.get() || cancelled.getAsBoolean()) {
                                aborted.set(true);
                                // Thrown inside the provider's line consumer: closes the upstream HTTP stream
                                throw new ChatStreamCancelledException();
                            }
                            try {
                                emitToken(emitter, token);
                                streamedAnswer.append(token);
                            } catch (IOException | IllegalStateException e) {
                                // The client is gone (broken pipe or emitter already completed)
                                aborted.set(true);
                                throw new ChatStreamCancelledException();
                            }
                        }
                );
            }

            if (aborted.get() || cancelled.getAsBoolean()) {
                // Partial answer intentionally discarded: it would be stored as if it were complete
                logCancellation(sessionId);
                return;
            }

            String fullAnswer;
            if (!streamedAnswer.isEmpty()) {
                if (!llmStreamed) {
                    // Upstream failed after tokens were already delivered: never splice the fallback onto a
                    // partial answer, and store nothing that differs from what the user saw
                    String reference = ErrorReference.newReference();
                    log.warn("LLM stream failed mid-answer [ref={}, session={}]; partial answer discarded",
                            reference, sessionId);
                    emitFailure(emitter, effectiveLocale, reference);
                    return;
                }
                fullAnswer = streamedAnswer.toString();
            } else {
                // Fallback: structured multi-chunk document synthesis
                fullAnswer = generateFallbackGroundedAnswer(chunks);
                streamTokens(emitter, fullAnswer);
            }

            emitComplete(emitter);

            // 7. Persist Assistant Response (own short transaction)
            transcriptService.saveAssistantMessage(sessionId, fullAnswer, citationsJson, activeModel.getModelName(),
                    (int) (System.currentTimeMillis() - startTime));

        } catch (ChatStreamCancelledException e) {
            logCancellation(sessionId);
        } catch (IOException | RuntimeException e) {
            if (cancelled.getAsBoolean()) {
                logCancellation(sessionId);
                return;
            }
            String reference = ErrorReference.newReference();
            log.error("Error during RAG streaming orchestration [ref={}, session={}]", reference, sessionId, e);
            emitFailure(emitter, effectiveLocale, reference);
        }
    }

    /**
     * Sends a generic, localized {@code error} event (with the correlation reference only, never the exception
     * message) and completes the stream.
     *
     * @param emitter Stream to finish
     * @param locale Locale of the request
     * @param reference Correlation reference also written to the server log
     */
    public void emitFailure(SseEmitter emitter, Locale locale, String reference) {
        String message = messageSource.getMessage("chat.error.generic", new Object[]{reference},
                locale != null ? locale : DEFAULT_LOCALE);
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("message", message);
            payload.put("reference", reference);
            emitter.send(SseEmitter.event().name("error").data(objectMapper.writeValueAsString(payload)));
            emitter.complete();
        } catch (IOException | IllegalStateException e) {
            log.debug("Could not deliver the error event [ref={}]: client already disconnected", reference);
        }
    }

    private void logCancellation(UUID sessionId) {
        log.info("Chat stream cancelled by the client; partial answer discarded (session {})", sessionId);
    }

    private String serializeSubjectIds(List<UUID> subjectIds) {
        if (subjectIds == null || subjectIds.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(subjectIds);
        } catch (JacksonException e) {
            log.warn("Failed to serialize subjectIds to JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds the user turn with the retrieved excerpts and the question in clearly delimited sections. The
     * system prompt instructs the model to treat everything inside them strictly as data. Look-alike
     * delimiter tags inside the untrusted text are neutralized so they cannot close a section early.
     */
    String buildUserPromptWithContext(String userQuestion, List<SearchResultChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append('<').append(CONTEXT_TAG).append(">\n");
        for (int i = 0; i < chunks.size(); i++) {
            SearchResultChunk chunk = chunks.get(i);
            sb.append("<source index=\"").append(i + 1).append("\">\n");
            sb.append("<title>").append(neutralizeDelimiters(chunk.documentTitle()));
            if (chunk.chunkTitle() != null && !chunk.chunkTitle().isBlank()) {
                sb.append(" | ").append(neutralizeDelimiters(chunk.chunkTitle()));
            }
            sb.append("</title>\n");
            sb.append("<excerpt>\n").append(neutralizeDelimiters(chunk.content().trim())).append("\n</excerpt>\n");
            sb.append("</source>\n");
        }
        sb.append("</").append(CONTEXT_TAG).append(">\n\n");
        sb.append('<').append(QUESTION_TAG).append(">\n");
        sb.append(neutralizeDelimiters(userQuestion.trim())).append('\n');
        sb.append("</").append(QUESTION_TAG).append('>');
        return sb.toString();
    }

    static String neutralizeDelimiters(String text) {
        if (text == null) {
            return "";
        }
        Matcher matcher = DELIMITER_TAG_PATTERN.matcher(text);
        return matcher.replaceAll(match -> Matcher.quoteReplacement("&lt;" + match.group(1)));
    }

    private String generateFallbackGroundedAnswer(List<SearchResultChunk> chunks) {
        SearchResultChunk primary = chunks.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append(primary.content().trim()).append("\n\n");
        sb.append("— Fonte Oficial: ").append(primary.documentTitle());
        if (primary.chunkTitle() != null && !primary.chunkTitle().isBlank()) {
            sb.append(" (").append(primary.chunkTitle()).append(")");
        }
        return sb.toString();
    }

    private List<CanonicalCitationDTO> extractCitations(List<SearchResultChunk> chunks) {
        List<CanonicalCitationDTO> list = new ArrayList<>();
        for (SearchResultChunk chunk : chunks) {
            Integer page = null;
            Integer questionNum = null;
            String articleNum = null;
            String legalBasis = null;

            if (chunk.metadataJson() != null && !chunk.metadataJson().isBlank()) {
                try {
                    JsonNode node = objectMapper.readTree(chunk.metadataJson());
                    if (node.has("page")) page = node.get("page").asInt();
                    if (node.has("questionNumber")) questionNum = node.get("questionNumber").asInt();
                    if (node.has("articleNumber")) articleNum = node.get("articleNumber").asString();
                    if (node.has("legalBasis")) legalBasis = node.get("legalBasis").asString();
                } catch (@SuppressWarnings("unused") JacksonException ignored) {}
            }

            list.add(new CanonicalCitationDTO(
                    chunk.documentTitle(),
                    chunk.chunkTitle(),
                    page,
                    questionNum,
                    articleNum,
                    legalBasis
            ));
        }
        return list;
    }

    private void streamTokens(SseEmitter emitter, String text) throws IOException {
        String[] words = text.split("(?<=\\s)");
        for (String word : words) {
            emitToken(emitter, word);
        }
    }

    private void emitToken(SseEmitter emitter, String token) throws IOException {
        emitter.send(SseEmitter.event().name("token").data(objectMapper.writeValueAsString(token)));
    }

    private void emitEvent(SseEmitter emitter, String eventName, String data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    private void emitComplete(SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event().name("complete").data("[DONE]"));
        emitter.complete();
    }
}
