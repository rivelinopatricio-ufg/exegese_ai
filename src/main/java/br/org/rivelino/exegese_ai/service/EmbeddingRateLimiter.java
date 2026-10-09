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

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Paces the background embedding job (document ingestion and reindexing) below the provider quota: at most
 * {@code exegese.embedding.requests-per-minute} texts and {@code exegese.embedding.tokens-per-minute}
 * estimated tokens (characters / 4) per minute. {@link #acquire(List)} blocks until the batch fits, which is
 * cheap on the job's virtual thread. Chat queries are not paced here: they fall back to full-text search
 * as soon as the provider refuses a call.
 *
 * @author Rivelino Patrício
 */
@Component
public class EmbeddingRateLimiter {

    private static final int CHARS_PER_TOKEN = 4;

    private final Bucket requestBucket;
    private final Bucket tokenBucket;
    private final long requestsPerMinute;
    private final long tokensPerMinute;

    public EmbeddingRateLimiter(@Value("${exegese.embedding.requests-per-minute:90}") long requestsPerMinute,
                                @Value("${exegese.embedding.tokens-per-minute:25000}") long tokensPerMinute) {
        if (requestsPerMinute <= 0 || tokensPerMinute <= 0) {
            throw new IllegalArgumentException(
                    "exegese.embedding.requests-per-minute and tokens-per-minute must be positive");
        }
        this.requestsPerMinute = requestsPerMinute;
        this.tokensPerMinute = tokensPerMinute;
        this.requestBucket = perMinuteBucket(requestsPerMinute);
        this.tokenBucket = perMinuteBucket(tokensPerMinute);
    }

    private static Bucket perMinuteBucket(long perMinute) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)).build())
                .build();
    }

    /**
     * Waits until the batch fits the configured per-minute pace, then reserves it. A batch larger than a
     * whole minute of quota reserves the full minute (it is never refused).
     *
     * @param texts Texts about to be sent to the provider
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public void acquire(List<String> texts) throws InterruptedException {
        if (texts == null || texts.isEmpty()) {
            return;
        }
        requestBucket.asBlocking().consume(Math.min(texts.size(), requestsPerMinute));
        tokenBucket.asBlocking().consume(Math.min(estimateTokens(texts), tokensPerMinute));
    }

    /**
     * @param texts Texts to embed
     * @return Estimated provider tokens (characters / 4, at least 1 per text)
     */
    static long estimateTokens(List<String> texts) {
        long tokens = 0;
        for (String text : texts) {
            int length = text != null ? text.length() : 0;
            tokens += Math.max(1, (length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN);
        }
        return tokens;
    }
}
