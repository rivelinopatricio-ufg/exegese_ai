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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import br.org.rivelino.exegese_ai.domain.entity.ChatMessage;
import br.org.rivelino.exegese_ai.domain.entity.ChatSession;

/**
 * Unit tests for {@link QueryRewritingService} validating anaphora resolution and topic extraction.
 *
 * @author Rivelino Patrício
 */
class QueryRewritingServiceTest {

    private QueryRewritingService service;
    private ChatSession session;

    @BeforeEach
    void setUp() {
        service = new QueryRewritingService();
        session = new ChatSession();
    }

    @Test
    @DisplayName("Null or blank query returns empty string")
    void testNullOrBlankQueryReturnsEmpty() {
        assertThat(service.rewriteQuery(null, Collections.emptyList())).isEqualTo("");
        assertThat(service.rewriteQuery("", Collections.emptyList())).isEqualTo("");
        assertThat(service.rewriteQuery("   ", Collections.emptyList())).isEqualTo("");
    }

    @Test
    @DisplayName("Standalone query without history is trimmed and returned as-is")
    void testStandaloneQueryWithoutHistory() {
        String query = "  Qual é o limite de isenção do IRPF 2026?  ";
        assertThat(service.rewriteQuery(query, null)).isEqualTo("Qual é o limite de isenção do IRPF 2026?");
        assertThat(service.rewriteQuery(query, Collections.emptyList())).isEqualTo("Qual é o limite de isenção do IRPF 2026?");
    }

    @Test
    @DisplayName("Standalone query with history but without anaphoric patterns is returned as-is")
    void testStandaloneQueryWithHistory() {
        ChatMessage msg1 = new ChatMessage(session, "USER", "O que é rendimento tributável?");
        ChatMessage msg2 = new ChatMessage(session, "ASSISTANT", "Rendimento tributável é...");

        String current = "Quais são as deduções permitidas?";
        assertThat(service.rewriteQuery(current, List.of(msg1, msg2))).isEqualTo("Quais são as deduções permitidas?");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "E se for aposentado?",
        "E no caso de dependente?",
        "E quem tem mais de 65 anos?",
        "E qual o valor?",
        "E quanto ao MEI?",
        "E onde declaro?",
        "E como comprovar?",
        "E quando vence o prazo?",
        "E sobre rendimentos no exterior?",
        "E para dependente estudante?",
        "Nesse caso há isenção?",
        "Dessa forma como fica?",
        "Disso decorre multa?"
    })
    @DisplayName("Anaphoric queries trigger topic prepending from recent user question")
    void testAnaphoricQueries(String anaphoricQuery) {
        ChatMessage msg1 = new ChatMessage(session, "USER", "Como declarar a venda de um imóvel residencial?");
        ChatMessage msg2 = new ChatMessage(session, "ASSISTANT", "A venda de imóvel residencial...");

        String rewritten = service.rewriteQuery(anaphoricQuery, List.of(msg1, msg2));

        assertThat(rewritten).startsWith("Como declarar a venda de um imóvel residencial — ");
        assertThat(rewritten).endsWith(anaphoricQuery);
    }

    @Test
    @DisplayName("Truncates prior user topic if it exceeds 60 characters and strips punctuation")
    void testTopicTruncationAndPunctuationStripping() {
        String longQuestion = "Como fazer a declaração de ajuste anual do imposto de renda no caso de ter recebido bens no exterior com valor elevado?";
        ChatMessage msg1 = new ChatMessage(session, "USER", longQuestion);
        ChatMessage msg2 = new ChatMessage(session, "ASSISTANT", "Resposta...");

        String rewritten = service.rewriteQuery("E se for em euro?", List.of(msg1, msg2));

        String expectedTopic = longQuestion.replaceAll("[?!.,;:]", "").trim().substring(0, 60);
        assertThat(rewritten).isEqualTo(expectedTopic + " — E se for em euro?");
    }

    @Test
    @DisplayName("History with only ASSISTANT messages does not trigger anaphora rewrite")
    void testHistoryWithOnlyAssistantMessages() {
        ChatMessage msg1 = new ChatMessage(session, "ASSISTANT", "Olá! Como posso ajudar com a declaração do IRPF?");
        String rewritten = service.rewriteQuery("E se for atrasado?", List.of(msg1));

        assertThat(rewritten).isEqualTo("E se for atrasado?");
    }
}
