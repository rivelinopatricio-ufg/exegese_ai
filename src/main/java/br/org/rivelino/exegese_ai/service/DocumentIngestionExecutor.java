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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Background executor for document ingestion (PDF extraction, segmentation and embeddings). Each upload is
 * processed on its own virtual thread, and at most {@code exegese.ingestion.max-concurrent} documents are
 * processed at the same time (the others wait for a permit), which bounds CPU, temporary disk and embedding
 * provider usage. Running tasks are interrupted when the application context closes; documents left in
 * {@code PROCESSING} are marked as failed on the next startup.
 * <p>
 * Deliberately not exposed as an {@link java.util.concurrent.Executor} bean, so that Spring Boot's own
 * {@code applicationTaskExecutor} and the MVC async support keep their auto-configuration.
 *
 * @author Rivelino Patrício
 */
@Component
public class DocumentIngestionExecutor {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionExecutor.class);

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("document-ingestion-", 0).factory());
    private final Semaphore permits;

    public DocumentIngestionExecutor(@Value("${exegese.ingestion.max-concurrent:2}") int maxConcurrent) {
        this.permits = new Semaphore(Math.max(1, maxConcurrent), true);
    }

    /**
     * Runs the ingestion task on a new virtual thread once a processing permit is available.
     *
     * @param task Ingestion task
     * @throws java.util.concurrent.RejectedExecutionException when the application is shutting down
     */
    public void execute(Runnable task) {
        executor.execute(() -> {
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Document ingestion task interrupted while waiting for a processing slot");
                return;
            }
            try {
                task.run();
            } finally {
                permits.release();
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        List<Runnable> neverStarted = executor.shutdownNow();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Document ingestion executor did not terminate within 10 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!neverStarted.isEmpty()) {
            log.info("Document ingestion executor discarded {} queued task(s) on shutdown", neverStarted.size());
        }
    }
}
