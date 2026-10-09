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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariDataSource;

import br.org.rivelino.exegese_ai.controller.ChatApiController;
import br.org.rivelino.exegese_ai.domain.entity.ChatMessage;
import br.org.rivelino.exegese_ai.domain.entity.ChatSession;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseChunk;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseDocument;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.ChatMessageRepository;
import br.org.rivelino.exegese_ai.repository.ChatSessionRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseChunkRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseDocumentRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseUserRepository;
import br.org.rivelino.exegese_ai.security.RateLimitFilter;
import br.org.rivelino.exegese_ai.service.ChatConcurrencyLimiter;
import br.org.rivelino.exegese_ai.service.ChatStreamCancelledException;
import br.org.rivelino.exegese_ai.service.LlmClientService;
import jakarta.servlet.http.Cookie;

/**
 * End-to-end tests of the two-step chat flow ({@code POST /api/chat/messages} then
 * {@code GET /api/chat/stream/{streamId}}) running on the dedicated virtual-thread executor.
 * <p>
 * Deliberately NOT {@code @Transactional}: the stream runs on another thread and must see committed data,
 * and the tests verify that no transaction (nor pooled connection) is held while the LLM generates. Every
 * row created here is removed after each test. The LLM is the hermetic mock of {@code TestLlmClientConfiguration}.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChatStreamingIntegrationTest {

    private static final long WAIT_SECONDS = 15;

    @Autowired
    private MockMvc mockMvc;

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
    private LlmClientService llmClientService;

    @Autowired
    private ChatConcurrencyLimiter concurrencyLimiter;

    @Autowired
    private RateLimitFilter rateLimitFilter;

    @Autowired
    private DataSource dataSource;

    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> sessionIds = new ArrayList<>();
    private final List<UUID> chunkIds = new ArrayList<>();
    private final List<UUID> documentIds = new ArrayList<>();
    private final List<UUID> subjectIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        rateLimitFilter.reset();
    }

    @AfterEach
    void cleanUp() {
        for (UUID sessionId : sessionIds) {
            messageRepository.deleteAll(messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
            sessionRepository.deleteById(sessionId);
        }
        chunkIds.forEach(chunkRepository::deleteById);
        documentIds.forEach(documentRepository::deleteById);
        subjectIds.forEach(subjectRepository::deleteById);
        userIds.forEach(userRepository::deleteById);
    }

    @Test
    @DisplayName("POST then GET streams the answer once, with no transaction nor DB connection held during the LLM call")
    void testTwoStepFlowStreamsWithoutHoldingConnections() throws Exception {
        ExegeseUser owner = createUser("fluxo.stream@exegese.test");
        ChatSession session = createSession(owner);
        ExegeseSubject subject = createGroundedSubject("fluxo");

        AtomicReference<Boolean> transactionActive = new AtomicReference<>();
        AtomicInteger activeConnections = new AtomicInteger(-1);
        when(llmClientService.streamInference(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
                    if (dataSource instanceof HikariDataSource hikari && hikari.getHikariPoolMXBean() != null) {
                        activeConnections.set(hikari.getHikariPoolMXBean().getActiveConnections());
                    } else {
                        activeConnections.set(0);
                    }
                    Consumer<String> tokenConsumer = invocation.getArgument(4);
                    tokenConsumer.accept("Resposta ");
                    tokenConsumer.accept("final.");
                    return true;
                });

        String streamUrl = submit(owner, session, "Aposentadoria isenta moléstia fluxo", subject.getId(), null);

        MvcResult result = mockMvc.perform(get(streamUrl).with(as(owner)))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();
        result.getAsyncResult(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        awaitStreamsFinished(owner);

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("event:citation").contains("Resposta ").contains("final.").contains("event:complete");

        assertThat(transactionActive.get()).as("transaction active while waiting on the LLM").isFalse();
        assertThat(activeConnections.get()).as("pooled connections in use while waiting on the LLM").isZero();

        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        assertThat(messages).extracting(ChatMessage::getRole).containsExactly("USER", "ASSISTANT");
        assertThat(messages.get(0).getContent()).isEqualTo("Aposentadoria isenta moléstia fluxo");
        assertThat(messages.get(1).getContent()).isEqualTo("Resposta final.");

        // The ticket is single-use
        mockMvc.perform(get(streamUrl).with(as(owner)))
                .andExpect(status().isNotFound())
                .andExpect(request().asyncNotStarted());
    }

    @Test
    @DisplayName("Each user gets the system prompt of their own locale; an anonymous ?lang= changes nothing global")
    void testSystemPromptFollowsEachUsersLocale() throws Exception {
        ExegeseUser english = createUser("english.locale@exegese.test");
        ExegeseUser spanish = createUser("spanish.locale@exegese.test");
        ExegeseUser portuguese = createUser("portuguese.locale@exegese.test");
        ExegeseSubject subject = createGroundedSubject("idioma");

        List<String> systemPrompts = new CopyOnWriteArrayList<>();
        when(llmClientService.streamInference(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    systemPrompts.add(invocation.getArgument(2));
                    Consumer<String> tokenConsumer = invocation.getArgument(4);
                    tokenConsumer.accept("ok");
                    return true;
                });

        streamToCompletion(english, createSession(english), subject, "en");
        streamToCompletion(spanish, createSession(spanish), subject, "es");

        // Anonymous visitor switches the login page to English: only their own cookie may change
        mockMvc.perform(get("/login").param("lang", "en")).andExpect(status().isOk());
        streamToCompletion(portuguese, createSession(portuguese), subject, null);

        assertThat(systemPrompts).hasSize(3);
        assertThat(systemPrompts.get(0)).contains("ZERO HALLUCINATION");
        assertThat(systemPrompts.get(1)).contains("CERO ALUCINACIÓN");
        assertThat(systemPrompts.get(2)).contains("ZERO ALUCINAÇÃO");
    }

    @Test
    @DisplayName("A user cannot have more than the allowed concurrent answers in flight (HTTP 429, localized)")
    void testPerUserConcurrencyLimit() throws Exception {
        ExegeseUser user = createUser("concorrente.stream@exegese.test");
        ChatSession session = createSession(user);
        int max = concurrencyLimiter.getMaxConcurrentStreams();

        for (int i = 0; i < max; i++) {
            submit(user, session, "Pergunta pendente " + i, null, null);
        }

        mockMvc.perform(post("/api/chat/messages")
                        .with(as(user))
                        .with(csrf())
                        .param("sessionId", session.getId().toString())
                        .param("question", "Pergunta excedente"))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.error", containsString("consultas em andamento")));

        // Another user is not affected
        ExegeseUser other = createUser("outro.concorrente@exegese.test");
        submit(other, createSession(other), "Pergunta de outro usuário", null, null);
    }

    @Test
    @DisplayName("Client disconnect cancels the upstream LLM stream, releases the permit and discards the partial answer")
    void testClientDisconnectCancelsLlmStream() throws Exception {
        ExegeseUser user = createUser("desconecta.stream@exegese.test");
        ChatSession session = createSession(user);
        ExegeseSubject subject = createGroundedSubject("desconexao");

        CountDownLatch firstTokenSent = new CountDownLatch(1);
        CountDownLatch clientGone = new CountDownLatch(1);
        AtomicReference<Throwable> consumerFailure = new AtomicReference<>();
        when(llmClientService.streamInference(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Consumer<String> tokenConsumer = invocation.getArgument(4);
                    tokenConsumer.accept("Parte inicial ");
                    firstTokenSent.countDown();
                    clientGone.await(WAIT_SECONDS, TimeUnit.SECONDS);
                    try {
                        tokenConsumer.accept("parte que ninguém lerá");
                    } catch (RuntimeException e) {
                        consumerFailure.set(e);
                        throw e;
                    }
                    return true;
                });

        String streamUrl = submit(user, session, "Aposentadoria isenta moléstia desconexao", subject.getId(), null);
        MvcResult result = mockMvc.perform(get(streamUrl).with(as(user)))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(firstTokenSent.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        // The servlet container completes the async request when the browser tab is closed
        result.getRequest().getAsyncContext().complete();
        clientGone.countDown();
        awaitStreamsFinished(user);

        assertThat(consumerFailure.get()).isInstanceOf(ChatStreamCancelledException.class);
        assertThat(messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()))
                .extracting(ChatMessage::getRole)
                .containsExactly("USER");
    }

    @Test
    @DisplayName("A question filtered by more than 50 subjects (every box checked on a large catalog) is accepted; only abusive lists are refused")
    void testLargeSubjectSelectionAccepted() throws Exception {
        ExegeseUser user = createUser("muitos.assuntos@exegese.test");
        ChatSession session = createSession(user);
        ExegeseSubject subject = createGroundedSubject("muitos");

        var manySubjects = post("/api/chat/messages")
                .with(as(user))
                .with(csrf())
                .param("sessionId", session.getId().toString())
                .param("question", "Aposentadoria isenta moléstia muitos")
                .param("subjectIds", subject.getId().toString());
        for (int i = 0; i < 60; i++) {
            manySubjects.param("subjectIds", UUID.randomUUID().toString());
        }
        String json = mockMvc.perform(manySubjects)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        MvcResult result = mockMvc.perform(get(JsonPath.<String>read(json, "$.streamUrl")).with(as(user)))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        awaitStreamsFinished(user);

        var abusive = post("/api/chat/messages")
                .with(as(user))
                .with(csrf())
                .param("sessionId", session.getId().toString())
                .param("question", "Aposentadoria isenta moléstia muitos");
        for (int i = 0; i <= ChatApiController.MAX_SUBJECT_FILTERS; i++) {
            abusive.param("subjectIds", UUID.randomUUID().toString());
        }
        mockMvc.perform(abusive).andExpect(status().isBadRequest());
        assertThat(concurrencyLimiter.inFlight(user.getId())).isZero();
    }

    private void streamToCompletion(ExegeseUser user, ChatSession session, ExegeseSubject subject, String lang) throws Exception {
        String streamUrl = submit(user, session, "Aposentadoria isenta moléstia idioma", subject.getId(), lang);
        MvcResult result = mockMvc.perform(get(streamUrl).with(as(user)))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        awaitStreamsFinished(user);
    }

    private String submit(ExegeseUser user, ChatSession session, String question, UUID subjectId, String lang) throws Exception {
        var request = post("/api/chat/messages")
                .with(as(user))
                .with(csrf())
                .param("sessionId", session.getId().toString())
                .param("question", question);
        if (subjectId != null) {
            request.param("subjectIds", subjectId.toString());
        }
        if (lang != null) {
            request.cookie(new Cookie("EXEGESE_LOCALE", lang));
        }
        String json = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.streamUrl");
    }

    private void awaitStreamsFinished(ExegeseUser user) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (concurrencyLimiter.inFlight(user.getId()) > 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(concurrencyLimiter.inFlight(user.getId())).as("stream permits still held").isZero();
    }

    private static RequestPostProcessor as(ExegeseUser user) {
        return user(user.getEmail()).roles("USER");
    }

    private ExegeseUser createUser(String email) {
        ExegeseUser user = userRepository.save(new ExegeseUser(email, email, UserRole.ROLE_USER));
        userIds.add(user.getId());
        return user;
    }

    private ChatSession createSession(ExegeseUser owner) {
        ChatSession session = sessionRepository.save(new ChatSession(owner, "Sessão " + owner.getEmail()));
        sessionIds.add(session.getId());
        return session;
    }

    private ExegeseSubject createGroundedSubject(String suffix) {
        ExegeseSubject subject = subjectRepository.save(
                new ExegeseSubject("stream-" + suffix, "Stream " + suffix, "Assunto " + suffix));
        subjectIds.add(subject.getId());

        ExegeseDocument doc = new ExegeseDocument(
                "Manual Stream " + suffix, suffix + ".pdf", "storage/" + suffix + ".pdf", "hash-stream-doc-" + suffix, 512L, "PDF");
        doc.addSubject(subject);
        doc = documentRepository.save(doc);
        documentIds.add(doc.getId());

        ExegeseChunk chunk = chunkRepository.save(new ExegeseChunk(
                doc,
                "hash-stream-chunk-" + suffix,
                1,
                "Aposentadoria " + suffix,
                "Aposentadoria isenta por moléstia grave " + suffix + ": os proventos são isentos do imposto de renda.",
                "{\"page\": 1}"
        ));
        chunkIds.add(chunk.getId());
        return subject;
    }
}
