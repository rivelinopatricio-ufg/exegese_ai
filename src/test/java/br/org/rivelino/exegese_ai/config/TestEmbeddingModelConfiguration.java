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

import br.org.rivelino.exegese_ai.service.EmbeddingException;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Test configuration keeping the test suite hermetic: replaces the Gemini embedding model with a
 * deterministic, offline {@link FakeEmbeddingModel}. No test ever calls the real embedding API.
 *
 * @author Rivelino Patrício
 */
@Configuration
public class TestEmbeddingModelConfiguration {

    @Bean
    @Primary
    public FakeEmbeddingModel fakeEmbeddingModel() {
        return new FakeEmbeddingModel(768);
    }

    /**
     * Deterministic bag-of-words embedding: every accent-free word is hashed into one of the dimensions and
     * the vector is L2-normalized, so texts sharing words have a high cosine similarity. A test can make it
     * behave as an unconfigured provider with {@link #setUnavailable(boolean)} (always restore it).
     */
    public static class FakeEmbeddingModel implements EmbeddingModel {

        private final int dimensions;
        private volatile boolean unavailable;

        public FakeEmbeddingModel(int dimensions) {
            this.dimensions = dimensions;
        }

        public void setUnavailable(boolean unavailable) {
            this.unavailable = unavailable;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            if (unavailable) {
                throw EmbeddingException.notConfigured();
            }
            List<Embedding> embeddings = new ArrayList<>();
            List<String> instructions = request.getInstructions();
            for (int i = 0; i < instructions.size(); i++) {
                embeddings.add(new Embedding(vectorOf(instructions.get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public int dimensions() {
            return dimensions;
        }

        public float[] vectorOf(String text) {
            float[] vector = new float[dimensions];
            String normalized = Normalizer.normalize(text == null ? "" : text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "");
            for (String word : normalized.split("[^a-z0-9]+")) {
                if (word.length() < 3) {
                    continue;
                }
                vector[Math.floorMod(word.hashCode(), dimensions)] += 1.0f;
            }
            double norm = 0.0;
            for (float value : vector) {
                norm += value * value;
            }
            if (norm == 0.0) {
                vector[0] = 1.0f;
                return vector;
            }
            float inverse = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= inverse;
            }
            return vector;
        }
    }
}
