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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ChatStreamService} covering SSE lifecycle, cancellation triggers, and executor rejection.
 *
 * @author Rivelino Patrício
 */
class ChatStreamServiceTest {

    @Mock
    private RagOrchestrationService ragOrchestrationService;

    @Mock
    private ChatStreamExecutor executor;

    @Mock
    private ChatConcurrencyLimiter concurrencyLimiter;

    private ChatStreamService chatStreamService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        chatStreamService = new ChatStreamService(ragOrchestrationService, executor, concurrencyLimiter);
    }

    private ChatStreamTicket sampleTicket() {
        return new ChatStreamTicket(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Como declarar IRPF?",
                List.of(UUID.randomUUID()),
                Locale.forLanguageTag("pt-BR")
        );
    }

    @Test
    @DisplayName("start submits asynchronous stream and executes RAG response pipeline")
    void testStartExecutesStream() {
        ChatStreamTicket ticket = sampleTicket();

        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(executor).execute(any());

        SseEmitter emitter = chatStreamService.start(ticket);

        assertThat(emitter).isNotNull();
        verify(ragOrchestrationService).streamRagResponse(
                eq(ticket.sessionId()),
                eq(ticket.userId()),
                eq(ticket.question()),
                eq(ticket.subjectIds()),
                eq(ticket.locale()),
                eq(emitter),
                any()
        );
        verify(concurrencyLimiter).release(ticket.userId());
    }

    @Test
    @DisplayName("start releases permit and throws SERVICE_UNAVAILABLE on RejectedExecutionException")
    void testStartRejection() {
        ChatStreamTicket ticket = sampleTicket();

        doThrow(new RejectedExecutionException("Worker pool full")).when(executor).execute(any());

        assertThatThrownBy(() -> chatStreamService.start(ticket))
                .isInstanceOf(ResponseStatusException.class)
                .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE);

        verify(concurrencyLimiter).release(ticket.userId());
    }

    @Test
    @DisplayName("run emits failure and releases concurrency limiter on pipeline exception")
    void testRunHandlesException() {
        ChatStreamTicket ticket = sampleTicket();

        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(executor).execute(any());

        doThrow(new RuntimeException("LLM upstream error")).when(ragOrchestrationService).streamRagResponse(
                any(), any(), any(), any(), any(), any(), any());

        SseEmitter emitter = chatStreamService.start(ticket);

        assertThat(emitter).isNotNull();
        verify(ragOrchestrationService).emitFailure(eq(emitter), eq(ticket.locale()), any());
        verify(concurrencyLimiter).release(ticket.userId());
    }
}
