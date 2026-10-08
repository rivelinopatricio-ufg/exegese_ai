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

import br.org.rivelino.exegese_ai.domain.entity.ChatSession;
import br.org.rivelino.exegese_ai.repository.ChatSessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Central ownership check for chat sessions. A session is only reachable by the user who owns it;
 * a session owned by someone else is reported exactly like a missing one, so its existence is not leaked.
 * Administrators get no override: conversations are private.
 *
 * @author Rivelino Patrício
 */
@Service
public class ChatSessionAccessService {

    private final ChatSessionRepository sessionRepository;

    public ChatSessionAccessService(ChatSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * Looks up a session owned by the given user.
     *
     * @param sessionId Chat session identifier
     * @param userId Identifier of the user that must own the session
     * @return The session, or empty when it does not exist or belongs to another user
     */
    @Transactional(readOnly = true)
    public Optional<ChatSession> findOwnedSession(UUID sessionId, UUID userId) {
        if (sessionId == null || userId == null) {
            return Optional.empty();
        }
        return sessionRepository.findByIdAndUserId(sessionId, userId);
    }

    /**
     * Returns a session owned by the given user or fails with HTTP 404.
     *
     * @param sessionId Chat session identifier
     * @param userId Identifier of the user that must own the session
     * @return The owned session
     * @throws ResponseStatusException with status 404 when the session is missing or not owned
     */
    @Transactional(readOnly = true)
    public ChatSession requireOwnedSession(UUID sessionId, UUID userId) {
        return findOwnedSession(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
