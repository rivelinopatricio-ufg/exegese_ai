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

import br.org.rivelino.exegese_ai.domain.dto.RawChunk;
import br.org.rivelino.exegese_ai.domain.enums.SegmentationStrategyType;
import br.org.rivelino.exegese_ai.service.CryptoService;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * General recursive paragraph segmentation strategy with character-level windowing and overlap.
 *
 * @author Rivelino Patrício
 */
@Component
public class GeneralSegmentationStrategy implements SegmentationStrategy {

    private static final int TARGET_CHUNK_SIZE = 1000;
    private static final int OVERLAP_SIZE = 150; // ~15% overlap
    private final CryptoService cryptoService;

    public GeneralSegmentationStrategy(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    @Override
    public SegmentationStrategyType getType() {
        return SegmentationStrategyType.RECURSIVE;
    }

    @Override
    public List<RawChunk> segment(String fullText, Map<Integer, String> pageMap) {
        if (fullText == null || fullText.isBlank()) {
            return Collections.emptyList();
        }

        List<RawChunk> chunks = new ArrayList<>();
        String normalized = fullText.trim();
        int length = normalized.length();

        int start = 0;
        int sequenceNumber = 1;

        while (start < length) {
            int end = Math.min(start + TARGET_CHUNK_SIZE, length);

            // Attempt to break at paragraph or sentence boundary if not at the very end
            if (end < length) {
                int boundary = findNaturalBreak(normalized, start, end);
                if (boundary > start + (TARGET_CHUNK_SIZE / 2)) {
                    end = boundary;
                }
            }

            String content = normalized.substring(start, end).trim();
            if (!content.isEmpty()) {
                int pageNumber = resolvePageNumber(content, pageMap);
                String hash = cryptoService.sha256(content);

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("page", pageNumber);
                metadata.put("strategy", SegmentationStrategyType.RECURSIVE.name());
                metadata.put("charStart", start);
                metadata.put("charEnd", end);

                String title = "Seção " + sequenceNumber;
                chunks.add(new RawChunk(sequenceNumber++, title, content, hash, pageNumber, metadata));
            }

            if (end >= length) {
                break;
            }

            // Move forward with overlap
            int nextStart = end - OVERLAP_SIZE;
            if (nextStart <= start) {
                nextStart = end;
            }
            start = nextStart;
        }

        return chunks;
    }

    private int findNaturalBreak(String text, int start, int maxEnd) {
        // Look backwards from maxEnd for paragraph break, newline or sentence boundary
        for (int i = maxEnd; i > start + (TARGET_CHUNK_SIZE / 2); i--) {
            char c = text.charAt(i - 1);
            if (c == '\n') {
                return i;
            }
            if (c == '.' || c == ';' || c == '!') {
                return i;
            }
        }
        return maxEnd;
    }

    private int resolvePageNumber(String contentSnippet, Map<Integer, String> pageMap) {
        if (pageMap == null || pageMap.isEmpty()) return 1;
        String sample = contentSnippet.length() > 50 ? contentSnippet.substring(0, 50) : contentSnippet;

        for (Map.Entry<Integer, String> entry : pageMap.entrySet()) {
            if (entry.getValue().contains(sample)) {
                return entry.getKey();
            }
        }
        return 1;
    }
}
