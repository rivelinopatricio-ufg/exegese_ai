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

import br.org.rivelino.exegese_ai.domain.entity.ChatMessage;
import br.org.rivelino.exegese_ai.domain.entity.ChatSession;
import br.org.rivelino.exegese_ai.repository.ChatMessageRepository;
import br.org.rivelino.exegese_ai.repository.ChatSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists the messages of a chat exchange in short transactions, so that the streaming pipeline never
 * holds a database connection while it waits for the LLM.
 *
 * @author Rivelino Patrício
 */
@Service
public class ChatTranscriptService {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    public ChatTranscriptService(ChatSessionRepository sessionRepository,
                                 ChatMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * Stores the question asked by the user.
     *
     * @param sessionId Chat session identifier (ownership already verified by the caller)
     * @param question Question text
     * @param appliedSubjectIdsJson JSON array of the subject filters, or null
     * @return The persisted message
     */
    @Transactional
    public ChatMessage saveUserMessage(UUID sessionId, String question, String appliedSubjectIdsJson) {
        ChatSession session = requireSession(sessionId);
        ChatMessage message = new ChatMessage(session, ROLE_USER, question);
        message.setAppliedSubjectIds(appliedSubjectIdsJson);
        return messageRepository.save(message);
    }

    /**
     * Stores a complete assistant answer and marks the session as recently updated.
     *
     * @param sessionId Chat session identifier
     * @param answer Full answer text
     * @param citationsJson JSON array of citations, or null
     * @param modelUsed Model (or guard) that produced the answer
     * @param executionDurationMs Total pipeline duration
     * @return The persisted message
     */
    @Transactional
    public ChatMessage saveAssistantMessage(UUID sessionId,
                                            String answer,
                                            String citationsJson,
                                            String modelUsed,
                                            int executionDurationMs) {
        ChatSession session = requireSession(sessionId);
        ChatMessage message = new ChatMessage(session, ROLE_ASSISTANT, answer);
        message.setCitations(citationsJson);
        message.setModelUsed(modelUsed);
        message.setExecutionDurationMs(executionDurationMs);
        ChatMessage saved = messageRepository.save(message);

        session.setUpdatedAt(Instant.now());
        return saved;
    }

    private ChatSession requireSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalStateException("Chat session no longer exists: " + sessionId));
    }
}
