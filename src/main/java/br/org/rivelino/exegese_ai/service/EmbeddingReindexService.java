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

import br.org.rivelino.exegese_ai.repository.ChunkEmbeddingRepository;
import br.org.rivelino.exegese_ai.repository.ChunkEmbeddingRepository.PendingChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Background job that recomputes chunk embeddings from the text already stored in {@code exegese_chunk}
 * (no original PDF needed). By default it processes chunks whose vector is missing or all-zero (legacy
 * placeholder vectors); with {@code forceAll} it re-embeds every chunk, e.g. after changing the model.
 * <p>
 * Chunks are processed in batches of {@code exegese.embedding.batch-size} on a dedicated virtual thread; at
 * most one run is active at a time. Progress is logged after each batch and exposed by {@link #status()}.
 * A failed batch is counted and skipped; the run aborts after several consecutive failed batches or when the
 * embedding provider is not configured. Invalid vectors are never stored.
 *
 * @author Rivelino Patrício
 */
@Service
public class EmbeddingReindexService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingReindexService.class);

    static final int MAX_CONSECUTIVE_FAILED_BATCHES = 3;
    private static final UUID LOWEST_UUID = new UUID(0L, 0L);

    /** Lifecycle of the reindex job. */
    public enum State { IDLE, RUNNING, COMPLETED, FAILED }

    /** Result of a start request. */
    public enum StartOutcome { STARTED, ALREADY_RUNNING, NOT_CONFIGURED, UNSUPPORTED_DATABASE }

    /**
     * Snapshot of the current or last run.
     *
     * @param state Job state
     * @param forceAll Whether every chunk is re-embedded
     * @param total Chunks selected when the run started
     * @param processed Chunks whose embedding was stored
     * @param failed Chunks skipped because their batch failed
     * @param startedAt Start instant, null when idle
     * @param finishedAt End instant, null while running
     * @param errorReference Correlation reference of the log entry when the run failed
     */
    public record ReindexStatus(State state, boolean forceAll, long total, long processed, long failed,
                                Instant startedAt, Instant finishedAt, String errorReference) {

        static final ReindexStatus IDLE = new ReindexStatus(State.IDLE, false, 0, 0, 0, null, null, null);

        public boolean running() {
            return state == State.RUNNING;
        }
    }

    private final ChunkEmbeddingRepository chunkEmbeddingRepository;
    private final EmbeddingService embeddingService;
    private final Executor executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<ReindexStatus> status = new AtomicReference<>(ReindexStatus.IDLE);

    @Autowired
    public EmbeddingReindexService(ChunkEmbeddingRepository chunkEmbeddingRepository, EmbeddingService embeddingService) {
        this(chunkEmbeddingRepository, embeddingService, virtualThreadExecutor());
    }

    EmbeddingReindexService(ChunkEmbeddingRepository chunkEmbeddingRepository, EmbeddingService embeddingService,
                            Executor executor) {
        this.chunkEmbeddingRepository = chunkEmbeddingRepository;
        this.embeddingService = embeddingService;
        this.executor = executor;
    }

    private static Executor virtualThreadExecutor() {
        ThreadFactory factory = Thread.ofVirtual().name("embedding-reindex-", 0).factory();
        return task -> factory.newThread(task).start();
    }

    /**
     * Starts a reindex run in the background unless one is already running.
     *
     * @param forceAll true to re-embed every chunk, false for missing or zero vectors only
     * @return What happened
     */
    public StartOutcome start(boolean forceAll) {
        if (!chunkEmbeddingRepository.supportsVectors()) {
            return StartOutcome.UNSUPPORTED_DATABASE;
        }
        if (!embeddingService.isConfigured()) {
            return StartOutcome.NOT_CONFIGURED;
        }
        if (!running.compareAndSet(false, true)) {
            return StartOutcome.ALREADY_RUNNING;
        }
        try {
            long total = chunkEmbeddingRepository.countChunks(forceAll);
            status.set(new ReindexStatus(State.RUNNING, forceAll, total, 0, 0, Instant.now(), null, null));
            log.info("Embedding reindex started (forceAll={}): {} chunks selected", forceAll, total);
            executor.execute(() -> run(forceAll));
            return StartOutcome.STARTED;
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    /**
     * @return Snapshot of the current or last run
     */
    public ReindexStatus status() {
        return status.get();
    }

    private void run(boolean forceAll) {
        ReindexStatus started = status.get();
        long processed = 0;
        long failed = 0;
        int consecutiveFailures = 0;
        UUID after = LOWEST_UUID;
        try {
            while (true) {
                List<PendingChunk> batch = chunkEmbeddingRepository.findBatch(forceAll, after, embeddingService.batchSize());
                if (batch.isEmpty()) {
                    break;
                }
                after = batch.get(batch.size() - 1).id();

                List<PendingChunk> embeddable = new ArrayList<>(batch.size());
                for (PendingChunk chunk : batch) {
                    if (EmbeddingService.chunkEmbeddingText(chunk.title(), chunk.content()).isBlank()) {
                        failed++;
                    } else {
                        embeddable.add(chunk);
                    }
                }

                try {
                    List<float[]> vectors = embeddingService.embedAll(embeddable.stream()
                            .map(c -> EmbeddingService.chunkEmbeddingText(c.title(), c.content()))
                            .toList());
                    Map<UUID, float[]> updates = new LinkedHashMap<>();
                    for (int i = 0; i < embeddable.size(); i++) {
                        updates.put(embeddable.get(i).id(), vectors.get(i));
                    }
                    chunkEmbeddingRepository.updateEmbeddings(updates);
                    processed += embeddable.size();
                    consecutiveFailures = 0;
                } catch (EmbeddingException e) {
                    if (e.isNotConfigured()) {
                        throw e;
                    }
                    failed += embeddable.size();
                    consecutiveFailures++;
                    log.warn("Embedding reindex: batch of {} chunks failed ({}); skipped", embeddable.size(), e.getMessage());
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILED_BATCHES) {
                        throw new IllegalStateException("Aborted after " + consecutiveFailures + " consecutive failed batches", e);
                    }
                }

                status.set(new ReindexStatus(State.RUNNING, forceAll, started.total(), processed, failed,
                        started.startedAt(), null, null));
                log.info("Embedding reindex progress: {} stored, {} failed, {} selected", processed, failed, started.total());
            }
            status.set(new ReindexStatus(State.COMPLETED, forceAll, started.total(), processed, failed,
                    started.startedAt(), Instant.now(), null));
            log.info("Embedding reindex completed: {} stored, {} failed", processed, failed);
        } catch (RuntimeException e) {
            String reference = ErrorReference.newReference();
            log.error("Embedding reindex failed [ref={}] after {} stored, {} failed", reference, processed, failed, e);
            status.set(new ReindexStatus(State.FAILED, forceAll, started.total(), processed, failed,
                    started.startedAt(), Instant.now(), reference));
        } finally {
            running.set(false);
        }
    }
}
