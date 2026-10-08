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
import br.org.rivelino.exegese_ai.repository.ChatMessageRepository;
import br.org.rivelino.exegese_ai.repository.ChatSessionRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.security.SecurityContextFacade;
import br.org.rivelino.exegese_ai.service.ChatSessionAccessService;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Controller rendering the reactive conversational chat interface and session manager.
 * Every session-scoped route goes through {@link ChatSessionAccessService}: a session owned by another
 * user answers HTTP 404, exactly like a missing one.
 *
 * @author Rivelino Patrício
 */
@Controller
public class ChatViewController {

    private final SecurityContextFacade securityContextFacade;
    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ExegeseSubjectRepository subjectRepository;
    private final ChatSessionAccessService sessionAccessService;
    private final MessageSource messageSource;

    public ChatViewController(SecurityContextFacade securityContextFacade,
                              ChatSessionRepository sessionRepository,
                              ChatMessageRepository messageRepository,
                              ExegeseSubjectRepository subjectRepository,
                              ChatSessionAccessService sessionAccessService,
                              MessageSource messageSource) {
        this.securityContextFacade = securityContextFacade;
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.subjectRepository = subjectRepository;
        this.sessionAccessService = sessionAccessService;
        this.messageSource = messageSource;
    }

    @GetMapping("/")
    public String index(Model model) {
        ExegeseUser user = securityContextFacade.requireCurrentUser();
        List<ChatSession> sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(user.getId());

        ChatSession activeSession;
        if (sessions.isEmpty()) {
            activeSession = sessionRepository.save(new ChatSession(user, resolveDefaultSessionTitle()));
            sessions = List.of(activeSession);
        } else {
            activeSession = sessions.get(0);
        }

        return populateChatModel(model, user, sessions, activeSession);
    }

    @GetMapping("/chat/{sessionId}")
    public String viewSession(@PathVariable UUID sessionId, Model model) {
        ExegeseUser user = securityContextFacade.requireCurrentUser();
        ChatSession activeSession = sessionAccessService.requireOwnedSession(sessionId, user.getId());
        List<ChatSession> sessions = sessionRepository.findByUserIdOrderByUpdatedAtDesc(user.getId());

        return populateChatModel(model, user, sessions, activeSession);
    }

    @PostMapping("/chat/new")
    public String createNewSession() {
        ExegeseUser user = securityContextFacade.requireCurrentUser();
        ChatSession newSession = sessionRepository.save(new ChatSession(user, resolveDefaultSessionTitle()));
        return "redirect:/chat/" + newSession.getId();
    }

    @PostMapping("/chat/{sessionId}/rename")
    @Transactional
    public String renameSession(@PathVariable UUID sessionId,
                                @RequestParam("title") String title) {
        ExegeseUser user = securityContextFacade.requireCurrentUser();
        ChatSession session = sessionAccessService.requireOwnedSession(sessionId, user.getId());

        String sanitizedTitle = title != null ? title.trim() : "";
        if (!sanitizedTitle.isBlank()) {
            session.setTitle(sanitizedTitle);
            session.setUpdatedAt(Instant.now());
            sessionRepository.save(session);
        }
        return "redirect:/chat/" + sessionId;
    }

    @PostMapping("/chat/{sessionId}/delete")
    @Transactional
    public String deleteSession(@PathVariable UUID sessionId) {
        ExegeseUser user = securityContextFacade.requireCurrentUser();
        ChatSession session = sessionAccessService.requireOwnedSession(sessionId, user.getId());

        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        messageRepository.deleteAll(messages);
        sessionRepository.delete(session);
        return "redirect:/";
    }

    private String resolveDefaultSessionTitle() {
        return messageSource.getMessage("chat.session.default_title", null, "Nova Consulta", LocaleContextHolder.getLocale());
    }

    private String populateChatModel(Model model, ExegeseUser user, List<ChatSession> sessions, ChatSession activeSession) {
        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(activeSession.getId());
        // Subjects are public, but only active ones are offered (inactive subjects stay hidden)
        List<ExegeseSubject> subjects = subjectRepository.findByActiveTrue();

        model.addAttribute("currentUser", user);
        model.addAttribute("sessions", sessions);
        model.addAttribute("activeSession", activeSession);
        model.addAttribute("messages", messages);
        model.addAttribute("subjects", subjects);
        model.addAttribute("isAdmin", securityContextFacade.isAdmin());
        model.addAttribute("canAccessAdmin", securityContextFacade.isOperatorOrAdmin());

        return "index";
    }
}
