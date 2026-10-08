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

import br.org.rivelino.exegese_ai.domain.dto.ChatStreamTicket;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and redeems the single-use tickets of the two-step chat flow: {@code POST /api/chat/messages}
 * (CSRF protected) issues a ticket, and {@code GET /api/chat/stream/{streamId}} redeems it exactly once.
 * <p>
 * Each ticket holds one of the user's {@link ChatConcurrencyLimiter} permits. The permit is handed over to
 * the stream when the ticket is redeemed, or returned automatically when the ticket expires unused.
 *
 * @author Rivelino Patrício
 */
@Service
public class ChatStreamTicketService {

    private static final int TICKET_BYTES = 24;

    private final SecureRandom random = new SecureRandom();
    private final ChatConcurrencyLimiter concurrencyLimiter;
    private final Cache<String, ChatStreamTicket> tickets;

    public ChatStreamTicketService(ChatConcurrencyLimiter concurrencyLimiter,
                                   @Value("${exegese.chat.stream-ticket-ttl:2m}") Duration ticketTtl) {
        this.concurrencyLimiter = concurrencyLimiter;
        this.tickets = Caffeine.newBuilder()
                .expireAfterWrite(ticketTtl)
                .maximumSize(100_000)
                .scheduler(Scheduler.systemScheduler())
                .removalListener((String key, ChatStreamTicket ticket, RemovalCause cause) -> {
                    // Expired or evicted before being redeemed: give the permit back
                    if (ticket != null && cause.wasEvicted()) {
                        concurrencyLimiter.release(ticket.userId());
                    }
                })
                .build();
    }

    /**
     * Issues a ticket when the user still has a free concurrency permit.
     *
     * @param ticket Question accepted for streaming
     * @return The opaque stream identifier, or empty when the user already has too many answers in flight
     */
    public Optional<String> issue(ChatStreamTicket ticket) {
        if (!concurrencyLimiter.tryAcquire(ticket.userId())) {
            return Optional.empty();
        }
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        String streamId = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(streamId, ticket);
        return Optional.of(streamId);
    }

    /**
     * Redeems a ticket. A ticket of another user is left untouched and reported as missing.
     * On success the caller owns the ticket's concurrency permit and must release it when the stream ends.
     *
     * @param streamId Identifier returned by {@link #issue(ChatStreamTicket)}
     * @param userId Identifier of the authenticated user
     * @return The ticket, or empty when it is unknown, expired, already used or owned by someone else
     */
    public Optional<ChatStreamTicket> redeem(String streamId, UUID userId) {
        if (streamId == null || userId == null) {
            return Optional.empty();
        }
        ChatStreamTicket[] redeemed = new ChatStreamTicket[1];
        tickets.asMap().computeIfPresent(streamId, (key, ticket) -> {
            if (!ticket.userId().equals(userId)) {
                return ticket;
            }
            redeemed[0] = ticket;
            return null;
        });
        return Optional.ofNullable(redeemed[0]);
    }
}
