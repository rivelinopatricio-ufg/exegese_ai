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

import com.google.genai.errors.ApiException;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single entry point for text embeddings (search queries, document chunks and reindexing). It enforces the
 * vector contract of {@code exegese_chunk.embedding}: exactly {@code exegese.embedding.dimensions} finite
 * components with a non-zero magnitude. Any violation, provider failure or missing configuration raises an
 * {@link EmbeddingException}; a placeholder vector is never returned.
 *
 * @author Rivelino Patrício
 */
@Service
public class EmbeddingService {

    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    /** Wait suggested by the Gemini API in quota errors, e.g. "Please retry in 37.5s" or "retryDelay": "37s". */
    private static final Pattern RETRY_DELAY_PATTERN = Pattern.compile(
            "(?i)(?:retry in|\"retryDelay\"\\s*:\\s*\")\\s*([0-9]+(?:\\.[0-9]+)?)\\s*s");
    private static final Duration MAX_SUGGESTED_DELAY = Duration.ofHours(1);

    private final EmbeddingModel embeddingModel;
    private final int dimensions;
    private final int batchSize;

    public EmbeddingService(EmbeddingModel embeddingModel,
                            @Value("${exegese.embedding.dimensions:768}") int dimensions,
                            @Value("${exegese.embedding.batch-size:16}") int batchSize) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("exegese.embedding.dimensions must be positive");
        }
        this.embeddingModel = embeddingModel;
        this.dimensions = dimensions;
        this.batchSize = Math.max(1, batchSize);
    }

    /**
     * @return false when no embedding provider credentials are configured
     */
    public boolean isConfigured() {
        return !(embeddingModel instanceof UnconfiguredEmbeddingModel);
    }

    /**
     * @return The expected vector dimension (the size of the pgvector column)
     */
    public int dimensions() {
        return dimensions;
    }

    /**
     * @return Maximum number of texts sent to the provider in a single request
     */
    public int batchSize() {
        return batchSize;
    }

    /**
     * Embeds a single text.
     *
     * @param text Non-blank text
     * @return A validated vector
     * @throws EmbeddingException when the embedding cannot be produced or is invalid
     */
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    /**
     * Embeds several texts, sending them to the provider in batches of {@link #batchSize()}.
     *
     * @param texts Non-blank texts
     * @return One validated vector per text, in the same order
     * @throws EmbeddingException when any embedding cannot be produced or is invalid
     */
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new EmbeddingException("Cannot embed a blank text");
            }
        }
        if (!isConfigured()) {
            throw EmbeddingException.notConfigured();
        }

        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + batchSize));
            List<float[]> batchVectors = callProvider(batch);
            if (batchVectors == null || batchVectors.size() != batch.size()) {
                throw new EmbeddingException("Embedding provider returned " + (batchVectors == null ? 0 : batchVectors.size())
                        + " vectors for " + batch.size() + " texts");
            }
            for (float[] vector : batchVectors) {
                validate(vector);
                vectors.add(vector);
            }
        }
        return vectors;
    }

    private List<float[]> callProvider(List<String> batch) {
        try {
            return embeddingModel.embed(batch);
        } catch (EmbeddingException e) {
            throw e;
        } catch (RuntimeException e) {
            ApiException apiException = findApiException(e);
            if (apiException != null && apiException.code() == HTTP_TOO_MANY_REQUESTS) {
                throw EmbeddingException.rateLimited("Embedding provider quota exceeded (" + describe(e) + ")", e,
                        suggestedRetryDelay(apiException.message()));
            }
            throw new EmbeddingException("Embedding provider call failed (" + describe(e) + ")", e);
        }
    }

    /**
     * Extracts the wait suggested by the provider from a quota error message (only the number is kept; the
     * message itself is never propagated).
     *
     * @param providerMessage Provider error message, may be null
     * @return The suggested wait, capped at one hour, or null when the message has none
     */
    static Duration suggestedRetryDelay(String providerMessage) {
        if (providerMessage == null) {
            return null;
        }
        Matcher matcher = RETRY_DELAY_PATTERN.matcher(providerMessage);
        if (!matcher.find()) {
            return null;
        }
        try {
            long millis = (long) Math.ceil(Double.parseDouble(matcher.group(1)) * 1000);
            Duration delay = Duration.ofMillis(Math.max(0, millis));
            return delay.compareTo(MAX_SUGGESTED_DELAY) > 0 ? MAX_SUGGESTED_DELAY : delay;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ApiException findApiException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ApiException apiException) {
                return apiException;
            }
            if (current.getCause() == current) {
                return null;
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * Checks the vector contract: expected dimension, finite components and non-zero magnitude.
     *
     * @param vector Vector to check
     * @throws EmbeddingException when the vector violates the contract
     */
    public void validate(float[] vector) {
        if (vector == null || vector.length != dimensions) {
            throw new EmbeddingException("Embedding dimension mismatch: expected " + dimensions + ", got "
                    + (vector == null ? 0 : vector.length));
        }
        boolean nonZero = false;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new EmbeddingException("Embedding contains non-finite values");
            }
            if (value != 0.0f) {
                nonZero = true;
            }
        }
        if (!nonZero) {
            throw new EmbeddingException("Embedding has zero magnitude");
        }
    }

    /**
     * Text embedded for a chunk: its title (when present) followed by its content. Used by both ingestion
     * and reindexing so that stored vectors are always comparable.
     *
     * @param title Chunk title, may be null
     * @param content Chunk content
     * @return Text to embed
     */
    public static String chunkEmbeddingText(String title, String content) {
        String body = content != null ? content.trim() : "";
        if (title == null || title.isBlank()) {
            return body;
        }
        return title.trim() + "\n" + body;
    }

    /**
     * Formats a vector as a pgvector literal, e.g. {@code [0.1,0.2]}, for {@code cast(? as vector)}.
     *
     * @param vector Vector to format
     * @return pgvector text representation
     */
    public static String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * Short, safe description of a provider failure: exception type and HTTP status only (no response body).
     */
    private static String describe(RuntimeException e) {
        ApiException apiException = findApiException(e);
        if (apiException != null) {
            return apiException.getClass().getSimpleName() + ", HTTP " + apiException.code();
        }
        return e.getClass().getSimpleName();
    }
}
