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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmbeddingResumeScheduler}: pending chunks are resumed only when the feature is
 * enabled, PostgreSQL and a provider are available and no run is active.
 *
 * @author Rivelino Patrício
 */
class EmbeddingResumeSchedulerTest {

    private EmbeddingReindexService reindexService;
    private ChunkEmbeddingRepository repository;
    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        reindexService = mock(EmbeddingReindexService.class);
        repository = mock(ChunkEmbeddingRepository.class);
        embeddingService = mock(EmbeddingService.class);
        when(repository.supportsVectors()).thenReturn(true);
        when(embeddingService.isConfigured()).thenReturn(true);
        when(reindexService.status()).thenReturn(EmbeddingReindexService.ReindexStatus.IDLE);
        when(reindexService.start(false)).thenReturn(EmbeddingReindexService.StartOutcome.STARTED);
    }

    @Test
    @DisplayName("Pending chunks start a run")
    void testResumesPendingChunks() {
        when(repository.countChunks(false)).thenReturn(42L);

        assertThat(new EmbeddingResumeScheduler(reindexService, repository, embeddingService, true)
                .resumePendingEmbeddings()).isTrue();
        verify(reindexService).start(false);
    }

    @Test
    @DisplayName("Nothing pending, disabled, unconfigured or already running: no run is started")
    void testSkipsWhenNotNeeded() {
        when(repository.countChunks(false)).thenReturn(0L);
        assertThat(new EmbeddingResumeScheduler(reindexService, repository, embeddingService, true)
                .resumePendingEmbeddings()).isFalse();

        when(repository.countChunks(false)).thenReturn(5L);
        assertThat(new EmbeddingResumeScheduler(reindexService, repository, embeddingService, false)
                .resumePendingEmbeddings()).isFalse();

        when(embeddingService.isConfigured()).thenReturn(false);
        assertThat(new EmbeddingResumeScheduler(reindexService, repository, embeddingService, true)
                .resumePendingEmbeddings()).isFalse();

        when(embeddingService.isConfigured()).thenReturn(true);
        when(reindexService.status()).thenReturn(new EmbeddingReindexService.ReindexStatus(
                EmbeddingReindexService.State.RUNNING, false, 5, 0, 0, Instant.now(), null, null, null));
        assertThat(new EmbeddingResumeScheduler(reindexService, repository, embeddingService, true)
                .resumePendingEmbeddings()).isFalse();

        verify(reindexService, never()).start(anyBoolean());
    }
}
