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

import br.org.rivelino.exegese_ai.domain.entity.ChatMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Service for contextual query rewriting and anaphora resolution in multi-turn dialogues.
 *
 * @author Rivelino Patrício
 */
@Service
public class QueryRewritingService {

    private static final Pattern ANAPHORA_PATTERN = Pattern.compile(
            "^(?:e\\s+(?:se|no\\s+caso|quem|qual|quanto|onde|como|quando)|e\\s+sobre|e\\s+para|nesse\\s+caso|dessa\\s+forma|disso)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Resolves conversational references into a self-contained query for hybrid search.
     *
     * @param currentQuery The latest query submitted by the user
     * @param history Prior message turns in the current chat session
     * @return Disambiguated, standalone search query
     */
    public String rewriteQuery(String currentQuery, List<ChatMessage> history) {
        if (currentQuery == null || currentQuery.isBlank()) {
            return "";
        }
        String trimmed = currentQuery.trim();

        if (history == null || history.isEmpty()) {
            return trimmed;
        }

        if (ANAPHORA_PATTERN.matcher(trimmed).find()) {
            // Find most recent user question in history
            for (int i = history.size() - 1; i >= 0; i--) {
                ChatMessage msg = history.get(i);
                if ("USER".equalsIgnoreCase(msg.getRole()) && msg.getContent() != null && !msg.getContent().isBlank()) {
                    String previousTopic = extractCoreTopic(msg.getContent());
                    return previousTopic + " — " + trimmed;
                }
            }
        }

        return trimmed;
    }

    private String extractCoreTopic(String message) {
        String cleaned = message.replaceAll("[?!.,;:]", "").trim();
        if (cleaned.length() > 60) {
            return cleaned.substring(0, 60);
        }
        return cleaned;
    }
}
