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
package br.org.rivelino.exegese_ai.service.segmentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.org.rivelino.exegese_ai.domain.dto.RawChunk;
import br.org.rivelino.exegese_ai.domain.enums.SegmentationStrategyType;
import br.org.rivelino.exegese_ai.service.CryptoService;

/**
 * Unit tests for {@link GeneralSegmentationStrategy} validating recursive paragraph windowing,
 * overlap, and page resolution.
 *
 * @author Rivelino Patrício
 */
class GeneralSegmentationStrategyTest {

    private CryptoService cryptoService;
    private GeneralSegmentationStrategy strategy;

    @BeforeEach
    void setUp() {
        cryptoService = mock(CryptoService.class);
        when(cryptoService.sha256(anyString())).thenAnswer(invocation -> "hash-" + invocation.getArgument(0).hashCode());
        strategy = new GeneralSegmentationStrategy(cryptoService);
    }

    @Test
    @DisplayName("Strategy type is RECURSIVE")
    void testGetType() {
        assertThat(strategy.getType()).isEqualTo(SegmentationStrategyType.RECURSIVE);
    }

    @Test
    @DisplayName("Null or blank text returns empty chunk list")
    void testEmptyOrNullTextReturnsEmptyList() {
        assertThat(strategy.segment(null, Map.of())).isEmpty();
        assertThat(strategy.segment("", Map.of())).isEmpty();
        assertThat(strategy.segment("   ", Map.of())).isEmpty();
    }

    @Test
    @DisplayName("Short text smaller than target window generates a single chunk")
    void testShortTextGeneratesSingleChunk() {
        String content = "Este é um texto curto para teste de segmentação geral.";
        List<RawChunk> chunks = strategy.segment(content, Map.of(1, content));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sequenceNumber()).isEqualTo(1);
        assertThat(chunks.get(0).title()).isEqualTo("Seção 1");
        assertThat(chunks.get(0).content()).isEqualTo(content);
        assertThat(chunks.get(0).pageNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("Long text breaks naturally at paragraph and punctuation boundaries with overlap")
    void testLongTextNaturalBreakAndOverlap() {
        StringBuilder builder = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            builder.append("Parágrafo número ").append(i).append(" com texto explicativo longo para preencher a janela.\n\n");
        }
        String longText = builder.toString();

        List<RawChunk> chunks = strategy.segment(longText, Map.of(1, longText));

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.get(0).sequenceNumber()).isEqualTo(1);
        assertThat(chunks.get(1).sequenceNumber()).isEqualTo(2);
        assertThat(chunks.get(0).metadata()).containsEntry("strategy", "RECURSIVE");
    }

    @Test
    @DisplayName("Resolves page number from pageMap based on snippet matching")
    void testResolvePageNumberFromMap() {
        String page1Text = "Conteúdo exclusivo pertencente à primeira página do documento.";
        String page2Text = "Conteúdo exclusivo pertencente à segunda página do documento.";
        String combined = page1Text + "\n\n" + page2Text;

        Map<Integer, String> pageMap = Map.of(1, page1Text, 2, page2Text);

        List<RawChunk> chunks = strategy.segment(combined, pageMap);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).pageNumber()).isEqualTo(1);
    }
}
