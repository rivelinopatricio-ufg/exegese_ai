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

import br.org.rivelino.exegese_ai.domain.entity.ChatSession;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.ChatSessionRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseUserRepository;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
                .andExpect(model().attributeExists("messages"));
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
    @DisplayName("SSE streaming endpoint responds with text/event-stream")
    void testSseChatStreamEndpoint() throws Exception {
        ExegeseUser user = userRepository.save(new ExegeseUser("stream.user@exegese.ai", "Stream User", UserRole.ROLE_USER));
        ChatSession session = sessionRepository.save(new ChatSession(user, "Sessão Stream"));

        mockMvc.perform(get("/api/chat/stream")
                        .param("sessionId", session.getId().toString())
                        .param("question", "Como funciona a isenção de aposentadoria?"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    @DisplayName("Unauthenticated request to root path is redirected to /login")
    void testUnauthenticatedRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}
