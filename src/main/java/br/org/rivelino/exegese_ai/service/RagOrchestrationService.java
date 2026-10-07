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
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Service orchestrating RAG inference, anti-hallucination guard rails, and SSE streaming.
 *
 * @author Rivelino Patrício
 */
@Service
public class RagOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(RagOrchestrationService.class);

    public static final String DEFAULT_SPECIALIST_SYSTEM_PROMPT = """
        Você é o Exegese AI, assistente consultivo especialista e auditor com fundamentação documental estrita na base oficial selecionada.

        SUA MISSÃO:
        Resolver com precisão, clareza didática e máxima utilidade prática a dúvida do cidadão/contribuinte, transformando a complexidade dos manuais e normas em orientações claras e acionáveis.

        DIRETRIZES FUNDAMENTAIS DE RESPOSTA:
        1. RESPOSTA DIRETA & CONCLUSIVA:
           - Inicie respondendo de forma imediata à pergunta formulada (ex.: "Sim, é dedutível.", "Não, não é obrigatório apresentar declaração.", "Depende do cumprimento dos seguintes requisitos:").

        2. REQUISITOS, CONDIÇÕES & LIMITES:
           - Apresente os requisitos ou condições em tópicos claros.
           - Destaque em negrito todos os valores monetários (ex.: **R$ 2.275,08**, **R$ 30.639,90**), limites percentuais (ex.: **12%**, **20%**), idades (ex.: **até 24 anos**, **65 anos ou mais**) e datas/prazos (ex.: **até 30 de abril de 2026**).

        3. ORIENTAÇÃO PRÁTICA PASSO A PASSO (COMO PROCEDER):
           - Sempre que a dúvida envolver declaração ou procedimento, oriente objetivamente:
             * Em qual Ficha ou Menu declarar (ex.: "Ficha Rendimentos Isentos e Não Tributáveis", "Ficha Pagamentos Efetuados", "Ficha Bens e Direitos").
             * O Código correspondente, se informado nos documentos oficiais.
             * Cuidados e comprovantes necessários a manter em guarda.

        4. FIDELIDADE DOCUMENTAL ESTRITA (ZERO ALUCINAÇÃO):
           - Todas as afirmações devem ser 100% embasadas nos trechos do "CONTEXTO DOCUMENTAL OFICIAL" fornecido.
           - NUNCA invente leis, artigos, prazos ou regras não constantes no contexto.
           - Se os trechos oficiais não contiverem a informação necessária para algum aspecto da dúvida, declare expressamente essa limitação com honestidade.

        5. CITAÇÃO DAS FONTES OFICIAIS:
           - Indique as fontes oficiais citadas no texto dos trechos (ex.: número da pergunta, Instrução Normativa ou Lei).

        ESTRUTURA DE FORMATAÇÃO:
        - Utilize Markdown limpo com listas, marcadores, tabelas quando comparativo e negrito para números e conceitos-chave.
        """;

    public static volatile String SPECIALIST_SYSTEM_PROMPT = DEFAULT_SPECIALIST_SYSTEM_PROMPT;

    private final HybridSearchService hybridSearchService;
    private final QueryRewritingService queryRewritingService;
    private final AntiHallucinationGuard antiHallucinationGuard;
    private final LlmProviderRouter providerRouter;
    private final LlmClientService llmClientService;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    public RagOrchestrationService(HybridSearchService hybridSearchService,
                                   QueryRewritingService queryRewritingService,
                                   AntiHallucinationGuard antiHallucinationGuard,
                                   LlmProviderRouter providerRouter,
                                   LlmClientService llmClientService,
                                   ChatSessionRepository sessionRepository,
                                   ChatMessageRepository messageRepository,
                                   ObjectMapper objectMapper,
                                   MessageSource messageSource) {
        this.hybridSearchService = hybridSearchService;
        this.queryRewritingService = queryRewritingService;
        this.antiHallucinationGuard = antiHallucinationGuard;
        this.providerRouter = providerRouter;
        this.llmClientService = llmClientService;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.messageSource = messageSource;
    }

    @PostConstruct
    public void init() {
        configureSystemPromptForLocale(Locale.of("pt", "BR"));
    }

    /**
     * Configures the specialist system prompt dynamically according to the specified locale.
     *
     * @param locale Target locale for the system prompt
     */
    public void configureSystemPromptForLocale(Locale locale) {
        if (locale == null) {
            locale = Locale.of("pt", "BR");
        }
        try {
            String prompt = messageSource.getMessage("rag.specialist.system.prompt", null, locale);
            if (prompt != null && !prompt.isBlank()) {
                SPECIALIST_SYSTEM_PROMPT = prompt;
                log.info("RagOrchestrationService SPECIALIST_SYSTEM_PROMPT reconfigured for locale: {}", locale);
            }
        } catch (NoSuchMessageException e) {
            log.warn("Could not find rag.specialist.system.prompt for locale {}, keeping current prompt", locale, e);
        }
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
            try {
                userMessage.setAppliedSubjectIds(objectMapper.writeValueAsString(subjectIds));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize subjectIds to JSON: {}", e.getMessage());
            }
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

            // 6. Grounded Answer Synthesis & Streaming via Multi-Provider LLM
            AiModelConfig activeModel = providerRouter.getDefaultProvider();
            String apiKey = providerRouter.resolveApiKey(activeModel.getProvider());
            boolean hasKey = providerRouter.hasConfiguredKey(activeModel.getProvider());

            StringBuilder streamedAnswer = new StringBuilder();
            boolean llmStreamed = false;

            if (hasKey) {
                String systemPrompt = SPECIALIST_SYSTEM_PROMPT;
                String userPrompt = buildUserPromptWithContext(userQuestion, chunks);

                llmStreamed = llmClientService.streamInference(
                        activeModel,
                        apiKey,
                        systemPrompt,
                        userPrompt,
                        token -> {
                            try {
                                emitToken(emitter, token);
                                streamedAnswer.append(token);
                            } catch (IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        }
                );
            }

            String fullAnswer;
            if (llmStreamed && !streamedAnswer.isEmpty()) {
                fullAnswer = streamedAnswer.toString();
            } else {
                // Fallback: structured multi-chunk document synthesis
                fullAnswer = generateFallbackGroundedAnswer(chunks);
                streamTokens(emitter, fullAnswer);
            }

            emitComplete(emitter);

            // 7. Persist Assistant Response
            ChatMessage assistantMessage = new ChatMessage(session, "ASSISTANT", fullAnswer);
            assistantMessage.setCitations(citationsJson);
            assistantMessage.setModelUsed(activeModel.getModelName());
            assistantMessage.setExecutionDurationMs((int) (System.currentTimeMillis() - startTime));
            messageRepository.save(assistantMessage);

            session.setUpdatedAt(Instant.now());
            sessionRepository.save(session);

        } catch (IOException | RuntimeException e) {
            log.error("Error during RAG streaming orchestration: {}", e.getMessage(), e);
            try {
                emitter.send(SseEmitter.event().name("error").data("Erro ao processar consulta: " + e.getMessage()));
            } catch (@SuppressWarnings("unused") IOException ignored) {}
            emitter.completeWithError(e);
        }
    }

    private String buildUserPromptWithContext(String userQuestion, List<SearchResultChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CONTEXTO DOCUMENTAL OFICIAL ===\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            SearchResultChunk chunk = chunks.get(i);
            sb.append(String.format("[FONTE %d: %s", (i + 1), chunk.documentTitle()));
            if (chunk.chunkTitle() != null && !chunk.chunkTitle().isBlank()) {
                sb.append(" | ").append(chunk.chunkTitle());
            }
            sb.append("]\n");
            sb.append(chunk.content().trim()).append("\n\n");
        }
        sb.append("=== DÚVIDA DO CONTRIBUINTE / CIDADÃO ===\n");
        sb.append(userQuestion.trim());
        return sb.toString();
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
