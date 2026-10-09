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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link EmbeddingRateLimiter}: token estimation, non-blocking acquisition within the
 * per-minute budget and batches larger than a whole minute of quota.
 *
 * @author Rivelino Patrício
 */
class EmbeddingRateLimiterTest {

    @Test
    @DisplayName("Tokens are estimated as characters / 4, at least one per text")
    void testEstimateTokens() {
        assertThat(EmbeddingRateLimiter.estimateTokens(List.of("abcd", "abcde", ""))).isEqualTo(1 + 2 + 1);
    }

    @Test
    @Timeout(5)
    @DisplayName("Batches within the per-minute budget are acquired without waiting")
    void testAcquireWithinBudget() throws InterruptedException {
        EmbeddingRateLimiter limiter = new EmbeddingRateLimiter(10, 1_000);
        limiter.acquire(List.of("texto um", "texto dois"));
        limiter.acquire(Collections.nCopies(8, "x"));
        limiter.acquire(List.of());
    }

    @Test
    @Timeout(5)
    @DisplayName("A batch larger than a whole minute of quota reserves the full minute instead of blocking forever")
    void testOversizedBatchIsCapped() throws InterruptedException {
        EmbeddingRateLimiter limiter = new EmbeddingRateLimiter(2, 10);
        limiter.acquire(Collections.nCopies(5, "um texto bem maior que o limite de tokens por minuto"));
    }

    @Test
    @DisplayName("Limits must be positive")
    void testInvalidLimits() {
        assertThatThrownBy(() -> new EmbeddingRateLimiter(0, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingRateLimiter(10, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
