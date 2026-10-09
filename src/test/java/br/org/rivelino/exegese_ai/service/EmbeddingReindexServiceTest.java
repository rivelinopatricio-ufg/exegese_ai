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

import br.org.rivelino.exegese_ai.config.TestEmbeddingModelConfiguration.FakeEmbeddingModel;
import br.org.rivelino.exegese_ai.repository.ChunkEmbeddingRepository;
import br.org.rivelino.exegese_ai.repository.ChunkEmbeddingRepository.PendingChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.google.genai.errors.ClientException;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmbeddingReindexService}: batched keyset processing, validated vectors only,
 * failure accounting, quota refusals (wait and retry, pause) and the start preconditions. The job runs
 * synchronously here and quota waits are recorded instead of slept.
 *
 * @author Rivelino Patrício
 */
class EmbeddingReindexServiceTest {

    /** Quota policy in milliseconds so that waits are only recorded, never slept. */
    private static final EmbeddingReindexService.QuotaPolicy QUOTA_POLICY = new EmbeddingReindexService.QuotaPolicy(
            Duration.ofSeconds(15), Duration.ofMinutes(5), Duration.ofMinutes(30));

    private ChunkEmbeddingRepository repository;
    private FakeEmbeddingModel model;
    private EmbeddingReindexService service;
    private final List<Duration> sleeps = new ArrayList<>();

    private EmbeddingReindexService newService(EmbeddingService embeddingService, Executor executor) {
        return new EmbeddingReindexService(repository, embeddingService, new EmbeddingRateLimiter(100_000, 10_000_000),
                QUOTA_POLICY, executor, sleeps::add);
    }

    /** Embedding model refusing the first {@code refusals} calls with HTTP 429, then delegating to the fake. */
    private static FakeEmbeddingModel quotaRefusing(int refusals, String providerMessage) {
        AtomicInteger remaining = new AtomicInteger(refusals);
        return new FakeEmbeddingModel(768) {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                if (remaining.getAndDecrement() > 0) {
                    throw new ClientException(429, "RESOURCE_EXHAUSTED", providerMessage);
                }
                return super.call(request);
            }
        };
    }

    @BeforeEach
    void setUp() {
        repository = mock(ChunkEmbeddingRepository.class);
        model = new FakeEmbeddingModel(768);
        service = newService(new EmbeddingService(model, 768, 2), Runnable::run);
        when(repository.supportsVectors()).thenReturn(true);
    }

    private static PendingChunk chunk(long n, String content) {
        return new PendingChunk(new UUID(0L, n), "Título " + n, content);
    }

    @Test
    @DisplayName("Missing/zero vectors are recomputed in batches from the stored chunk text")
    @SuppressWarnings("unchecked")
    void testReindexInBatches() {
        PendingChunk c1 = chunk(1, "dedução de despesas médicas");
        PendingChunk c2 = chunk(2, "criptoativos na ficha de bens");
        PendingChunk c3 = chunk(3, "multa por atraso");
        when(repository.countChunks(false)).thenReturn(3L);
        when(repository.findBatch(false, new UUID(0L, 0L), 2)).thenReturn(List.of(c1, c2));
        when(repository.findBatch(false, c2.id(), 2)).thenReturn(List.of(c3));
        when(repository.findBatch(false, c3.id(), 2)).thenReturn(List.of());

        assertThat(service.start(false)).isEqualTo(EmbeddingReindexService.StartOutcome.STARTED);

        ArgumentCaptor<Map<UUID, float[]>> updates = ArgumentCaptor.forClass(Map.class);
        verify(repository, times(2)).updateEmbeddings(updates.capture());
        List<UUID> updated = new ArrayList<>();
        updates.getAllValues().forEach(m -> updated.addAll(m.keySet()));
        assertThat(updated).containsExactly(c1.id(), c2.id(), c3.id());
        assertThat(updates.getAllValues().get(0).get(c1.id()))
                .isEqualTo(model.vectorOf("Título 1\ndedução de despesas médicas"));

        EmbeddingReindexService.ReindexStatus status = service.status();
        assertThat(status.state()).isEqualTo(EmbeddingReindexService.State.COMPLETED);
        assertThat(status.total()).isEqualTo(3);
        assertThat(status.processed()).isEqualTo(3);
        assertThat(status.failed()).isZero();
        assertThat(status.running()).isFalse();
    }

    @Test
    @DisplayName("forceAll re-embeds every chunk")
    void testForceAll() {
        when(repository.countChunks(true)).thenReturn(1L);
        when(repository.findBatch(eq(true), any(), anyInt())).thenReturn(List.of(chunk(9, "texto")), List.of());

        assertThat(service.start(true)).isEqualTo(EmbeddingReindexService.StartOutcome.STARTED);

        verify(repository).countChunks(true);
        verify(repository).updateEmbeddings(any());
        assertThat(service.status().forceAll()).isTrue();
        assertThat(service.status().processed()).isEqualTo(1);
    }

    @Test
    @DisplayName("Consecutive failed batches abort the run without storing any vector")
    void testAbortAfterConsecutiveFailures() {
        EmbeddingService failing = new EmbeddingService(new FakeEmbeddingModel(768) {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                throw new IllegalStateException("provider down");
            }
        }, 768, 1);
        service = newService(failing, Runnable::run);
        when(repository.countChunks(false)).thenReturn(5L);
        when(repository.findBatch(anyBoolean(), any(), anyInt())).thenAnswer(invocation -> {
            UUID after = invocation.getArgument(1);
            long next = after.getLeastSignificantBits() + 1;
            return next <= 5 ? List.of(chunk(next, "texto " + next)) : List.of();
        });

        service.start(false);

        verify(repository, never()).updateEmbeddings(any());
        EmbeddingReindexService.ReindexStatus status = service.status();
        assertThat(status.state()).isEqualTo(EmbeddingReindexService.State.FAILED);
        assertThat(status.failed()).isEqualTo(EmbeddingReindexService.MAX_CONSECUTIVE_FAILED_BATCHES);
        assertThat(status.errorReference()).isNotBlank();
    }

    @Test
    @DisplayName("The run fails as soon as the provider reports it is not configured")
    void testNotConfiguredDuringRun() {
        when(repository.countChunks(false)).thenReturn(2L);
        when(repository.findBatch(anyBoolean(), any(), anyInt())).thenReturn(List.of(chunk(1, "texto")));
        model.setUnavailable(true);

        service.start(false);

        verify(repository, never()).updateEmbeddings(any());
        assertThat(service.status().state()).isEqualTo(EmbeddingReindexService.State.FAILED);
    }

    @Test
    @DisplayName("Start preconditions: PostgreSQL required, provider configured, one run at a time")
    void testStartPreconditions() {
        when(repository.supportsVectors()).thenReturn(false);
        assertThat(service.start(false)).isEqualTo(EmbeddingReindexService.StartOutcome.UNSUPPORTED_DATABASE);

        when(repository.supportsVectors()).thenReturn(true);
        EmbeddingReindexService unconfigured = newService(
                new EmbeddingService(new UnconfiguredEmbeddingModel(768), 768, 32), Runnable::run);
        assertThat(unconfigured.start(false)).isEqualTo(EmbeddingReindexService.StartOutcome.NOT_CONFIGURED);

        List<Runnable> pending = new ArrayList<>();
        EmbeddingReindexService deferred = newService(new EmbeddingService(model, 768, 32), pending::add);
        when(repository.countChunks(false)).thenReturn(0L);
        assertThat(deferred.start(false)).isEqualTo(EmbeddingReindexService.StartOutcome.STARTED);
        assertThat(deferred.status().running()).isTrue();
        assertThat(deferred.start(false)).isEqualTo(EmbeddingReindexService.StartOutcome.ALREADY_RUNNING);

        when(repository.findBatch(anyBoolean(), any(), anyInt())).thenReturn(List.of());
        pending.forEach(Runnable::run);
        assertThat(deferred.status().state()).isEqualTo(EmbeddingReindexService.State.COMPLETED);
        assertThat(deferred.start(false)).isEqualTo(EmbeddingReindexService.StartOutcome.STARTED);
    }

    @Test
    @DisplayName("A quota refusal (HTTP 429) waits and retries the same batch instead of skipping it")
    void testQuotaRefusalWaitsAndRetriesSameBatch() {
        service = newService(new EmbeddingService(quotaRefusing(2, "Resource has been exhausted"), 768, 2), Runnable::run);
        PendingChunk c1 = chunk(1, "dedução de despesas médicas");
        PendingChunk c2 = chunk(2, "criptoativos na ficha de bens");
        when(repository.countChunks(false)).thenReturn(2L);
        when(repository.findBatch(false, new UUID(0L, 0L), 2)).thenReturn(List.of(c1, c2));
        when(repository.findBatch(false, c2.id(), 2)).thenReturn(List.of());

        service.start(false);

        verify(repository, times(1)).updateEmbeddings(any());
        assertThat(sleeps).containsExactly(Duration.ofSeconds(15), Duration.ofSeconds(30));
        EmbeddingReindexService.ReindexStatus status = service.status();
        assertThat(status.state()).isEqualTo(EmbeddingReindexService.State.COMPLETED);
        assertThat(status.processed()).isEqualTo(2);
        assertThat(status.failed()).isZero();
        assertThat(status.waitingUntil()).isNull();
    }

    @Test
    @DisplayName("The wait suggested by the provider in the 429 message is honored")
    void testQuotaRefusalHonorsSuggestedDelay() {
        service = newService(new EmbeddingService(
                quotaRefusing(1, "Quota exceeded for metric. Please retry in 42.5s."), 768, 2), Runnable::run);
        when(repository.countChunks(false)).thenReturn(1L);
        when(repository.findBatch(eq(false), any(), anyInt())).thenReturn(List.of(chunk(1, "texto")), List.of());

        service.start(false);

        assertThat(sleeps).containsExactly(Duration.ofMillis(42_500));
        assertThat(service.status().state()).isEqualTo(EmbeddingReindexService.State.COMPLETED);
    }

    @Test
    @DisplayName("A quota that stays exhausted pauses the run: nothing fails, chunks stay pending for the resume")
    void testPersistentQuotaPausesRun() {
        service = newService(new EmbeddingService(quotaRefusing(Integer.MAX_VALUE, "exhausted"), 768, 2), Runnable::run);
        when(repository.countChunks(false)).thenReturn(4L);
        when(repository.findBatch(eq(false), any(), anyInt())).thenReturn(List.of(chunk(1, "a"), chunk(2, "b")));

        service.start(false);

        verify(repository, never()).updateEmbeddings(any());
        EmbeddingReindexService.ReindexStatus status = service.status();
        assertThat(status.state()).isEqualTo(EmbeddingReindexService.State.PAUSED_QUOTA);
        assertThat(status.failed()).isZero();
        assertThat(status.running()).isFalse();
        Duration waited = sleeps.stream().reduce(Duration.ZERO, Duration::plus);
        assertThat(waited).isLessThanOrEqualTo(QUOTA_POLICY.maxQuotaWait());
        assertThat(sleeps).allSatisfy(d -> assertThat(d).isLessThanOrEqualTo(QUOTA_POLICY.maxBackoff()));
        // Every wait retried the same (first) batch: the keyset never advanced
        verify(repository, times(1)).findBatch(eq(false), any(), anyInt());
    }

    @Test
    @DisplayName("Chunks requested during a run get one follow-up pass")
    void testRequestDuringRunQueuesFollowUpPass() {
        List<Runnable> pending = new ArrayList<>();
        service = newService(new EmbeddingService(model, 768, 2), pending::add);
        when(repository.countChunks(false)).thenReturn(0L);
        when(repository.findBatch(anyBoolean(), any(), anyInt())).thenReturn(List.of());

        assertThat(service.requestPendingEmbeddings()).isEqualTo(EmbeddingReindexService.StartOutcome.STARTED);
        assertThat(service.requestPendingEmbeddings()).isEqualTo(EmbeddingReindexService.StartOutcome.ALREADY_RUNNING);

        pending.remove(0).run();
        assertThat(pending).as("follow-up pass scheduled").hasSize(1);
        pending.remove(0).run();
        assertThat(pending).as("no further pass without a new request").isEmpty();
        assertThat(service.status().state()).isEqualTo(EmbeddingReindexService.State.COMPLETED);
    }
}
