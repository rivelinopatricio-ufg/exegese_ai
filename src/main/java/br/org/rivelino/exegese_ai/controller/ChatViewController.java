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
package br.org.rivelino.exegese_ai.controller;

import br.org.rivelino.exegese_ai.domain.entity.ChatMessage;
import br.org.rivelino.exegese_ai.domain.entity.ChatSession;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.ChatMessageRepository;
import br.org.rivelino.exegese_ai.repository.ChatSessionRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseUserRepository;
import br.org.rivelino.exegese_ai.security.SecurityContextFacade;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;
import java.util.UUID;

/**
 * Controller rendering the reactive conversational chat interface and session manager.
 *
 * @author Rivelino Patrício
 */
@Controller
public class ChatViewController {

    private final SecurityContextFacade securityContextFacade;
    private final ExegeseUserRepository userRepository;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ExegeseSubjectRepository subjectRepository;

    public ChatViewController(SecurityContextFacade securityContextFacade,
                              ExegeseUserRepository userRepository,
                              ChatSessionRepository sessionRepository,
                              ChatMessageRepository messageRepository,
                              ExegeseSubjectRepository subjectRepository) {
        this.securityContextFacade = securityContextFacade;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.subjectRepository = subjectRepository;
    }

    @GetMapping("/")
    public String index(Model model) {
        ExegeseUser user = resolveCurrentUser();
        List<ChatSession> sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(user.getId());

        ChatSession activeSession;
        if (sessions.isEmpty()) {
            activeSession = sessionRepository.save(new ChatSession(user, "Nova Consulta"));
            sessions = List.of(activeSession);
        } else {
            activeSession = sessions.get(0);
        }

        return populateChatModel(model, user, sessions, activeSession);
    }

    @GetMapping("/chat/{sessionId}")
    public String viewSession(@PathVariable UUID sessionId, Model model) {
        ExegeseUser user = resolveCurrentUser();
        List<ChatSession> sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(user.getId());

        ChatSession activeSession = sessionRepository.findById(sessionId)
                .orElseGet(() -> {
                    if (!sessions.isEmpty()) return sessions.get(0);
                    return sessionRepository.save(new ChatSession(user, "Nova Consulta"));
                });

        return populateChatModel(model, user, sessions, activeSession);
    }

    @PostMapping("/chat/new")
    public String createNewSession() {
        ExegeseUser user = resolveCurrentUser();
        ChatSession newSession = sessionRepository.save(new ChatSession(user, "Nova Consulta"));
        return "redirect:/chat/" + newSession.getId();
    }

    private String populateChatModel(Model model, ExegeseUser user, List<ChatSession> sessions, ChatSession activeSession) {
        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(activeSession.getId());
        List<ExegeseSubject> subjects = subjectRepository.findByActiveTrue();
        if (subjects.isEmpty()) {
            subjects = subjectRepository.findAll();
        }

        model.addAttribute("currentUser", user);
        model.addAttribute("sessions", sessions);
        model.addAttribute("activeSession", activeSession);
        model.addAttribute("messages", messages);
        model.addAttribute("subjects", subjects);
        model.addAttribute("isAdmin", securityContextFacade.isAdmin());
        model.addAttribute("canAccessAdmin", securityContextFacade.isOperatorOrAdmin());

        return "index";
    }

    private ExegeseUser resolveCurrentUser() {
        return securityContextFacade.getCurrentUser().orElseGet(() -> {
            String email = securityContextFacade.getCurrentUserEmail().orElse("default.user@exegese.ai");
            return userRepository.findByEmail(email).orElseGet(() ->
                    userRepository.save(new ExegeseUser(email, "Usuário Exegese", UserRole.ROLE_USER))
            );
        });
    }
}
