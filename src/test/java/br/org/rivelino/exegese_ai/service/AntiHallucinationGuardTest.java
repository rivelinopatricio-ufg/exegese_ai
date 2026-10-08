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

import br.org.rivelino.exegese_ai.domain.dto.SearchResultChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AntiHallucinationGuard}: grounding requires real evidence (vector similarity at or
 * above {@code exegese.similarity-threshold}, a full-text match of every term, or a lexical score at or above
 * {@code exegese.min-lexical-score}); a positive RRF score alone is never enough.
 *
 * @author Rivelino Patrício
 */
class AntiHallucinationGuardTest {

    private final AntiHallucinationGuard guard = new AntiHallucinationGuard(0.65, 4.0);

    private static SearchResultChunk chunk(Double similarity, double lexicalScore, boolean fullTextMatch) {
        return new SearchResultChunk(UUID.randomUUID(), UUID.randomUUID(), "Doc", "Chunk", "Conteúdo", 1, "{}",
                1.0 / 61, similarity != null ? 1 : Integer.MAX_VALUE, Integer.MAX_VALUE, similarity, lexicalScore, fullTextMatch);
    }

    @Test
    @DisplayName("No candidates is never grounded")
    void testEmptyCandidatesRefused() {
        assertThat(guard.isGrounded(List.of())).isFalse();
        assertThat(guard.isGrounded(null)).isFalse();
    }

    @Test
    @DisplayName("Vector candidates below the similarity threshold are refused despite a positive RRF score")
    void testBelowThresholdRefused() {
        List<SearchResultChunk> candidates = List.of(chunk(0.64, 0.0, false), chunk(0.30, 0.0, false));

        assertThat(candidates.get(0).score()).isPositive();
        assertThat(guard.isGrounded(candidates)).isFalse();
        assertThat(guard.bestSimilarity(candidates)).hasValue(0.64);
    }

    @Test
    @DisplayName("A vector candidate at or above the threshold is grounded")
    void testAtThresholdGrounded() {
        assertThat(guard.isGrounded(List.of(chunk(0.20, 0.0, false), chunk(0.65, 0.0, false)))).isTrue();
        assertThat(guard.isGrounded(List.of(chunk(0.91, 0.0, false)))).isTrue();
    }

    @Test
    @DisplayName("NaN similarity (legacy zero vector) is not evidence")
    void testNaNSimilarityRefused() {
        assertThat(guard.isGrounded(List.of(chunk(Double.NaN, 0.0, false)))).isFalse();
    }

    @Test
    @DisplayName("Lexical evidence grounds the answer: full-text match or lexical score above the minimum")
    void testLexicalEvidence() {
        assertThat(guard.isGrounded(List.of(chunk(0.10, 0.0, true)))).isTrue();
        assertThat(guard.isGrounded(List.of(chunk(null, 4.0, false)))).isTrue();
        assertThat(guard.isGrounded(List.of(chunk(null, 3.5, false)))).isFalse();
        assertThat(guard.isGrounded(List.of(chunk(null, 0.0, false)))).isFalse();
    }

    @Test
    @DisplayName("The configured threshold is honored")
    void testCustomThreshold() {
        AntiHallucinationGuard strict = new AntiHallucinationGuard(0.90, 50.0);
        assertThat(strict.isGrounded(List.of(chunk(0.85, 10.0, false)))).isFalse();
        assertThat(strict.isGrounded(List.of(chunk(0.95, 0.0, false)))).isTrue();
        assertThat(strict.getSimilarityThreshold()).isEqualTo(0.90);
    }
}
