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
import org.mockito.ArgumentCaptor;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * failure accounting and the start preconditions. The job runs synchronously here.
 *
 * @author Rivelino Patrício
 */
class EmbeddingReindexServiceTest {

    private ChunkEmbeddingRepository repository;
    private FakeEmbeddingModel model;
    private EmbeddingReindexService service;

    @BeforeEach
    void setUp() {
        repository = mock(ChunkEmbeddingRepository.class);
        model = new FakeEmbeddingModel(768);
        service = new EmbeddingReindexService(repository, new EmbeddingService(model, 768, 2), Runnable::run);
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
        service = new EmbeddingReindexService(repository, failing, Runnable::run);
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
        EmbeddingReindexService unconfigured = new EmbeddingReindexService(repository,
                new EmbeddingService(new UnconfiguredEmbeddingModel(768), 768, 32), Runnable::run);
        assertThat(unconfigured.start(false)).isEqualTo(EmbeddingReindexService.StartOutcome.NOT_CONFIGURED);

        List<Runnable> pending = new ArrayList<>();
        EmbeddingReindexService deferred = new EmbeddingReindexService(repository,
                new EmbeddingService(model, 768, 32), pending::add);
        when(repository.countChunks(false)).thenReturn(0L);
        assertThat(deferred.start(false)).isEqualTo(EmbeddingReindexService.StartOutcome.STARTED);
        assertThat(deferred.status().running()).isTrue();
        assertThat(deferred.start(false)).isEqualTo(EmbeddingReindexService.StartOutcome.ALREADY_RUNNING);

        when(repository.findBatch(anyBoolean(), any(), anyInt())).thenReturn(List.of());
        pending.forEach(Runnable::run);
        assertThat(deferred.status().state()).isEqualTo(EmbeddingReindexService.State.COMPLETED);
        assertThat(deferred.start(false)).isEqualTo(EmbeddingReindexService.StartOutcome.STARTED);
    }
}
