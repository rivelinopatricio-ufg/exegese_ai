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

import br.org.rivelino.exegese_ai.domain.dto.ChatStreamTicket;
import br.org.rivelino.exegese_ai.domain.entity.ChatMessage;
import br.org.rivelino.exegese_ai.domain.entity.ChatSession;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.ChatMessageRepository;
import br.org.rivelino.exegese_ai.repository.ChatSessionRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseUserRepository;
import br.org.rivelino.exegese_ai.service.ChatConcurrencyLimiter;
import br.org.rivelino.exegese_ai.service.ChatStreamTicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Integration tests for the conversational chat interface and SSE streaming endpoint.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ChatInterfaceIntegrationTest {

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
    private ChatStreamTicketService ticketService;

    @Autowired
    private ChatConcurrencyLimiter concurrencyLimiter;

    @Test
    @WithMockUser(username = "chat.user@exegese.ai", roles = "USER")
    @DisplayName("Authenticated user accesses index view with chat sessions and subject options")
    void testIndexEndpointReturnsOkWithChatModel() throws Exception {
        userRepository.save(new ExegeseUser("chat.user@exegese.ai", "Usuário Chat", UserRole.ROLE_USER));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(model().attributeExists("currentUser"))
                .andExpect(model().attributeExists("activeSession"))
                .andExpect(model().attributeExists("sessions"))
                .andExpect(model().attributeExists("subjects"))
                .andExpect(model().attributeExists("messages"))
                // CSRF token exposed for the fetch() POST of the two-step chat flow
                .andExpect(content().string(containsString("<meta name=\"_csrf_header\" content=\"X-CSRF-TOKEN\">")))
                .andExpect(content().string(containsString("<meta name=\"_csrf\" content=\"")));
    }

    @Test
    @WithMockUser(username = "new.chat@exegese.ai", roles = "USER")
    @DisplayName("User triggers creation of a new chat session")
    void testCreateNewChatSession() throws Exception {
        userRepository.save(new ExegeseUser("new.chat@exegese.ai", "Novo Chat", UserRole.ROLE_USER));

        mockMvc.perform(post("/chat/new").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/chat/*"));
    }

    @Test
    @WithMockUser(username = "stream.user@exegese.ai", roles = "USER")
    @DisplayName("Submitting a question answers a single-use stream URL (two-step, CSRF-protected flow)")
    void testSubmitQuestionReturnsStreamUrl() throws Exception {
        ExegeseUser user = userRepository.save(new ExegeseUser("stream.user@exegese.ai", "Stream User", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(user, "Sessão Stream"));

        try {
            mockMvc.perform(post("/api/chat/messages")
                            .with(csrf())
                            .param("sessionId", session.getId().toString())
                            .param("question", "Como funciona a isenção de aposentadoria?"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.streamUrl", startsWith("/api/chat/stream/")))
                    .andExpect(request().asyncNotStarted());

            // Nothing is persisted before the stream is opened
            assertThat(messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())).isEmpty();
        } finally {
            concurrencyLimiter.release(user.getId());
        }
    }

    @Test
    @WithMockUser(username = "blank.user@exegese.ai", roles = "USER")
    @DisplayName("A blank question is refused with HTTP 400 and a localized message")
    void testBlankQuestionRejected() throws Exception {
        ExegeseUser user = userRepository.save(new ExegeseUser("blank.user@exegese.ai", "Blank User", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(user, "Sessão Vazia"));

        mockMvc.perform(post("/api/chat/messages")
                        .with(csrf())
                        .param("sessionId", session.getId().toString())
                        .param("question", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Digite uma pergunta")));
    }

    @Test
    @WithMockUser(username = "legacy.user@exegese.ai", roles = "USER")
    @DisplayName("The legacy state-changing GET /api/chat/stream?question= endpoint no longer exists")
    void testLegacyGetStreamEndpointRemoved() throws Exception {
        ExegeseUser user = userRepository.save(new ExegeseUser("legacy.user@exegese.ai", "Legacy User", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(user, "Sessão Legada"));

        mockMvc.perform(get("/api/chat/stream")
                        .param("sessionId", session.getId().toString())
                        .param("question", "Pergunta via GET"))
                .andExpect(status().isNotFound())
                .andExpect(request().asyncNotStarted());

        assertThat(messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())).isEmpty();
    }

    @Test
    @DisplayName("Unauthenticated request to root path is redirected to /login")
    void testUnauthenticatedRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    @WithMockUser(username = "rename.user@exegese.ai", roles = "USER")
    @DisplayName("User renames their own chat session successfully")
    void testRenameChatSession() throws Exception {
        ExegeseUser user = userRepository.save(new ExegeseUser("rename.user@exegese.ai", "Rename User", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(user, "Consulta Original"));

        mockMvc.perform(post("/chat/" + session.getId() + "/rename")
                        .with(csrf())
                        .param("title", "IRPF 2026 - Dedução Carnê-Leão"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/chat/" + session.getId()));

        ChatSession updated = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("IRPF 2026 - Dedução Carnê-Leão");
    }

    @Test
    @WithMockUser(username = "delete.user@exegese.ai", roles = "USER")
    @DisplayName("User deletes their chat session and associated messages successfully")
    void testDeleteChatSession() throws Exception {
        ExegeseUser user = userRepository.save(new ExegeseUser("delete.user@exegese.ai", "Delete User", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(user, "Consulta a Deletar"));
        messageRepository.save(new ChatMessage(session, "USER", "Mensagem de teste"));

        mockMvc.perform(post("/chat/" + session.getId() + "/delete")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        assertThat(sessionRepository.findById(session.getId())).isEmpty();
        assertThat(messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())).isEmpty();
    }

    @Test
    @WithMockUser(username = "attacker.rename@exegese.ai", roles = "USER")
    @DisplayName("User cannot rename another user's chat session")
    void testCannotRenameOtherUserChatSession() throws Exception {
        ExegeseUser victim = userRepository.save(new ExegeseUser("victim.rename@exegese.ai", "Victim", UserRole.ROLE_USER));
        userRepository.save(new ExegeseUser("attacker.rename@exegese.ai", "Attacker", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(victim, "Título Legítimo"));

        // A foreign session behaves exactly like a missing one (no existence leak)
        mockMvc.perform(post("/chat/" + session.getId() + "/rename")
                        .with(csrf())
                        .param("title", "Título Hackeado"))
                .andExpect(status().isNotFound());

        ChatSession unchanged = sessionRepository.findById(session.getId()).orElseThrow();
        assertThat(unchanged.getTitle()).isEqualTo("Título Legítimo");
    }

    @Test
    @WithMockUser(username = "attacker.delete@exegese.ai", roles = "USER")
    @DisplayName("User cannot delete another user's chat session")
    void testCannotDeleteOtherUserChatSession() throws Exception {
        ExegeseUser victim = userRepository.save(new ExegeseUser("victim.del@exegese.ai", "Victim Del", UserRole.ROLE_USER));
        userRepository.save(new ExegeseUser("attacker.delete@exegese.ai", "Attacker Del", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(victim, "Consulta Protegida"));

        mockMvc.perform(post("/chat/" + session.getId() + "/delete")
                        .with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(sessionRepository.findById(session.getId())).isPresent();
    }

    @Test
    @WithMockUser(username = "attacker.view@exegese.ai", roles = "USER")
    @DisplayName("User cannot view another user's chat session (IDOR): HTTP 404, messages not rendered")
    void testCannotViewOtherUserChatSession() throws Exception {
        ExegeseUser victim = userRepository.save(new ExegeseUser("victim.view@exegese.ai", "Victim View", UserRole.ROLE_USER));
        userRepository.save(new ExegeseUser("attacker.view@exegese.ai", "Attacker View", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(victim, "Consulta Sigilosa"));
        messageRepository.save(new ChatMessage(session, "USER", "Minha renda sigilosa de 123.456,78"));

        mockMvc.perform(get("/chat/" + session.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "missing.view@exegese.ai", roles = "USER")
    @DisplayName("Viewing a non-existent chat session answers HTTP 404 (same as a foreign one)")
    void testViewMissingChatSessionReturns404() throws Exception {
        userRepository.save(new ExegeseUser("missing.view@exegese.ai", "Missing View", UserRole.ROLE_USER));

        mockMvc.perform(get("/chat/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "attacker.stream@exegese.ai", roles = "USER")
    @DisplayName("User cannot submit into another user's chat session (IDOR): HTTP 404 before any async work")
    void testCannotStreamIntoOtherUserChatSession() throws Exception {
        ExegeseUser victim = userRepository.save(new ExegeseUser("victim.stream@exegese.ai", "Victim Stream", UserRole.ROLE_USER));
        userRepository.save(new ExegeseUser("attacker.stream@exegese.ai", "Attacker Stream", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(victim, "Consulta Alheia"));

        mockMvc.perform(post("/api/chat/messages")
                        .with(csrf())
                        .param("sessionId", session.getId().toString())
                        .param("question", "Pergunta injetada"))
                .andExpect(status().isNotFound())
                .andExpect(request().asyncNotStarted());

        assertThat(messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId())).isEmpty();
    }

    @Test
    @WithMockUser(username = "thief.stream@exegese.ai", roles = "USER")
    @DisplayName("A stream ticket of another user answers HTTP 404 and stays usable by its owner")
    void testCannotRedeemOtherUserStreamTicket() throws Exception {
        ExegeseUser owner = userRepository.save(new ExegeseUser("owner.ticket@exegese.ai", "Owner Ticket", UserRole.ROLE_USER));
        userRepository.save(new ExegeseUser("thief.stream@exegese.ai", "Thief", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(owner, "Consulta do Dono"));

        String streamId = ticketService.issue(new ChatStreamTicket(owner.getId(), session.getId(), "Pergunta do dono",
                List.of(), Locale.of("pt", "BR"))).orElseThrow();
        try {
            mockMvc.perform(get("/api/chat/stream/" + streamId))
                    .andExpect(status().isNotFound())
                    .andExpect(request().asyncNotStarted());

            assertThat(ticketService.redeem(streamId, owner.getId())).isPresent();
        } finally {
            concurrencyLimiter.release(owner.getId());
        }
    }

    @Test
    @WithMockUser(username = "ghost.user@exegese.ai", roles = "USER")
    @DisplayName("Principal without a local account is treated as unauthenticated (no fallback account is created)")
    void testPrincipalWithoutLocalAccountIsNotProvisioned() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(post("/api/chat/messages")
                        .with(csrf())
                        .param("sessionId", UUID.randomUUID().toString())
                        .param("question", "Pergunta"))
                .andExpect(status().isUnauthorized());

        assertThat(userRepository.findByEmail("ghost.user@exegese.ai")).isEmpty();
        assertThat(userRepository.findByEmail("default.user@exegese.ai")).isEmpty();
    }

    @Test
    @WithMockUser(username = "subjects.user@exegese.ai", roles = "USER")
    @DisplayName("Chat page offers only active subjects (inactive subjects stay hidden)")
    void testChatPageListsOnlyActiveSubjects() throws Exception {
        userRepository.save(new ExegeseUser("subjects.user@exegese.ai", "Subjects User", UserRole.ROLE_USER));
        ExegeseSubject active = subjectRepository.save(new ExegeseSubject("chat-ativo", "Assunto Ativo Chat", "Ativo"));
        ExegeseSubject inactive = new ExegeseSubject("chat-inativo", "Assunto Inativo Chat", "Inativo");
        inactive.setActive(false);
        subjectRepository.save(inactive);

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("subjects", hasItem(hasProperty("code", equalTo(active.getCode())))))
                .andExpect(model().attribute("subjects", not(hasItem(hasProperty("code", equalTo("chat-inativo"))))))
                .andExpect(content().string(not(containsString("Assunto Inativo Chat"))));
    }

    @Test
    @WithMockUser(username = "citations.user@exegese.ai", roles = "USER")
    @DisplayName("Historical messages with citations render official sources chips and data attributes")
    void testHistoricalCitationsRendered() throws Exception {
        ExegeseUser user = userRepository.save(new ExegeseUser("citations.user@exegese.ai", "Citations User", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(user, "Consulta com Citações"));

        ChatMessage assistantMsg = new ChatMessage(session, "ASSISTANT", "Resposta ancorada com fundamentação.");
        assistantMsg.setCitations("[{\"documentTitle\":\"Manual IRPF 2026\",\"chunkTitle\":\"Pergunta 101 - Despesas\",\"pageNumber\":55,\"questionNumber\":101,\"articleNumber\":null,\"legalBasis\":\"Lei 7.713/1988\"}]");
        messageRepository.save(assistantMsg);

        mockMvc.perform(get("/chat/" + session.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("citation-chip")))
                .andExpect(content().string(containsString("Pergunta 101 - Despesas")))
                .andExpect(content().string(containsString("data-page=\"55\"")));
    }
}
