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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.OptionalDouble;

/**
 * Guard service implementing the Zero Hallucination policy by enforcing evidence threshold validation.
 * <p>
 * The retrieved context is considered grounded only when at least one candidate carries real evidence:
 * a vector match whose cosine similarity reaches {@code exegese.similarity-threshold}, a full-text match
 * containing every query term, or a lexical term-overlap score of at least {@code exegese.min-lexical-score}.
 * The fused RRF score alone is never evidence: it is positive for any candidate, however weak.
 *
 * @author Rivelino Patrício
 */
@Service
public class AntiHallucinationGuard {

    public static final String CANONICAL_REFUSAL_MESSAGE = "Essa informação não consta nos documentos dos assuntos selecionados.";

    private final double similarityThreshold;
    private final double minLexicalScore;

    public AntiHallucinationGuard(@Value("${exegese.similarity-threshold:0.65}") double similarityThreshold,
                                  @Value("${exegese.min-lexical-score:4.0}") double minLexicalScore) {
        this.similarityThreshold = similarityThreshold;
        this.minLexicalScore = minLexicalScore;
    }

    /**
     * Evaluates retrieved candidates against the grounded evidence threshold.
     *
     * @param candidates Retrieved search result chunks
     * @return true if candidates satisfy grounding criteria, false otherwise
     */
    public boolean isGrounded(List<SearchResultChunk> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        return candidates.stream().anyMatch(this::hasEvidence);
    }

    private boolean hasEvidence(SearchResultChunk candidate) {
        Double similarity = candidate.vectorSimilarity();
        if (similarity != null && !similarity.isNaN() && similarity >= similarityThreshold) {
            return true;
        }
        return candidate.fullTextMatch() || candidate.lexicalScore() >= minLexicalScore;
    }

    /**
     * Highest vector similarity among the candidates, for diagnostics.
     *
     * @param candidates Retrieved search result chunks
     * @return The best cosine similarity, or empty when no candidate came from vector search
     */
    public OptionalDouble bestSimilarity(List<SearchResultChunk> candidates) {
        if (candidates == null) {
            return OptionalDouble.empty();
        }
        return candidates.stream()
                .map(SearchResultChunk::vectorSimilarity)
                .filter(s -> s != null && !s.isNaN())
                .mapToDouble(Double::doubleValue)
                .max();
    }

    public String getRefusalMessage() {
        return CANONICAL_REFUSAL_MESSAGE;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public double getMinLexicalScore() {
        return minLexicalScore;
    }
}
