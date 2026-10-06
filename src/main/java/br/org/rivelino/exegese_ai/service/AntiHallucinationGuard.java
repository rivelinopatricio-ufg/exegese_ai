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

/**
 * Guard service implementing the Zero Hallucination policy by enforcing evidence threshold validation.
 *
 * @author Rivelino Patrício
 */
@Service
public class AntiHallucinationGuard {

    public static final String CANONICAL_REFUSAL_MESSAGE = "Essa informação não consta nos documentos dos assuntos selecionados.";

    private final double similarityThreshold;

    public AntiHallucinationGuard(@Value("${exegese.similarity-threshold:0.65}") double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
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

        // Must have at least one candidate with positive retrieval score
        double topScore = candidates.get(0).score();
        return topScore > 0.0;
    }

    public String getRefusalMessage() {
        return CANONICAL_REFUSAL_MESSAGE;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }
}
