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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Specialized segmentation strategy partitioning structured Q&A manuals (e.g. IRPF 2026).
 *
 * @author Rivelino Patrício
 */
@Component
public class StructuredQuestionSegmentationStrategy implements SegmentationStrategy {

    private static final Pattern QUESTION_PATTERN = Pattern.compile("(?m)^(\\d{1,4})\\s*[—–-]\\s*(.+)");
    private final CryptoService cryptoService;

    public StructuredQuestionSegmentationStrategy(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    @Override
    public SegmentationStrategyType getType() {
        return SegmentationStrategyType.STRUCTURED_QA;
    }

    @Override
    public List<RawChunk> segment(String fullText, Map<Integer, String> pageMap) {
        if (fullText == null || fullText.isBlank()) {
            return Collections.emptyList();
        }

        List<RawChunk> chunks = new ArrayList<>();
        Matcher matcher = QUESTION_PATTERN.matcher(fullText);

        List<Integer> startPositions = new ArrayList<>();
        List<String> questionNumbers = new ArrayList<>();
        List<String> titles = new ArrayList<>();

        while (matcher.find()) {
            startPositions.add(matcher.start());
            questionNumbers.add(matcher.group(1).trim());
            titles.add(matcher.group(2).trim());
        }

        if (startPositions.isEmpty()) {
            // Fallback: segment by generic paragraphs if no structured questions detected
            return new GeneralSegmentationStrategy(cryptoService).segment(fullText, pageMap);
        }

        for (int i = 0; i < startPositions.size(); i++) {
            int start = startPositions.get(i);
            int end = (i + 1 < startPositions.size()) ? startPositions.get(i + 1) : fullText.length();

            String content = fullText.substring(start, end).trim();
            String questionNum = questionNumbers.get(i);
            String title = "Pergunta " + questionNum + " — " + titles.get(i);

            int pageNumber = resolvePageNumber(content, pageMap);
            String hash = cryptoService.sha256(content);

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("questionNumber", Integer.parseInt(questionNum));
            metadata.put("page", pageNumber);
            metadata.put("strategy", SegmentationStrategyType.STRUCTURED_QA.name());

            chunks.add(new RawChunk(i + 1, title, content, hash, pageNumber, metadata));
        }

        return chunks;
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
