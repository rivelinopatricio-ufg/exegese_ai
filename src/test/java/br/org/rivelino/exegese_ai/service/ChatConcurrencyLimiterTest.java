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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ChatConcurrencyLimiter} validating per-user concurrency permits.
 *
 * @author Rivelino Patrício
 */
class ChatConcurrencyLimiterTest {

    private ChatConcurrencyLimiter limiter;
    private UUID userId;

    @BeforeEach
    void setUp() {
        limiter = new ChatConcurrencyLimiter(2);
        userId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Acquires permits up to maximum allowed and rejects excess requests")
    void testTryAcquireUpToMax() {
        assertThat(limiter.getMaxConcurrentStreams()).isEqualTo(2);
        assertThat(limiter.inFlight(userId)).isEqualTo(0);

        assertThat(limiter.tryAcquire(userId)).isTrue();
        assertThat(limiter.inFlight(userId)).isEqualTo(1);

        assertThat(limiter.tryAcquire(userId)).isTrue();
        assertThat(limiter.inFlight(userId)).isEqualTo(2);

        assertThat(limiter.tryAcquire(userId)).isFalse();
        assertThat(limiter.inFlight(userId)).isEqualTo(2);
    }

    @Test
    @DisplayName("Releasing permits decrements count and allows acquiring new permits")
    void testReleasePermits() {
        limiter.tryAcquire(userId);
        limiter.tryAcquire(userId);
        assertThat(limiter.tryAcquire(userId)).isFalse();

        limiter.release(userId);
        assertThat(limiter.inFlight(userId)).isEqualTo(1);

        assertThat(limiter.tryAcquire(userId)).isTrue();
        assertThat(limiter.inFlight(userId)).isEqualTo(2);

        limiter.release(userId);
        limiter.release(userId);
        assertThat(limiter.inFlight(userId)).isEqualTo(0);
    }

    @Test
    @DisplayName("Releasing permit for user without active streams does not throw")
    void testReleaseUnacquiredUser() {
        assertThat(limiter.inFlight(userId)).isEqualTo(0);
        limiter.release(userId);
        assertThat(limiter.inFlight(userId)).isEqualTo(0);
    }

    @Test
    @DisplayName("Constructor throws IllegalArgumentException when maxConcurrentStreams is less than 1")
    void testInvalidConstructorParameter() {
        assertThatThrownBy(() -> new ChatConcurrencyLimiter(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be at least 1");

        assertThatThrownBy(() -> new ChatConcurrencyLimiter(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be at least 1");
    }
}
