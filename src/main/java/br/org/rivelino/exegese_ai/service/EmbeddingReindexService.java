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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
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
 * Background job that computes chunk embeddings from the text already stored in {@code exegese_chunk} (no
 * original PDF needed). It is started after every document ingestion (chunks are written without a vector
 * and are searchable by full text at once), by the "Reindex embeddings" admin action and by the periodic
 * resume ({@link EmbeddingResumeScheduler}). By default it processes chunks whose vector is missing or
 * all-zero (legacy placeholder vectors); with {@code forceAll} it re-embeds every chunk.
 * <p>
 * Chunks are processed in batches of {@code exegese.embedding.batch-size} on a dedicated virtual thread,
 * paced by {@link EmbeddingRateLimiter}; at most one run is active at a time, and a run requested while
 * another is active is queued (one follow-up pass). When the provider refuses a batch for quota (HTTP 429)
 * the job waits (the provider's suggested delay, or an exponential backoff) and retries the same batch;
 * after {@code exegese.embedding.max-quota-wait} without progress (typically a daily quota) it stops with
 * state {@link State#PAUSED_QUOTA} and the remaining chunks are resumed later. Other provider errors skip
 * the batch; the run aborts after several consecutive failed batches or when no provider is configured.
 * Invalid vectors are never stored.
 *
 * @author Rivelino Patrício
 */
@Service
public class EmbeddingReindexService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingReindexService.class);

    static final int MAX_CONSECUTIVE_FAILED_BATCHES = 3;
    private static final UUID LOWEST_UUID = new UUID(0L, 0L);
    private static final Duration MIN_QUOTA_WAIT = Duration.ofSeconds(1);

    /** Lifecycle of the embedding job. */
    public enum State { IDLE, RUNNING, COMPLETED, PAUSED_QUOTA, FAILED }

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
     * @param waitingUntil While running: end of the current wait for provider quota, null when not waiting
     */
    public record ReindexStatus(State state, boolean forceAll, long total, long processed, long failed,
                                Instant startedAt, Instant finishedAt, String errorReference, Instant waitingUntil) {

        static final ReindexStatus IDLE = new ReindexStatus(State.IDLE, false, 0, 0, 0, null, null, null, null);

        public boolean running() {
            return state == State.RUNNING;
        }

        public boolean waitingForQuota() {
            return state == State.RUNNING && waitingUntil != null;
        }

        ReindexStatus progress(long newProcessed, long newFailed, Instant newWaitingUntil) {
            return new ReindexStatus(State.RUNNING, forceAll, total, newProcessed, newFailed, startedAt, null, null,
                    newWaitingUntil);
        }

        ReindexStatus finish(State finalState, long newProcessed, long newFailed, String reference) {
            return new ReindexStatus(finalState, forceAll, total, newProcessed, newFailed, startedAt, Instant.now(),
                    reference, null);
        }
    }

    /** Waits between quota retries (replaced in tests). */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * Quota retry policy.
     *
     * @param initialBackoff First wait when the provider gives no suggested delay (doubled at each retry)
     * @param maxBackoff Upper bound of a single wait
     * @param maxQuotaWait Total wait for one batch after which the run pauses
     */
    record QuotaPolicy(Duration initialBackoff, Duration maxBackoff, Duration maxQuotaWait) {

        Duration backoff(int attempt) {
            Duration wait = initialBackoff.multipliedBy(1L << Math.min(attempt, 20));
            return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
        }
    }

    private final ChunkEmbeddingRepository chunkEmbeddingRepository;
    private final EmbeddingService embeddingService;
    private final EmbeddingRateLimiter rateLimiter;
    private final QuotaPolicy quotaPolicy;
    private final Executor executor;
    private final Sleeper sleeper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean rerunRequested = new AtomicBoolean(false);
    private final AtomicReference<ReindexStatus> status = new AtomicReference<>(ReindexStatus.IDLE);

    @Autowired
    public EmbeddingReindexService(ChunkEmbeddingRepository chunkEmbeddingRepository,
                                   EmbeddingService embeddingService,
                                   EmbeddingRateLimiter rateLimiter,
                                   @Value("${exegese.embedding.initial-backoff:PT15S}") Duration initialBackoff,
                                   @Value("${exegese.embedding.max-backoff:PT5M}") Duration maxBackoff,
                                   @Value("${exegese.embedding.max-quota-wait:PT30M}") Duration maxQuotaWait) {
        this(chunkEmbeddingRepository, embeddingService, rateLimiter,
                new QuotaPolicy(initialBackoff, maxBackoff, maxQuotaWait), virtualThreadExecutor(), Thread::sleep);
    }

    EmbeddingReindexService(ChunkEmbeddingRepository chunkEmbeddingRepository, EmbeddingService embeddingService,
                            EmbeddingRateLimiter rateLimiter, QuotaPolicy quotaPolicy, Executor executor,
                            Sleeper sleeper) {
        this.chunkEmbeddingRepository = chunkEmbeddingRepository;
        this.embeddingService = embeddingService;
        this.rateLimiter = rateLimiter;
        this.quotaPolicy = quotaPolicy;
        this.executor = executor;
        this.sleeper = sleeper;
    }

    private static Executor virtualThreadExecutor() {
        ThreadFactory factory = Thread.ofVirtual().name("embedding-reindex-", 0).factory();
        return task -> factory.newThread(task).start();
    }

    /**
     * Starts a run in the background unless one is already running.
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
            status.set(new ReindexStatus(State.RUNNING, forceAll, total, 0, 0, Instant.now(), null, null, null));
            log.info("Embedding job started (forceAll={}): {} chunks selected", forceAll, total);
            executor.execute(() -> run(forceAll));
            return StartOutcome.STARTED;
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
    }

    /**
     * Requests embeddings for chunks that have none (e.g. a document that was just indexed): starts a run, or
     * queues one follow-up pass when a run is already active (its keyset may already be past the new chunks).
     *
     * @return What happened
     */
    public StartOutcome requestPendingEmbeddings() {
        StartOutcome outcome = start(false);
        if (outcome == StartOutcome.ALREADY_RUNNING) {
            rerunRequested.set(true);
        }
        return outcome;
    }

    /**
     * @return Snapshot of the current or last run
     */
    public ReindexStatus status() {
        return status.get();
    }

    private void run(boolean forceAll) {
        long processed = 0;
        long failed = 0;
        int consecutiveFailures = 0;
        UUID after = LOWEST_UUID;
        State finalState = State.COMPLETED;
        try {
            while (true) {
                List<PendingChunk> batch = chunkEmbeddingRepository.findBatch(forceAll, after, embeddingService.batchSize());
                if (batch.isEmpty()) {
                    break;
                }

                List<PendingChunk> embeddable = new ArrayList<>(batch.size());
                for (PendingChunk chunk : batch) {
                    if (EmbeddingService.chunkEmbeddingText(chunk.title(), chunk.content()).isBlank()) {
                        failed++;
                    } else {
                        embeddable.add(chunk);
                    }
                }

                BatchResult result = embedBatch(embeddable, processed, failed);
                if (result == BatchResult.PAUSED) {
                    finalState = State.PAUSED_QUOTA;
                    break;
                }
                if (result == BatchResult.STORED) {
                    processed += embeddable.size();
                    consecutiveFailures = 0;
                } else {
                    failed += embeddable.size();
                    consecutiveFailures++;
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILED_BATCHES) {
                        throw new IllegalStateException("Aborted after " + consecutiveFailures + " consecutive failed batches");
                    }
                }
                // The keyset only moves forward once the batch is stored or skipped (never during quota waits)
                after = batch.get(batch.size() - 1).id();

                status.set(status.get().progress(processed, failed, null));
                log.info("Embedding job progress: {} stored, {} failed, {} selected", processed, failed, status.get().total());
            }

            status.set(status.get().finish(finalState, processed, failed, null));
            if (finalState == State.PAUSED_QUOTA) {
                log.warn("Embedding job paused: the provider quota is still exhausted after waiting {} ({} stored, {} "
                        + "pending chunks are resumed automatically later)", quotaPolicy.maxQuotaWait(), processed,
                        Math.max(0, status.get().total() - processed - failed));
            } else {
                log.info("Embedding job completed: {} stored, {} failed", processed, failed);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Embedding job interrupted (application shutdown?) after {} stored; pending chunks are resumed later",
                    processed);
            status.set(status.get().finish(State.FAILED, processed, failed, ErrorReference.newReference()));
            finalState = State.FAILED;
        } catch (RuntimeException e) {
            String reference = ErrorReference.newReference();
            log.error("Embedding job failed [ref={}] after {} stored, {} failed", reference, processed, failed, e);
            status.set(status.get().finish(State.FAILED, processed, failed, reference));
            finalState = State.FAILED;
        } finally {
            running.set(false);
        }

        if (finalState == State.COMPLETED && rerunRequested.getAndSet(false)) {
            log.info("Embedding job: starting the queued follow-up pass for chunks added during the run");
            start(false);
        }
    }

    private enum BatchResult { STORED, SKIPPED, PAUSED }

    /**
     * Embeds and stores one batch, waiting and retrying while the provider refuses it for quota.
     */
    private BatchResult embedBatch(List<PendingChunk> embeddable, long processed, long failed) throws InterruptedException {
        if (embeddable.isEmpty()) {
            return BatchResult.STORED;
        }
        List<String> texts = embeddable.stream()
                .map(c -> EmbeddingService.chunkEmbeddingText(c.title(), c.content()))
                .toList();
        Duration quotaWaited = Duration.ZERO;
        int attempt = 0;
        while (true) {
            try {
                rateLimiter.acquire(texts);
                List<float[]> vectors = embeddingService.embedAll(texts);
                Map<UUID, float[]> updates = new LinkedHashMap<>();
                for (int i = 0; i < embeddable.size(); i++) {
                    updates.put(embeddable.get(i).id(), vectors.get(i));
                }
                chunkEmbeddingRepository.updateEmbeddings(updates);
                return BatchResult.STORED;
            } catch (EmbeddingException e) {
                if (e.isNotConfigured()) {
                    throw e;
                }
                if (!e.isRateLimited()) {
                    log.warn("Embedding job: batch of {} chunks failed ({}); skipped", embeddable.size(), e.getMessage());
                    return BatchResult.SKIPPED;
                }
                Duration wait = e.retryAfter() != null
                        ? max(e.retryAfter(), MIN_QUOTA_WAIT)
                        : quotaPolicy.backoff(attempt);
                attempt++;
                if (quotaWaited.plus(wait).compareTo(quotaPolicy.maxQuotaWait()) > 0) {
                    return BatchResult.PAUSED;
                }
                log.warn("Embedding job: provider quota exceeded; waiting {} before retrying the batch (attempt {})",
                        wait, attempt);
                status.set(status.get().progress(processed, failed, Instant.now().plus(wait)));
                sleeper.sleep(wait);
                quotaWaited = quotaWaited.plus(wait);
                status.set(status.get().progress(processed, failed, null));
            }
        }
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }
}
