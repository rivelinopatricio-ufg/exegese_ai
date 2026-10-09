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

import java.time.Duration;

/**
 * Raised when a text embedding cannot be produced: no embedding provider is configured
 * ({@code GEMINI_API_KEY} empty), the provider call failed, or the returned vector is unusable (wrong
 * dimension, zero magnitude or non-finite values). Callers must never replace a failed embedding with
 * a placeholder vector.
 * <p>
 * The message is built by the application and never contains provider response bodies or key material.
 * A provider refusal for exceeded quota (HTTP 429) is flagged by {@link #isRateLimited()}, with the wait
 * suggested by the provider when it gives one, so that background jobs can pause instead of failing.
 *
 * @author Rivelino Patrício
 */
public class EmbeddingException extends RuntimeException {

    private final boolean notConfigured;
    private final boolean rateLimited;
    private final Duration retryAfter;

    public EmbeddingException(String message) {
        this(message, null, false, false, null);
    }

    public EmbeddingException(String message, Throwable cause) {
        this(message, cause, false, false, null);
    }

    private EmbeddingException(String message, Throwable cause, boolean notConfigured, boolean rateLimited,
                               Duration retryAfter) {
        super(message, cause);
        this.notConfigured = notConfigured;
        this.rateLimited = rateLimited;
        this.retryAfter = retryAfter;
    }

    /**
     * Creates the exception signalling that the provider refused the call because a quota was exceeded.
     *
     * @param message Application-built message (no provider body)
     * @param cause Provider exception
     * @param retryAfter Wait suggested by the provider, or null when it gave none
     * @return A new exception flagged as "rate limited"
     */
    public static EmbeddingException rateLimited(String message, Throwable cause, Duration retryAfter) {
        return new EmbeddingException(message, cause, false, true, retryAfter);
    }

    /**
     * Creates the exception signalling that no embedding provider credentials are configured.
     *
     * @return A new exception flagged as "not configured"
     */
    public static EmbeddingException notConfigured() {
        return new EmbeddingException("Embedding provider is not configured (GEMINI_API_KEY is empty)", null, true, false, null);
    }

    /**
     * @return true when the failure is caused by missing provider configuration rather than by a call error
     */
    public boolean isNotConfigured() {
        return notConfigured;
    }

    /**
     * @return true when the provider refused the call because a quota was exceeded (HTTP 429)
     */
    public boolean isRateLimited() {
        return rateLimited;
    }

    /**
     * @return Wait suggested by the provider before retrying, or null when unknown
     */
    public Duration retryAfter() {
        return retryAfter;
    }
}
