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
package br.org.rivelino.exegese_ai.security;

import br.org.rivelino.exegese_ai.domain.enums.UserRole;
import br.org.rivelino.exegese_ai.repository.ExegeseUserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Short-lived cache of each account's current role and active flag, read on every authenticated
 * request by {@link AccountStatusFilter} without hitting the database each time.
 * Entries are evicted as soon as an administrator changes a user's role or status.
 *
 * @author Rivelino Patrício
 */
@Component
public class UserAccountStatusCache {

    /**
     * Snapshot of the persisted account state that matters for authorization.
     */
    public record AccountStatus(UUID userId, UserRole role, boolean active) {}

    private final ExegeseUserRepository userRepository;
    private final Cache<String, Optional<AccountStatus>> cache;

    public UserAccountStatusCache(ExegeseUserRepository userRepository,
                                  @Value("${exegese.security.account-status-cache-ttl:30s}") Duration ttl) {
        this.userRepository = userRepository;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(10_000)
                .build();
    }

    /**
     * Returns the current status of the account with the given e-mail (empty when no local account exists).
     *
     * @param email Account e-mail as stored in the database
     * @return Account status snapshot, possibly served from the cache
     */
    public Optional<AccountStatus> lookup(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return cache.get(email, key -> userRepository.findByEmail(key)
                .map(user -> new AccountStatus(user.getId(), user.getRole(), user.isActive())));
    }

    /**
     * Evicts the cached status of an account now and, when called inside a transaction, again after it
     * commits, so that a concurrent request cannot re-cache the state being replaced.
     *
     * @param email Account e-mail as stored in the database
     */
    public void evict(String email) {
        if (email == null) {
            return;
        }
        cache.invalidate(email);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    cache.invalidate(email);
                }
            });
        }
    }
}
