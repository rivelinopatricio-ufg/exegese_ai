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
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.*;
import br.org.rivelino.exegese_ai.service.AntiHallucinationGuard;
import br.org.rivelino.exegese_ai.service.QueryRewritingService;
import br.org.rivelino.exegese_ai.service.RagOrchestrationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for RAG pipeline orchestration, zero hallucination guard, and SSE streaming.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RagOrchestrationIntegrationTest {

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

    @BeforeEach
    void setUpLocale() {
        ragService.configureSystemPromptForLocale(Locale.of("pt", "BR"));
    }

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
                "Como preparar uma receita culinária no imposto de renda?",
                List.of(emptySubject.getId()),
                emitter
        );

        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getRole()).isEqualTo("USER");
        assertThat(messages.get(1).getRole()).isEqualTo("ASSISTANT");
        assertThat(messages.get(1).getContent()).isEqualTo(AntiHallucinationGuard.CANONICAL_REFUSAL_MESSAGE);
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
                "Rendimentos de Aposentadoria maiores de 65 anos",
                List.of(subject.getId()),
                emitter
        );

        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getRole()).isEqualTo("ASSISTANT");
        assertThat(messages.get(1).getContent()).contains("Previdência Social");
        assertThat(messages.get(1).getContent()).contains("1.903,98");
        assertThat(messages.get(1).getCitations()).contains("Pergunta 020");
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
        String prompt = RagOrchestrationService.SPECIALIST_SYSTEM_PROMPT;
        assertThat(prompt).isNotBlank();
        assertThat(prompt).contains("ZERO ALUCINAÇÃO");
        assertThat(prompt).contains("ORIENTAÇÃO PRÁTICA PASSO A PASSO");
        assertThat(prompt).contains("RESPOSTA DIRETA & CONCLUSIVA");
    }

    @Test
    @DisplayName("Specialist System Prompt reconfigures dynamically according to selected locale")
    void testSpecialistSystemPromptLocaleReconfiguration() {
        // Switch to English
        ragService.configureSystemPromptForLocale(Locale.ENGLISH);
        assertThat(RagOrchestrationService.SPECIALIST_SYSTEM_PROMPT)
                .contains("ZERO HALLUCINATION")
                .contains("STEP-BY-STEP PRACTICAL GUIDANCE");

        // Switch to Spanish
        ragService.configureSystemPromptForLocale(Locale.of("es"));
        assertThat(RagOrchestrationService.SPECIALIST_SYSTEM_PROMPT)
                .contains("CERO ALUCINACIÓN")
                .contains("ORIENTACIÓN PRÁTICA PASO A PASO");

        // Reset to Portuguese
        ragService.configureSystemPromptForLocale(Locale.of("pt", "BR"));
        assertThat(RagOrchestrationService.SPECIALIST_SYSTEM_PROMPT)
                .contains("ZERO ALUCINAÇÃO")
                .contains("ORIENTAÇÃO PRÁTICA PASSO A PASSO");
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
    }
}
