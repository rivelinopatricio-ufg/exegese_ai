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
package br.org.rivelino.exegese_ai;

import br.org.rivelino.exegese_ai.domain.dto.SearchResultChunk;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseChunk;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseDocument;
import br.org.rivelino.exegese_ai.domain.entity.ExegeseSubject;
import br.org.rivelino.exegese_ai.repository.ExegeseChunkRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseDocumentRepository;
import br.org.rivelino.exegese_ai.repository.ExegeseSubjectRepository;
import br.org.rivelino.exegese_ai.service.AntiHallucinationGuard;
import br.org.rivelino.exegese_ai.service.HybridSearchService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Formal Golden Dataset evaluation test validating retrieval accuracy of 20 canonical IRPF 2026 questions
 * and 1 strict out-of-scope refusal control.
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RagQualityEvaluationTest {

    @Autowired
    private HybridSearchService searchService;

    @Autowired
    private AntiHallucinationGuard antiHallucinationGuard;

    @Autowired
    private ExegeseSubjectRepository subjectRepository;

    @Autowired
    private ExegeseDocumentRepository documentRepository;

    @Autowired
    private ExegeseChunkRepository chunkRepository;

    @Autowired
    private EntityManager entityManager;

    private ExegeseSubject irpfSubject;

    @BeforeEach
    void setUpGoldenDataset() {
        irpfSubject = subjectRepository.save(
                new ExegeseSubject("tributario-golden", "Tributário IRPF 2026", "Manual Oficial IRPF 2026")
        );

        ExegeseDocument doc = new ExegeseDocument(
                "Perguntas e Respostas IRPF 2026",
                "IRPF2026.pdf",
                "storage/irpf2026.pdf",
                "hash-golden-dataset-full",
                4751936L,
                "PDF"
        );
        doc.addSubject(irpfSubject);
        doc = documentRepository.save(doc);

        // 20 Official Golden Dataset Chunks
        saveChunk(doc, 1, 1, "Pergunta 001 — Obrigatoriedade de Apresentação", "Está obrigada a apresentar a declaração quem recebeu rendimentos tributáveis acima de R$ 30.639,90.");
        saveChunk(doc, 2, 2, "Pergunta 002 — Pessoas Desobrigadas", "A pessoa física que não se enquadre em nenhuma das hipóteses de obrigatoriedade está dispensada.");
        saveChunk(doc, 3, 10, "Pergunta 010 — Saldo em Poupança Superior a R$ 800 mil", "A posse ou propriedade de bens e direitos, inclusive caderneta de poupança, com valor total superior a R$ 800.000,00 obriga à apresentação.");
        saveChunk(doc, 4, 12, "Pergunta 012 — Teto do Desconto Simplificado", "O desconto simplificado de 20% é limitado a R$ 16.754,34 no exercício de 2026.");
        saveChunk(doc, 5, 21, "Pergunta 021 — Prazo de Entrega IRPF 2026", "A Declaração de Ajuste Anual do IRPF deve ser apresentada até 30 de abril de 2026.");
        saveChunk(doc, 6, 24, "Pergunta 024 — Multa Mínima por Atraso", "A multa mínima por atraso na entrega da declaração sem imposto devido é de R$ 165,74.");
        saveChunk(doc, 7, 61, "Pergunta 061 — Tabela Progressiva Anual 2026", "A tabela progressiva anual estabelece faixas de isenção e alíquotas de 7,5%, 15%, 22,5% e 27,5%.");
        saveChunk(doc, 8, 123, "Pergunta 123 — Dedução por Dependente", "A dedução anual permitida por dependente na apuração da base de cálculo é de R$ 2.275,08.");
        saveChunk(doc, 9, 187, "Pergunta 187 — Previdência Complementar PGBL", "As contribuições para planos de previdência complementar PGBL são dedutíveis até o limite de 12% dos rendimentos tributáveis.");
        saveChunk(doc, 10, 189, "Pergunta 189 — Pensão Especial Ex-Combatente da FEB", "É isenta do imposto sobre a renda a pensão especial concedida a ex-combatente da Força Expedicionária Brasileira.");
        saveChunk(doc, 11, 223, "Pergunta 223 — Não Incidência sobre Pensão Alimentícia ADI 5422", "Em cumprimento à decisão do STF na ADI 5422, os valores recebidos a título de pensão alimentícia não estão sujeitos ao IRPF.");
        saveChunk(doc, 12, 318, "Pergunta 318 — Apostas de Quota Fixa e Bets", "Os prêmios líquidos obtidos em apostas de quota fixa e bets estão sujeitos à tributação exclusiva na fonte à alíquota de 15%.");
        saveChunk(doc, 13, 350, "Pergunta 350 — Dependente Filho Universitário até 24 Anos", "Podem ser considerados dependentes os filhos de até 24 anos que estejam cursando estabelecimento de ensino superior ou escola técnica.");
        saveChunk(doc, 14, 367, "Pergunta 367 — Prótese de Silicone em Cirurgia Médica", "O valor de prótese de silicone é dedutível somente se integrar a conta emitida pelo estabelecimento hospitalar.");
        saveChunk(doc, 15, 381, "Pergunta 381 — Testes de Covid Realizados em Farmácia", "Testes de Covid-19 realizados em farmácia não são dedutíveis como despesas médicas por ausência de previsão legal.");
        saveChunk(doc, 16, 401, "Pergunta 401 — Limite Individual de Despesas com Instrução", "O limite individual anual para dedução de despesas com instrução é de R$ 3.561,50.");
        saveChunk(doc, 17, 414, "Pergunta 414 — Cursos Pré-Vestibulares e Concursos", "Gastos com cursinhos pré-vestibulares ou preparatórios para concursos públicos não são dedutíveis da base de cálculo.");
        saveChunk(doc, 18, 473, "Pergunta 473 — Declaração Obrigatória de Criptoativos", "Os criptoativos e moedas virtuais devem ser declarados na ficha Bens e Direitos quando o valor de aquisição for igual ou superior a R$ 5.000,00.");
        saveChunk(doc, 19, 575, "Pergunta 575 — Isenção Ganho de Capital no Único Imóvel", "É isento o ganho de capital auferido na alienação do único imóvel por valor até R$ 440.000,00, desde que não tenha realizado outra alienação nos últimos 5 anos.");
        saveChunk(doc, 20, 707, "Pergunta 707 — Isenção de Ações no Mercado à Vista até R$ 20 mil", "São isentos do imposto de renda os ganhos líquidos auferidos em operações no mercado à vista de ações até o limite de R$ 20.000,00 por mês.");

        entityManager.flush();
    }

    private void saveChunk(ExegeseDocument doc, int seq, int qNum, String title, String content) {
        chunkRepository.save(new ExegeseChunk(
                doc,
                "golden-hash-" + qNum,
                seq,
                title,
                content,
                String.format("{\"page\": %d, \"questionNumber\": %d}", qNum, qNum)
        ));
    }

    @Test
    @DisplayName("Golden Dataset: 20 Official Questions retrieved with high accuracy")
    void testTwentyOfficialQuestions() {
        record GoldenCase(String query, String expectedSnippet) {}

        List<GoldenCase> cases = List.of(
                new GoldenCase("Quem está obrigado a apresentar declaração de imposto de renda?", "Pergunta 001"),
                new GoldenCase("Quem é considerado desobrigado de declarar?", "Pergunta 002"),
                new GoldenCase("Saldo em caderneta de poupança acima de 800 mil reais obriga declaração?", "Pergunta 010"),
                new GoldenCase("Qual o limite do desconto simplificado na declaração?", "Pergunta 012"),
                new GoldenCase("Qual é o prazo final de entrega da declaração em 2026?", "Pergunta 021"),
                new GoldenCase("Qual o valor da multa mínima por atraso sem imposto a pagar?", "Pergunta 024"),
                new GoldenCase("Quais as alíquotas da tabela progressiva anual?", "Pergunta 061"),
                new GoldenCase("Qual o valor limite de dedução anual por dependente?", "Pergunta 123"),
                new GoldenCase("Qual o limite dedutível de previdência complementar PGBL?", "Pergunta 187"),
                new GoldenCase("A pensão de ex-combatente da FEB é isenta?", "Pergunta 189"),
                new GoldenCase("Pensão alimentícia ADI 5422 sofre incidência de imposto?", "Pergunta 223"),
                new GoldenCase("Como é tributado ganho com apostas bets de quota fixa?", "Pergunta 318"),
                new GoldenCase("Filho de 24 anos cursando faculdade pode ser dependente?", "Pergunta 350"),
                new GoldenCase("Prótese de silicone pode ser deduzida no imposto de renda?", "Pergunta 367"),
                new GoldenCase("Teste de covid comprado em farmácia é dedutível?", "Pergunta 381"),
                new GoldenCase("Qual o limite individual de dedução com despesas de instrução educação?", "Pergunta 401"),
                new GoldenCase("Gastos com cursinho pré-vestibular e concurso são dedutíveis?", "Pergunta 414"),
                new GoldenCase("Como declarar criptoativos e Bitcoin na ficha de bens?", "Pergunta 473"),
                new GoldenCase("Venda do único imóvel até 440 mil reais tem isenção de ganho de capital?", "Pergunta 575"),
                new GoldenCase("Alienação de ações no mercado à vista até 20 mil reais no mês é isenta?", "Pergunta 707")
        );

        for (GoldenCase tc : cases) {
            List<SearchResultChunk> results = searchService.search(tc.query(), List.of(irpfSubject.getId()), 4);
            assertThat(results)
                    .as("Query '%s' should retrieve relevant chunks", tc.query())
                    .isNotEmpty();

            assertThat(results.get(0).chunkTitle())
                    .as("Top chunk for query '%s' must match expected question", tc.query())
                    .contains(tc.expectedSnippet());
        }
    }

    @Test
    @DisplayName("Golden Dataset: Control Question #21 (Out-of-Scope) triggers strict refusal")
    void testOutOfScopeControlQuestionRefusal() {
        // Query completely absent from the IRPF manual (ICMS state tax on fuel)
        List<SearchResultChunk> results = searchService.search(
                "Qual a alíquota de ICMS sobre combustíveis no estado de São Paulo?",
                List.of(irpfSubject.getId()),
                4
        );

        // Verification: If no matches are found, anti-hallucination guard immediately rejects
        boolean isGrounded = antiHallucinationGuard.isGrounded(results);
        assertThat(isGrounded).isFalse();
        assertThat(antiHallucinationGuard.getRefusalMessage())
                .isEqualTo(AntiHallucinationGuard.CANONICAL_REFUSAL_MESSAGE);
    }
}
