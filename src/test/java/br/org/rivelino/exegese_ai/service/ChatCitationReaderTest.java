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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ChatCitationReader}: citation JSON written by the RAG pipeline is read back with the
 * managed (Jackson 3) mapper; absent or corrupt JSON yields no citations instead of an error.
 *
 * @author Rivelino Patrício
 */
class ChatCitationReaderTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final ChatCitationReader reader = new ChatCitationReader(jsonMapper);

    @Test
    @DisplayName("Round trip of the citations JSON array")
    void testRoundTrip() {
        List<CanonicalCitationDTO> citations = List.of(
                new CanonicalCitationDTO("Manual IRPF", "Pergunta 001", 12, 1, null, "Lei 9.250/1995"));

        assertThat(reader.parse(jsonMapper.writeValueAsString(citations))).isEqualTo(citations);
    }

    @Test
    @DisplayName("Blank, null or corrupt JSON gives an empty list")
    void testInvalidInput() {
        assertThat(reader.parse(null)).isEmpty();
        assertThat(reader.parse(" ")).isEmpty();
        assertThat(reader.parse("{not json")).isEmpty();
    }

    @Test
    @DisplayName("Only messages with citations are indexed by message id")
    void testByMessageId() {
        ChatMessage withCitations = new ChatMessage();
        withCitations.setId(UUID.randomUUID());
        withCitations.setCitations("[{\"documentTitle\":\"Doc\",\"chunkTitle\":\"Art. 1\"}]");
        ChatMessage without = new ChatMessage();
        without.setId(UUID.randomUUID());

        Map<UUID, List<CanonicalCitationDTO>> byId = reader.byMessageId(List.of(withCitations, without));

        assertThat(byId).containsOnlyKeys(withCitations.getId());
        assertThat(byId.get(withCitations.getId())).extracting(CanonicalCitationDTO::chunkTitle).containsExactly("Art. 1");
    }
}
