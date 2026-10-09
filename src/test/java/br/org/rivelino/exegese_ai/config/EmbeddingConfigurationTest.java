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
import br.org.rivelino.exegese_ai.service.UnconfiguredEmbeddingModel;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmbeddingConfiguration} and {@link UnconfiguredEmbeddingModel} placeholder behaviors.
 *
 * @author Rivelino Patrício
 */
class EmbeddingConfigurationTest {

    @Test
    @DisplayName("embeddingModel returns UnconfiguredEmbeddingModel when API key is blank")
    void testEmbeddingModelUnconfigured() {
        EmbeddingConfiguration config = new EmbeddingConfiguration();
        @SuppressWarnings("unchecked")
        ObjectProvider<ObservationRegistry> obsProvider = mock(ObjectProvider.class);

        EmbeddingModel model = config.embeddingModel("", "gemini-embedding-001", 768, obsProvider);

        assertThat(model).isInstanceOf(UnconfiguredEmbeddingModel.class);
        assertThat(model.dimensions()).isEqualTo(768);
    }

    @Test
    @DisplayName("embeddingModel creates GoogleGenAiTextEmbeddingModel when API key is provided")
    void testEmbeddingModelConfigured() {
        EmbeddingConfiguration config = new EmbeddingConfiguration();
        @SuppressWarnings("unchecked")
        ObjectProvider<ObservationRegistry> obsProvider = mock(ObjectProvider.class);
        when(obsProvider.getIfUnique(any())).thenReturn(ObservationRegistry.NOOP);

        EmbeddingModel model = config.embeddingModel("valid-gemini-key", "gemini-embedding-001", 768, obsProvider);

        assertThat(model).isInstanceOf(GoogleGenAiTextEmbeddingModel.class);
    }

    @Test
    @DisplayName("UnconfiguredEmbeddingModel fails fast with EmbeddingException when invoked")
    void testUnconfiguredEmbeddingModelFailsFast() {
        UnconfiguredEmbeddingModel model = new UnconfiguredEmbeddingModel(768);

        assertThat(model.dimensions()).isEqualTo(768);

        assertThatThrownBy(() -> model.call(new EmbeddingRequest(List.of("text"), null)))
                .isInstanceOf(EmbeddingException.class)
                .matches(e -> ((EmbeddingException) e).isNotConfigured());

        assertThatThrownBy(() -> model.embed(new Document("text")))
                .isInstanceOf(EmbeddingException.class)
                .matches(e -> ((EmbeddingException) e).isNotConfigured());
    }
}
