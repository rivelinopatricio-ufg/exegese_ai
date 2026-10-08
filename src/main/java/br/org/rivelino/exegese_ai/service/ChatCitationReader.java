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

import br.org.rivelino.exegese_ai.domain.dto.CanonicalCitationDTO;
import br.org.rivelino.exegese_ai.domain.entity.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the canonical citations stored as JSON in {@link ChatMessage#getCitations()} with the
 * application-managed JSON mapper (entities never instantiate their own mapper).
 *
 * @author Rivelino Patrício
 */
@Component
public class ChatCitationReader {

    private static final Logger log = LoggerFactory.getLogger(ChatCitationReader.class);
    private static final TypeReference<List<CanonicalCitationDTO>> CITATION_LIST = new TypeReference<>() {};

    private final JsonMapper jsonMapper;

    public ChatCitationReader(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /**
     * Parses a JSON array of citations.
     *
     * @param citationsJson JSON array, may be null or blank
     * @return The citations, or an empty list when absent or unreadable
     */
    public List<CanonicalCitationDTO> parse(String citationsJson) {
        if (citationsJson == null || citationsJson.isBlank()) {
            return List.of();
        }
        try {
            List<CanonicalCitationDTO> citations = jsonMapper.readValue(citationsJson, CITATION_LIST);
            return citations != null ? citations : List.of();
        } catch (JacksonException e) {
            log.debug("Ignoring unreadable citation JSON: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * Parses the citations of every message that has some, keyed by message id (for the chat view).
     *
     * @param messages Messages of a chat session
     * @return Message id to its non-empty citation list
     */
    public Map<UUID, List<CanonicalCitationDTO>> byMessageId(List<ChatMessage> messages) {
        Map<UUID, List<CanonicalCitationDTO>> result = new LinkedHashMap<>();
        for (ChatMessage message : messages) {
            List<CanonicalCitationDTO> citations = parse(message.getCitations());
            if (!citations.isEmpty()) {
                result.put(message.getId(), citations);
            }
        }
        return result;
    }
}
