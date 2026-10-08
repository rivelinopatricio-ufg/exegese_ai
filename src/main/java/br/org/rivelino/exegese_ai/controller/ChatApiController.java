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

import br.org.rivelino.exegese_ai.domain.dto.ChatStreamTicket;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseUser;
import br.org.rivelino.exegese_ai.security.InputSanitizationFilter;
import br.org.rivelino.exegese_ai.security.SecurityContextFacade;
import br.org.rivelino.exegese_ai.service.ChatConcurrencyLimiter;
import br.org.rivelino.exegese_ai.service.ChatSessionAccessService;
import br.org.rivelino.exegese_ai.service.ChatStreamService;
import br.org.rivelino.exegese_ai.service.ChatStreamTicketService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller of the conversational RAG chat, in two steps:
 * <ol>
 *   <li>{@code POST /api/chat/messages} (CSRF protected) validates the session ownership and the input and
 *       answers {@code {"streamUrl": ...}} with a single-use stream ticket bound to the current user;</li>
 *   <li>{@code GET /api/chat/stream/{streamId}} redeems the ticket and streams the answer via Server-Sent
 *       Events (SSE). It changes no state that the ticket did not already authorize.</li>
 * </ol>
 * The current user, the session ownership and the request locale are resolved in the request thread,
 * before any asynchronous work starts: a session owned by another user answers HTTP 404.
 *
 * @author Rivelino Patrício
 */
@RestController
@RequestMapping("/api/chat")
public class ChatApiController {

    /**
     * Maximum number of subject filters accepted with a single question. It only guards against abusive
     * requests: the chat page checks every active subject by default, so it is far above any realistic
     * catalog (and selecting every active subject is treated as "no filter" by the search).
     */
    public static final int MAX_SUBJECT_FILTERS = 1000;

    private static final String STREAM_PATH = "/api/chat/stream/";

    private final SecurityContextFacade securityContextFacade;
    private final ChatSessionAccessService sessionAccessService;
    private final ChatStreamTicketService ticketService;
    private final ChatStreamService chatStreamService;
    private final ChatConcurrencyLimiter concurrencyLimiter;
    private final MessageSource messageSource;

    public ChatApiController(SecurityContextFacade securityContextFacade,
                             ChatSessionAccessService sessionAccessService,
                             ChatStreamTicketService ticketService,
                             ChatStreamService chatStreamService,
                             ChatConcurrencyLimiter concurrencyLimiter,
                             MessageSource messageSource) {
        this.securityContextFacade = securityContextFacade;
        this.sessionAccessService = sessionAccessService;
        this.ticketService = ticketService;
        this.chatStreamService = chatStreamService;
        this.concurrencyLimiter = concurrencyLimiter;
        this.messageSource = messageSource;
    }

    @PostMapping(value = "/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> submitQuestion(@RequestParam UUID sessionId,
                                                              @RequestParam String question,
                                                              @RequestParam(required = false) List<UUID> subjectIds,
                                                              Locale locale,
                                                              HttpServletRequest request) {
        ExegeseUser user = securityContextFacade.requireCurrentUser();
        UUID userId = user.getId();
        sessionAccessService.requireOwnedSession(sessionId, userId);

        String trimmedQuestion = question.trim();
        if (trimmedQuestion.isEmpty()) {
            return error(HttpStatus.BAD_REQUEST, "chat.error.question_blank", null, locale);
        }
        if (trimmedQuestion.length() > InputSanitizationFilter.MAX_QUESTION_LENGTH) {
            return error(HttpStatus.BAD_REQUEST, "security.input.too_long",
                    new Object[]{String.valueOf(InputSanitizationFilter.MAX_QUESTION_LENGTH)}, locale);
        }
        List<UUID> filters = subjectIds == null ? List.of() : subjectIds.stream().distinct().toList();
        if (filters.size() > MAX_SUBJECT_FILTERS) {
            return error(HttpStatus.BAD_REQUEST, "chat.error.too_many_subjects",
                    new Object[]{String.valueOf(MAX_SUBJECT_FILTERS)}, locale);
        }

        ChatStreamTicket ticket = new ChatStreamTicket(userId, sessionId, trimmedQuestion, filters, locale);
        return ticketService.issue(ticket)
                .map(streamId -> ResponseEntity.ok(Map.of("streamUrl", request.getContextPath() + STREAM_PATH + streamId)))
                .orElseGet(() -> error(HttpStatus.TOO_MANY_REQUESTS, "chat.error.too_many_streams",
                        new Object[]{String.valueOf(concurrencyLimiter.getMaxConcurrentStreams())}, locale));
    }

    @GetMapping(value = "/stream/{streamId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String streamId, HttpServletResponse response) {
        ExegeseUser user = securityContextFacade.requireCurrentUser();
        ChatStreamTicket ticket = ticketService.redeem(streamId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // Ask reverse proxies (NGINX) not to buffer the event stream
        response.setHeader("X-Accel-Buffering", "no");
        return chatStreamService.start(ticket);
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String key, Object[] args, Locale locale) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", messageSource.getMessage(key, args, locale)));
    }
}
