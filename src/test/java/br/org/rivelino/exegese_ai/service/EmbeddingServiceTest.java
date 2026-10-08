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

import br.org.rivelino.exegese_ai.config.TestEmbeddingModelConfiguration.FakeEmbeddingModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link EmbeddingService}: dimension, magnitude and count validation, batching, fail-fast
 * behavior without a configured provider, and the pgvector literal format.
 *
 * @author Rivelino Patrício
 */
class EmbeddingServiceTest {

    /** Embedding model answering with the vectors produced by a function, recording every request size. */
    private static final class ScriptedModel implements EmbeddingModel {
        private final Function<String, float[]> vectors;
        private final List<Integer> requestSizes = new ArrayList<>();

        ScriptedModel(Function<String, float[]> vectors) {
            this.vectors = vectors;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            requestSizes.add(request.getInstructions().size());
            List<Embedding> result = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                result.add(new Embedding(vectors.apply(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(result);
        }

        @Override
        public float[] embed(Document document) {
            return vectors.apply(document.getText());
        }
    }

    private static float[] unit(int dimensions) {
        float[] v = new float[dimensions];
        v[0] = 1.0f;
        return v;
    }

    @Test
    @DisplayName("Valid 768-dimension vectors are returned in order, sent in batches")
    void testBatchedEmbeddings() {
        ScriptedModel model = new ScriptedModel(text -> new FakeEmbeddingModel(768).vectorOf(text));
        EmbeddingService service = new EmbeddingService(model, 768, 2);

        List<float[]> vectors = service.embedAll(List.of("alfa beta", "gama delta", "épsilon zeta"));

        assertThat(vectors).hasSize(3);
        assertThat(vectors).allSatisfy(v -> assertThat(v).hasSize(768));
        assertThat(model.requestSizes).containsExactly(2, 1);
        assertThat(service.isConfigured()).isTrue();
    }

    @Test
    @DisplayName("A dimension mismatch is an error (never padded or truncated)")
    void testDimensionMismatchRejected() {
        EmbeddingService service = new EmbeddingService(new ScriptedModel(text -> unit(1536)), 768, 32);

        assertThatThrownBy(() -> service.embed("texto"))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("expected 768, got 1536");
    }

    @Test
    @DisplayName("Zero-magnitude and non-finite vectors are rejected")
    void testInvalidVectorsRejected() {
        EmbeddingService zero = new EmbeddingService(new ScriptedModel(text -> new float[768]), 768, 32);
        assertThatThrownBy(() -> zero.embed("texto")).isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("zero magnitude");

        EmbeddingService nan = new EmbeddingService(new ScriptedModel(text -> {
            float[] v = unit(768);
            v[3] = Float.NaN;
            return v;
        }), 768, 32);
        assertThatThrownBy(() -> nan.embed("texto")).isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("non-finite");
    }

    @Test
    @DisplayName("Without a configured provider every call fails fast as 'not configured'")
    void testUnconfiguredFailsFast() {
        EmbeddingService service = new EmbeddingService(new UnconfiguredEmbeddingModel(768), 768, 32);

        assertThat(service.isConfigured()).isFalse();
        assertThatThrownBy(() -> service.embed("texto"))
                .isInstanceOfSatisfying(EmbeddingException.class, e -> assertThat(e.isNotConfigured()).isTrue());
    }

    @Test
    @DisplayName("Provider failures are wrapped without exposing the upstream message")
    void testProviderFailureWrapped() {
        EmbeddingService service = new EmbeddingService(new ScriptedModel(text -> {
            throw new IllegalStateException("upstream body with secret details");
        }), 768, 32);

        assertThatThrownBy(() -> service.embed("texto"))
                .isInstanceOfSatisfying(EmbeddingException.class, e -> {
                    assertThat(e.isNotConfigured()).isFalse();
                    assertThat(e.getMessage()).doesNotContain("secret").contains("IllegalStateException");
                });
    }

    @Test
    @DisplayName("Blank texts are refused and an empty input returns no vectors")
    void testBlankInput() {
        EmbeddingService service = new EmbeddingService(new ScriptedModel(text -> unit(768)), 768, 32);

        assertThat(service.embedAll(List.of())).isEmpty();
        assertThatThrownBy(() -> service.embed("  ")).isInstanceOf(EmbeddingException.class);
    }

    @Test
    @DisplayName("Chunk text and pgvector literal helpers")
    void testHelpers() {
        assertThat(EmbeddingService.chunkEmbeddingText("Pergunta 1", " Conteúdo ")).isEqualTo("Pergunta 1\nConteúdo");
        assertThat(EmbeddingService.chunkEmbeddingText(null, "Conteúdo")).isEqualTo("Conteúdo");
        assertThat(EmbeddingService.toPgVector(new float[]{0.5f, -1.0f, 0.0f})).isEqualTo("[0.5,-1.0,0.0]");
    }
}
