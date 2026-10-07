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
import br.org.rivelino.exegese_ai.domain.entity.ChatSession;
import br.org.rivelino.exegese_ai.repository.ChatMessageRepository;
import br.org.rivelino.exegese_ai.repository.ChatSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

/**
 * Service orchestrating RAG inference, anti-hallucination guard rails, and SSE streaming.
 *
 * @author Rivelino Patrício
 */
@Service
public class RagOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RagOrchestrationService.class);

    private final HybridSearchService hybridSearchService;
    private final QueryRewritingService queryRewritingService;
    private final AntiHallucinationGuard antiHallucinationGuard;
    private final LlmProviderRouter providerRouter;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    public RagOrchestrationService(HybridSearchService hybridSearchService,
                                  QueryRewritingService queryRewritingService,
                                  AntiHallucinationGuard antiHallucinationGuard,
                                  LlmProviderRouter providerRouter,
                                  ChatSessionRepository sessionRepository,
                                  ChatMessageRepository messageRepository,
                                  ObjectMapper objectMapper) {
        this.hybridSearchService = hybridSearchService;
        this.queryRewritingService = queryRewritingService;
        this.antiHallucinationGuard = antiHallucinationGuard;
        this.providerRouter = providerRouter;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes end-to-end RAG pipeline, emitting Server-Sent Events to the client.
     *
     * @param sessionId Active chat session identifier
     * @param userQuestion Question asked by the user
     * @param subjectIds Active subject filters
     * @param emitter Spring MVC SseEmitter instance
     */
    @Transactional
    public void streamRagResponse(UUID sessionId,
                                  String userQuestion,
                                  List<UUID> subjectIds,
                                  SseEmitter emitter) {
        long startTime = System.currentTimeMillis();

        ChatSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Chat session not found: " + sessionId));

        List<ChatMessage> history = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);

        // 1. Persist User Message
        ChatMessage userMessage = new ChatMessage(session, "USER", userQuestion);
        if (subjectIds != null && !subjectIds.isEmpty()) {
            userMessage.setAppliedSubjectIds(subjectIds.toString());
        }
        messageRepository.save(userMessage);

        try {
            // 2. Contextual Query Rewriting
            String effectiveQuery = queryRewritingService.rewriteQuery(userQuestion, history);

            // 3. Hybrid Search Retrieval
            List<SearchResultChunk> chunks = hybridSearchService.search(effectiveQuery, subjectIds, 4);

            // 4. Anti-Hallucination Guard Evaluation
            if (!antiHallucinationGuard.isGrounded(chunks)) {
                String refusal = antiHallucinationGuard.getRefusalMessage();
                emitToken(emitter, refusal);
                emitComplete(emitter);

                ChatMessage refusalMessage = new ChatMessage(session, "ASSISTANT", refusal);
                refusalMessage.setModelUsed("anti-hallucination-guard");
                refusalMessage.setExecutionDurationMs((int) (System.currentTimeMillis() - startTime));
                messageRepository.save(refusalMessage);
                return;
            }

            // 5. Canonical Citations Extraction
            List<CanonicalCitationDTO> citations = extractCitations(chunks);
            String citationsJson = objectMapper.writeValueAsString(citations);
            emitEvent(emitter, "citation", citationsJson);

            // 6. Grounded Answer Synthesis & Streaming
            AiModelConfig activeModel = providerRouter.getDefaultProvider();
            String fullAnswer = generateGroundedAnswer(chunks);

            // Stream response tokens
            streamTokens(emitter, fullAnswer);
            emitComplete(emitter);

            // 7. Persist Assistant Response
            ChatMessage assistantMessage = new ChatMessage(session, "ASSISTANT", fullAnswer);
            assistantMessage.setCitations(citationsJson);
            assistantMessage.setModelUsed(activeModel.getModelName());
            assistantMessage.setExecutionDurationMs((int) (System.currentTimeMillis() - startTime));
            messageRepository.save(assistantMessage);

        } catch (IOException | RuntimeException e) {
            log.error("Error during RAG streaming orchestration: {}", e.getMessage(), e);
            try {
                emitter.send(SseEmitter.event().name("error").data("Erro ao processar consulta: " + e.getMessage()));
            } catch (@SuppressWarnings("unused") IOException ignored) {}
            emitter.completeWithError(e);
        }
    }

    private String generateGroundedAnswer(List<SearchResultChunk> chunks) {
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
                    if (node.has("articleNumber")) articleNum = node.get("articleNumber").asText();
                    if (node.has("legalBasis")) legalBasis = node.get("legalBasis").asText();
                } catch (@SuppressWarnings("unused") JsonProcessingException ignored) {}
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
        emitter.send(SseEmitter.event().name("token").data(token));
    }

    private void emitEvent(SseEmitter emitter, String eventName, String data) throws IOException {
        emitter.send(SseEmitter.event().name(eventName).data(data));
    }

    private void emitComplete(SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event().name("complete").data("[DONE]"));
        emitter.complete();
    }
}
