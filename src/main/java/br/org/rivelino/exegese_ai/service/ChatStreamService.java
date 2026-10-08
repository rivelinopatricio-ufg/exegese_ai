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

import br.org.rivelino.exegese_ai.domain.dto.ChatStreamTicket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts the asynchronous answer stream of a redeemed {@link ChatStreamTicket}.
 * <p>
 * The SSE completion, timeout and error callbacks raise a cancellation flag that the RAG pipeline checks
 * for every LLM token, so a closed browser tab stops the upstream generation. The ticket's concurrency
 * permit is released when the stream task ends, whatever the outcome.
 *
 * @author Rivelino Patrício
 */
@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    /** Upper bound for a whole answer stream (retrieval plus LLM generation). */
    public static final long STREAM_TIMEOUT_MS = 180_000L;

    private final RagOrchestrationService ragOrchestrationService;
    private final ChatStreamExecutor executor;
    private final ChatConcurrencyLimiter concurrencyLimiter;

    public ChatStreamService(RagOrchestrationService ragOrchestrationService,
                             ChatStreamExecutor executor,
                             ChatConcurrencyLimiter concurrencyLimiter) {
        this.ragOrchestrationService = ragOrchestrationService;
        this.executor = executor;
        this.concurrencyLimiter = concurrencyLimiter;
    }

    /**
     * Opens the SSE stream for a ticket already redeemed by its owner. Takes over the ticket's permit.
     *
     * @param ticket Redeemed ticket
     * @return Emitter to return from the controller
     */
    public SseEmitter start(ChatStreamTicket ticket) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(error -> cancelled.set(true));

        try {
            executor.execute(() -> run(ticket, emitter, cancelled));
        } catch (RejectedExecutionException e) {
            concurrencyLimiter.release(ticket.userId());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return emitter;
    }

    private void run(ChatStreamTicket ticket, SseEmitter emitter, AtomicBoolean cancelled) {
        try {
            ragOrchestrationService.streamRagResponse(
                    ticket.sessionId(),
                    ticket.userId(),
                    ticket.question(),
                    ticket.subjectIds(),
                    ticket.locale(),
                    emitter,
                    cancelled::get);
        } catch (RuntimeException e) {
            String reference = ErrorReference.newReference();
            log.error("Chat stream failed before the answer started [ref={}, session={}]",
                    reference, ticket.sessionId(), e);
            ragOrchestrationService.emitFailure(emitter, ticket.locale(), reference);
        } finally {
            concurrencyLimiter.release(ticket.userId());
        }
    }
}
