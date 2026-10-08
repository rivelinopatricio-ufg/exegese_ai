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

import br.org.rivelino.exegese_ai.domain.entity.*;
import br.org.rivelino.exegese_ai.domain.enums.ModelProvider;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.*;
import br.org.rivelino.exegese_ai.service.AntiHallucinationGuard;
import br.org.rivelino.exegese_ai.service.ChatStreamCancelledException;
import br.org.rivelino.exegese_ai.service.LlmClientService;
import br.org.rivelino.exegese_ai.service.LlmProviderRouter;
import br.org.rivelino.exegese_ai.service.QueryRewritingService;
import br.org.rivelino.exegese_ai.service.RagOrchestrationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for RAG pipeline orchestration, zero hallucination guard, and SSE streaming.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RagOrchestrationIntegrationTest {

    private static final Locale PT_BR = Locale.of("pt", "BR");

    @Autowired
    private RagOrchestrationService ragService;

    @Autowired
    private QueryRewritingService queryRewritingService;

    @Autowired
    private ExegeseUserRepository userRepository;

    @Autowired
    private ChatSessionRepository sessionRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private ExegeseSubjectRepository subjectRepository;

    @Autowired
    private ExegeseDocumentRepository documentRepository;

    @Autowired
    private ExegeseChunkRepository chunkRepository;

    @Autowired
    private EntityManager entityManager;

    /** Hermetic mock provided by TestLlmClientConfiguration (no real LLM HTTP calls in tests). */
    @Autowired
    private LlmClientService llmClientService;

    @Autowired
    private LlmProviderRouter providerRouter;

    @Test
    @DisplayName("Zero Hallucination Guard emits canonical refusal when context is absent")
    void testAntiHallucinationRefusal() {
        ExegeseUser user = userRepository.save(new ExegeseUser("citizen@exegese.ai", "Cidadão Teste", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(user, "Sessão Teste Recusa"));

        ExegeseSubject emptySubject = subjectRepository.save(
                new ExegeseSubject("vazio", "Assunto Vazio", "Sem documentos")
        );

        TestSseEmitter emitter = new TestSseEmitter();

        ragService.streamRagResponse(
                session.getId(),
                user.getId(),
                "Como preparar uma receita culinária no imposto de renda?",
                List.of(emptySubject.getId()),
                PT_BR,
                emitter,
                () -> false
        );

        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getRole()).isEqualTo("USER");
        assertThat(messages.get(1).getRole()).isEqualTo("ASSISTANT");
        assertThat(messages.get(1).getContent()).isEqualTo(AntiHallucinationGuard.CANONICAL_REFUSAL_MESSAGE);
        verify(llmClientService, never()).streamInference(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Grounded RAG emits tokens, citations and stores exchange in chat session")
    void testGroundedResponseAndCitationStreaming() {
        ExegeseUser user = userRepository.save(new ExegeseUser("user.rag@exegese.ai", "Contribuinte RAG", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(user, "Consulta Aposentadoria"));

        ExegeseSubject subject = subjectRepository.save(
                new ExegeseSubject("irpf-aposentado", "Aposentadoria IRPF", "Assunto Aposentadoria")
        );

        ExegeseDocument doc = new ExegeseDocument(
                "Manual IRPF 2026",
                "manual.pdf",
                "storage/manual.pdf",
                "hash-doc-aposentado-99",
                2048L,
                "PDF"
        );
        doc.addSubject(subject);
        doc = documentRepository.save(doc);

        chunkRepository.save(new ExegeseChunk(
                doc,
                "hash-chunk-aposentado-99",
                20,
                "Pergunta 020 — Rendimentos de Aposentadoria",
                "Os rendimentos de aposentadoria pagos pela Previdência Social são isentos até o limite de R$ 1.903,98 para maiores de 65 anos.",
                "{\"page\": 35, \"questionNumber\": 20}"
        ));

        entityManager.flush();

        TestSseEmitter emitter = new TestSseEmitter();

        ragService.streamRagResponse(
                session.getId(),
                user.getId(),
                "Rendimentos de Aposentadoria maiores de 65 anos",
                List.of(subject.getId()),
                PT_BR,
                emitter,
                () -> false
        );

        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getRole()).isEqualTo("ASSISTANT");
        assertThat(messages.get(1).getContent()).contains("Previdência Social");
        assertThat(messages.get(1).getContent()).contains("1.903,98");
        assertThat(messages.get(1).getCitations()).contains("Pergunta 020");
    }

    @Test
    @DisplayName("Grounded RAG streams the LLM answer tokens and persists the synthesized response")
    void testGroundedResponseStreamedByLlm() {
        ExegeseUser user = userRepository.save(new ExegeseUser("user.llm@exegese.ai", "Contribuinte LLM", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(user, "Consulta LLM"));

        ExegeseSubject subject = subjectRepository.save(
                new ExegeseSubject("irpf-llm", "IRPF LLM", "Assunto LLM")
        );

        ExegeseDocument doc = new ExegeseDocument(
                "Manual IRPF 2026 LLM",
                "manual-llm.pdf",
                "storage/manual-llm.pdf",
                "hash-doc-llm-77",
                1024L,
                "PDF"
        );
        doc.addSubject(subject);
        doc = documentRepository.save(doc);

        chunkRepository.save(new ExegeseChunk(
                doc,
                "hash-chunk-llm-77",
                21,
                "Pergunta 021 — Moléstia Grave",
                "Os proventos de aposentadoria recebidos por portadores de moléstia grave são isentos do imposto de renda.",
                "{\"page\": 36, \"questionNumber\": 21}"
        ));

        entityManager.flush();

        // Ensure the default provider has a key so the LLM path is taken (rolled back by @Transactional)
        providerRouter.setDefaultProvider(ModelProvider.GEMINI);
        providerRouter.updateConfig(ModelProvider.GEMINI, null, null, null, "test-gemini-key", null, null);

        AtomicReference<String> systemPrompt = new AtomicReference<>();
        AtomicReference<String> userPrompt = new AtomicReference<>();
        when(llmClientService.streamInference(any(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    systemPrompt.set(invocation.getArgument(2));
                    userPrompt.set(invocation.getArgument(3));
                    Consumer<String> tokenConsumer = invocation.getArgument(4);
                    tokenConsumer.accept("Resposta sintetizada ");
                    tokenConsumer.accept("pelo modelo.");
                    return true;
                });

        TestSseEmitter emitter = new TestSseEmitter();

        ragService.streamRagResponse(
                session.getId(),
                user.getId(),
                "Proventos de aposentadoria por moléstia grave são isentos?",
                List.of(subject.getId()),
                PT_BR,
                emitter,
                () -> false
        );

        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getRole()).isEqualTo("ASSISTANT");
        assertThat(messages.get(1).getContent()).isEqualTo("Resposta sintetizada pelo modelo.");
        assertThat(messages.get(1).getCitations()).contains("Pergunta 021");
        verify(llmClientService).streamInference(any(), anyString(), anyString(), anyString(), any());

        // Per-request prompt in the request language, with untrusted data in delimited sections (M4)
        assertThat(systemPrompt.get()).contains("ZERO ALUCINAÇÃO").contains("<official_context>").contains("<user_question>");
        assertThat(userPrompt.get())
                .startsWith("<official_context>")
                .contains("<source index=\"1\">")
                .contains("moléstia grave são isentos")
                .endsWith("<user_question>\nProventos de aposentadoria por moléstia grave são isentos?\n</user_question>");
    }

    @Test
    @DisplayName("System prompt follows the locale of each request; there is no shared prompt state")
    void testSystemPromptFollowsRequestLocale() {
        ExegeseUser user = userRepository.save(new ExegeseUser("user.locale@exegese.ai", "Contribuinte Locale", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(user, "Consulta Locale"));
        ExegeseSubject subject = createGroundedSubject("locale");

        List<String> systemPrompts = new ArrayList<>();
        when(llmClientService.streamInference(any(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    systemPrompts.add(invocation.getArgument(2));
                    Consumer<String> tokenConsumer = invocation.getArgument(4);
                    tokenConsumer.accept("ok");
                    return true;
                });

        for (Locale locale : List.of(Locale.ENGLISH, Locale.of("es"), PT_BR)) {
            ragService.streamRagResponse(session.getId(), user.getId(), "Aposentadoria isenta moléstia locale",
                    List.of(subject.getId()), locale, new TestSseEmitter(), () -> false);
        }

        assertThat(systemPrompts).hasSize(3);
        assertThat(systemPrompts.get(0)).contains("ZERO HALLUCINATION").contains("strictly as data");
        assertThat(systemPrompts.get(1)).contains("CERO ALUCINACIÓN").contains("estrictamente como datos");
        assertThat(systemPrompts.get(2)).contains("ZERO ALUCINAÇÃO").contains("estritamente como dados");
    }

    @Test
    @DisplayName("Client disconnect aborts the LLM stream and the partial answer is not persisted")
    void testCancelledStreamDiscardsPartialAnswer() {
        ExegeseUser user = userRepository.save(new ExegeseUser("user.cancel@exegese.ai", "Contribuinte Cancel", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(user, "Consulta Cancelada"));
        ExegeseSubject subject = createGroundedSubject("cancel");

        AtomicBoolean clientGone = new AtomicBoolean(false);
        AtomicReference<RuntimeException> consumerFailure = new AtomicReference<>();
        when(llmClientService.streamInference(any(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> tokenConsumer = invocation.getArgument(4);
                    tokenConsumer.accept("Parte inicial ");
                    clientGone.set(true);
                    try {
                        tokenConsumer.accept("parte nunca lida");
                    } catch (RuntimeException e) {
                        consumerFailure.set(e);
                        throw e;
                    }
                    return true;
                });

        TestSseEmitter emitter = new TestSseEmitter();
        ragService.streamRagResponse(session.getId(), user.getId(), "Aposentadoria isenta moléstia cancel",
                List.of(subject.getId()), PT_BR, emitter, clientGone::get);

        assertThat(consumerFailure.get()).isInstanceOf(ChatStreamCancelledException.class);
        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertThat(messages).extracting(ChatMessage::getRole).containsExactly("USER");
        assertThat(emitter.sentText()).doesNotContain("parte nunca lida").doesNotContain("[DONE]");
    }

    @Test
    @DisplayName("Pipeline failures send a generic localized error with a reference, never the exception message")
    void testFailureDoesNotLeakExceptionMessage() {
        ExegeseUser user = userRepository.save(new ExegeseUser("user.failure@exegese.ai", "Contribuinte Falha", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(user, "Consulta com Falha"));
        ExegeseSubject subject = createGroundedSubject("failure");

        when(llmClientService.streamInference(any(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("FATAL: password authentication failed for user exegese at 10.0.0.5"));

        TestSseEmitter emitter = new TestSseEmitter();
        ragService.streamRagResponse(session.getId(), user.getId(), "Aposentadoria isenta moléstia failure",
                List.of(subject.getId()), Locale.ENGLISH, emitter, () -> false);

        assertThat(emitter.sentText())
                .contains("Your inquiry could not be processed. Reference code: ")
                .doesNotContain("password")
                .doesNotContain("10.0.0.5");
    }

    @Test
    @DisplayName("RAG streaming refuses a session owned by another user and persists nothing (IDOR)")
    void testStreamingIntoForeignSessionIsRejected() {
        ExegeseUser owner = userRepository.save(new ExegeseUser("owner.rag@exegese.ai", "Owner", UserRole.ROLE_USER));
        ExegeseUser intruder = userRepository.save(new ExegeseUser("intruder.rag@exegese.ai", "Intruder", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(owner, "Sessão Privada"));

        TestSseEmitter emitter = new TestSseEmitter();

        assertThatThrownBy(() -> ragService.streamRagResponse(
                session.getId(),
                intruder.getId(),
                "Pergunta injetada na sessão alheia",
                List.of(),
                PT_BR,
                emitter,
                () -> false
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())).isEmpty();
        assertThat(emitter.sentEvents).isEmpty();
        verify(llmClientService, never()).streamInference(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("QueryRewritingService resolves anaphoric follow-up question")
    void testQueryRewriting() {
        ExegeseUser user = new ExegeseUser("user.rewriting@exegese.ai", "User", UserRole.ROLE_USER);
        ChatSession session = new ChatSession(user, "Session");

        List<ChatMessage> history = List.of(
                new ChatMessage(session, "USER", "Como declarar rendimentos de aluguel recebidos de pessoa física?"),
                new ChatMessage(session, "ASSISTANT", "Os rendimentos de aluguel estão sujeitos ao Carnê-Leão.")
        );

        String rewritten = queryRewritingService.rewriteQuery("E no caso de pessoa jurídica?", history);
        assertThat(rewritten).contains("rendimentos de aluguel");
        assertThat(rewritten).contains("E no caso de pessoa jurídica?");
    }

    @Test
    @DisplayName("Specialist System Prompt enforces practical guidance and zero hallucination")
    void testSpecialistSystemPromptContent() {
        String prompt = ragService.resolveSystemPrompt(PT_BR);
        assertThat(prompt).isNotBlank();
        assertThat(prompt).contains("ZERO ALUCINAÇÃO");
        assertThat(prompt).contains("ORIENTAÇÃO PRÁTICA PASSO A PASSO");
        assertThat(prompt).contains("RESPOSTA DIRETA & CONCLUSIVA");
    }

    @Test
    @DisplayName("Specialist System Prompt is resolved per locale without any global reconfiguration")
    void testSpecialistSystemPromptPerLocale() {
        assertThat(ragService.resolveSystemPrompt(Locale.ENGLISH))
                .contains("ZERO HALLUCINATION")
                .contains("STEP-BY-STEP PRACTICAL GUIDANCE");

        assertThat(ragService.resolveSystemPrompt(Locale.of("es")))
                .contains("CERO ALUCINACIÓN")
                .contains("ORIENTACIÓN PRÁTICA PASO A PASO");

        // Resolving other languages has no side effect on pt-BR, and unknown locales use the default bundle
        assertThat(ragService.resolveSystemPrompt(PT_BR))
                .contains("ZERO ALUCINAÇÃO")
                .contains("ORIENTAÇÃO PRÁTICA PASSO A PASSO");
        assertThat(ragService.resolveSystemPrompt(Locale.JAPANESE)).contains("ZERO ALUCINAÇÃO");
        assertThat(ragService.resolveSystemPrompt(null)).contains("ZERO ALUCINAÇÃO");
    }

    private ExegeseSubject createGroundedSubject(String suffix) {
        ExegeseSubject subject = subjectRepository.save(
                new ExegeseSubject("irpf-" + suffix, "IRPF " + suffix, "Assunto " + suffix));
        ExegeseDocument doc = new ExegeseDocument(
                "Manual " + suffix, suffix + ".pdf", "storage/" + suffix + ".pdf", "hash-doc-" + suffix, 512L, "PDF");
        doc.addSubject(subject);
        doc = documentRepository.save(doc);
        chunkRepository.save(new ExegeseChunk(
                doc,
                "hash-chunk-" + suffix,
                1,
                "Aposentadoria " + suffix,
                "Aposentadoria isenta por moléstia grave " + suffix + ": os proventos são isentos do imposto de renda.",
                "{\"page\": 1}"
        ));
        entityManager.flush();
        return subject;
    }

    private static class TestSseEmitter extends SseEmitter {
        private final List<Object> sentEvents = new ArrayList<>();

        public TestSseEmitter() {
            super(60000L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            sentEvents.add(builder);
        }

        String sentText() {
            StringBuilder sb = new StringBuilder();
            for (Object event : sentEvents) {
                ((SseEventBuilder) event).build().forEach(part -> sb.append(part.getData()));
            }
            return sb.toString();
        }
    }
}
