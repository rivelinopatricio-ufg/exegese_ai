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
package br.org.rivelino.exegese_ai.config;

import br.org.rivelino.exegese_ai.service.UnconfiguredEmbeddingModel;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.errors.ServerException;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.embedding.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Text embedding model used for semantic retrieval: Google Gemini {@code gemini-embedding-001} through the
 * Gemini Developer API (API key, not Vertex AI), with the output dimensionality fixed to the
 * {@code VECTOR(768)} column of {@code exegese_chunk}.
 * <p>
 * The model is built here instead of by Spring AI's auto-configuration because the auto-configured
 * connection refuses to start without credentials, while this application must keep running (full-text
 * search only) when {@code GEMINI_API_KEY} is empty. In that case an {@link UnconfiguredEmbeddingModel} is
 * registered, which fails every call fast; zero vectors are never produced.
 *
 * @author Rivelino Patrício
 */
@Configuration
public class EmbeddingConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfiguration.class);

    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    @Bean
    public EmbeddingModel embeddingModel(@Value("${exegese.embedding.api-key:}") String apiKey,
                                         @Value("${exegese.embedding-model:gemini-embedding-001}") String modelName,
                                         @Value("${exegese.embedding.dimensions:768}") int dimensions,
                                         ObjectProvider<ObservationRegistry> observationRegistry) {
        if (!StringUtils.hasText(apiKey)) {
            log.warn("GEMINI_API_KEY is not configured: semantic search is disabled (answers use full-text retrieval "
                    + "only) and document ingestion / embedding reindexing fail until a key is set");
            return new UnconfiguredEmbeddingModel(dimensions);
        }

        GoogleGenAiEmbeddingConnectionDetails connectionDetails = GoogleGenAiEmbeddingConnectionDetails.builder()
                .apiKey(apiKey.trim())
                .build();
        GoogleGenAiTextEmbeddingOptions options = GoogleGenAiTextEmbeddingOptions.builder()
                .model(modelName)
                .dimensions(dimensions)
                .build();

        log.info("Gemini text embeddings enabled: model {} with {} output dimensions", modelName, dimensions);
        return new GoogleGenAiTextEmbeddingModel(connectionDetails, options, embeddingRetryTemplate(),
                observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP));
    }

    /**
     * Short retry policy: transient provider failures (HTTP 5xx, 429, I/O) are retried twice with backoff;
     * authentication or request errors fail at once so that searches are not delayed.
     */
    private static RetryTemplate embeddingRetryTemplate() {
        return new RetryTemplate(RetryPolicy.builder()
                .maxRetries(2)
                .delay(Duration.ofMillis(500))
                .multiplier(2.0)
                .maxDelay(Duration.ofSeconds(4))
                .predicate(EmbeddingConfiguration::isTransient)
                .build());
    }

    private static boolean isTransient(Throwable error) {
        return error instanceof TransientAiException
                || error instanceof ServerException
                || error instanceof GenAiIOException
                || (error instanceof ClientException clientError
                        && clientError.code() == HTTP_TOO_MANY_REQUESTS);
    }
}
