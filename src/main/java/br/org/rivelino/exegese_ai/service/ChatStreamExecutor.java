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

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Dedicated executor for the chat answer streams. Each stream runs on its own virtual thread, so blocking
 * I/O on the LLM never starves a shared pool (such as {@code ForkJoinPool.commonPool}). Running streams are
 * interrupted when the application context closes.
 * <p>
 * Deliberately not exposed as an {@link java.util.concurrent.Executor} bean, so that Spring Boot's own
 * {@code applicationTaskExecutor} and the MVC async support keep their auto-configuration.
 *
 * @author Rivelino Patrício
 */
@Component
public class ChatStreamExecutor {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamExecutor.class);

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("chat-stream-", 0).factory());

    /**
     * Runs the task on a new virtual thread.
     *
     * @param task Stream task
     * @throws java.util.concurrent.RejectedExecutionException when the application is shutting down
     */
    public void execute(Runnable task) {
        executor.execute(task);
    }

    @PreDestroy
    public void shutdown() {
        List<Runnable> neverStarted = executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Chat stream executor did not terminate within 10 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!neverStarted.isEmpty()) {
            log.info("Chat stream executor discarded {} queued stream(s) on shutdown", neverStarted.size());
        }
    }
}
