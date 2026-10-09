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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import br.org.rivelino.exegese_ai.domain.enums.SegmentationStrategyType;

/**
 * Unit tests for {@link SegmentationStrategyFactory} validating strategy resolution and fallback.
 *
 * @author Rivelino Patrício
 */
class SegmentationStrategyFactoryTest {

    private SegmentationStrategy qaStrategy;
    private SegmentationStrategy legalStrategy;
    private SegmentationStrategy recursiveStrategy;
    private SegmentationStrategyFactory factory;

    @BeforeEach
    void setUp() {
        qaStrategy = mock(SegmentationStrategy.class);
        when(qaStrategy.getType()).thenReturn(SegmentationStrategyType.STRUCTURED_QA);

        legalStrategy = mock(SegmentationStrategy.class);
        when(legalStrategy.getType()).thenReturn(SegmentationStrategyType.LEGAL_SECTION);

        recursiveStrategy = mock(SegmentationStrategy.class);
        when(recursiveStrategy.getType()).thenReturn(SegmentationStrategyType.RECURSIVE);

        factory = new SegmentationStrategyFactory(List.of(qaStrategy, legalStrategy, recursiveStrategy));
    }

    @Test
    @DisplayName("Resolves STRUCTURED_QA strategy correctly")
    void testResolveStructuredQa() {
        assertThat(factory.getStrategy(SegmentationStrategyType.STRUCTURED_QA)).isSameAs(qaStrategy);
    }

    @Test
    @DisplayName("Resolves LEGAL_SECTION strategy correctly")
    void testResolveLegalSection() {
        assertThat(factory.getStrategy(SegmentationStrategyType.LEGAL_SECTION)).isSameAs(legalStrategy);
    }

    @Test
    @DisplayName("Resolves RECURSIVE strategy correctly")
    void testResolveRecursive() {
        assertThat(factory.getStrategy(SegmentationStrategyType.RECURSIVE)).isSameAs(recursiveStrategy);
    }

    @Test
    @DisplayName("Falls back to RECURSIVE strategy when type is null or missing")
    void testFallbackToRecursive() {
        assertThat(factory.getStrategy(null)).isSameAs(recursiveStrategy);
    }
}
