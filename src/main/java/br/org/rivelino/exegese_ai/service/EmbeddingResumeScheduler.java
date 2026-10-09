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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically resumes pending chunk embeddings: runs paused by an exhausted provider quota, runs interrupted
 * by a restart, and chunks indexed while no embedding provider was configured. Every
 * {@code exegese.embedding.resume-interval} it starts {@link EmbeddingReindexService} when chunks without a
 * vector exist and no run is active. Disabled with {@code exegese.embedding.resume-enabled=false}.
 *
 * @author Rivelino Patrício
 */
@Component
public class EmbeddingResumeScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingResumeScheduler.class);

    private final EmbeddingReindexService reindexService;
    private final ChunkEmbeddingRepository chunkEmbeddingRepository;
    private final EmbeddingService embeddingService;
    private final boolean enabled;

    public EmbeddingResumeScheduler(EmbeddingReindexService reindexService,
                                    ChunkEmbeddingRepository chunkEmbeddingRepository,
                                    EmbeddingService embeddingService,
                                    @Value("${exegese.embedding.resume-enabled:true}") boolean enabled) {
        this.reindexService = reindexService;
        this.chunkEmbeddingRepository = chunkEmbeddingRepository;
        this.embeddingService = embeddingService;
        this.enabled = enabled;
    }

    /**
     * Starts a run for pending chunks, if any.
     *
     * @return true when a run was started
     */
    @Scheduled(initialDelayString = "${exegese.embedding.resume-initial-delay:PT1M}",
               fixedDelayString = "${exegese.embedding.resume-interval:PT15M}")
    public boolean resumePendingEmbeddings() {
        if (!enabled || !chunkEmbeddingRepository.supportsVectors() || !embeddingService.isConfigured()
                || reindexService.status().running()) {
            return false;
        }
        try {
            long pending = chunkEmbeddingRepository.countChunks(false);
            if (pending == 0) {
                return false;
            }
            boolean started = reindexService.start(false) == EmbeddingReindexService.StartOutcome.STARTED;
            if (started) {
                log.info("Resuming embeddings for {} pending chunks", pending);
            }
            return started;
        } catch (RuntimeException e) {
            log.warn("Could not resume pending embeddings ({}); retrying at the next interval", e.getClass().getSimpleName());
            return false;
        }
    }
}
