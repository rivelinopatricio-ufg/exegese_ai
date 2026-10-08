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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Limits how many chat answers a single user may have in flight at the same time (pending stream
 * tickets included). Entries exist only while a user holds at least one permit, so the map stays bounded
 * by the number of active streams.
 *
 * @author Rivelino Patrício
 */
@Component
public class ChatConcurrencyLimiter {

    private final int maxConcurrentStreams;
    private final ConcurrentMap<UUID, Integer> inFlight = new ConcurrentHashMap<>();

    public ChatConcurrencyLimiter(@Value("${exegese.chat.max-concurrent-streams-per-user:2}") int maxConcurrentStreams) {
        if (maxConcurrentStreams < 1) {
            throw new IllegalArgumentException("exegese.chat.max-concurrent-streams-per-user must be at least 1");
        }
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    /**
     * Takes a permit for the user when one is available.
     *
     * @param userId Identifier of the user
     * @return true when the permit was granted; the caller must then call {@link #release(UUID)} exactly once
     */
    public boolean tryAcquire(UUID userId) {
        boolean[] acquired = {false};
        inFlight.compute(userId, (key, current) -> {
            int count = current == null ? 0 : current;
            if (count >= maxConcurrentStreams) {
                return current;
            }
            acquired[0] = true;
            return count + 1;
        });
        return acquired[0];
    }

    /**
     * Returns a permit previously granted by {@link #tryAcquire(UUID)}.
     *
     * @param userId Identifier of the user
     */
    public void release(UUID userId) {
        inFlight.computeIfPresent(userId, (key, current) -> current <= 1 ? null : current - 1);
    }

    /**
     * Number of permits currently held by the user.
     *
     * @param userId Identifier of the user
     * @return Permits in use (0 when none)
     */
    public int inFlight(UUID userId) {
        return inFlight.getOrDefault(userId, 0);
    }

    public int getMaxConcurrentStreams() {
        return maxConcurrentStreams;
    }
}
