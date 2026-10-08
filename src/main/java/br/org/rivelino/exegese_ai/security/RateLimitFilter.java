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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.LocaleResolver;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

/**
 * Filter enforcing API rate limiting on {@code /api/**} (20 requests per minute by default) using a
 * Bucket4j token bucket per client.
 * <p>
 * The client key is the authenticated account e-mail when present, otherwise the remote address as
 * resolved by Tomcat's {@code RemoteIpValve} from trusted proxies only. Client-supplied headers such as
 * {@code X-Forwarded-For} are never read here, so they cannot be used to obtain fresh buckets. Buckets live
 * in a bounded Caffeine cache that forgets idle clients, so the memory used is bounded too.
 *
 * @author Rivelino Patrício
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final int requestsPerMinute;
    private final Cache<String, Bucket> buckets;
    private final MessageSource messageSource;
    private final LocaleResolver localeResolver;

    public RateLimitFilter(@Value("${exegese.security.rate-limit.requests-per-minute:20}") int requestsPerMinute,
                           MessageSource messageSource,
                           LocaleResolver localeResolver) {
        this.requestsPerMinute = requestsPerMinute;
        this.messageSource = messageSource;
        this.localeResolver = localeResolver;
        // An idle bucket is full again after one minute, so forgetting it after a few minutes is lossless
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(5))
                .maximumSize(100_000)
                .build();
    }

    private Bucket createNewBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(requestsPerMinute)
                .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();

        // Enforce rate limiting specifically on /api/** endpoints
        if (uri.startsWith(request.getContextPath() + "/api/")) {
            Bucket bucket = buckets.get(resolveClientKey(request), key -> createNewBucket());

            if (!bucket.tryConsume(1)) {
                Locale locale = localeResolver.resolveLocale(request);
                String message = messageSource.getMessage("security.rate_limit.exceeded",
                        new Object[]{String.valueOf(requestsPerMinute)}, locale);
                response.setHeader("Retry-After", "60");
                JsonErrorResponse.write(response, 429, message);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private static String resolveClientKey(HttpServletRequest request) {
        return SecurityContextFacade.extractEmail(SecurityContextHolder.getContext().getAuthentication())
                .map(email -> "user:" + email.toLowerCase(Locale.ROOT))
                .orElseGet(() -> "ip:" + (request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown"));
    }

    /**
     * Resets buckets for testing or maintenance purposes.
     */
    public void reset() {
        buckets.invalidateAll();
    }
}
