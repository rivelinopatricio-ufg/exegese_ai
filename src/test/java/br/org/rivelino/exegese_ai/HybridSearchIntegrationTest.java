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
import br.org.rivelino.exegese_ai.service.HybridSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests validating Hybrid Search and Reciprocal Rank Fusion (RRF).
 *
 * @author Rivelino Patrício
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class HybridSearchIntegrationTest {

    @Autowired
    private HybridSearchService hybridSearchService;

    @Autowired
    private ExegeseSubjectRepository subjectRepository;

    @Autowired
    private ExegeseDocumentRepository documentRepository;

    @Autowired
    private ExegeseChunkRepository chunkRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    @DisplayName("Hybrid search filters strictly by subject and calculates RRF scores")
    void testHybridSearchWithSubjectFiltering() {
        ExegeseSubject subjectIrpf = subjectRepository.save(
                new ExegeseSubject("irpf-rrf", "Tributário IRPF", "Assunto IRPF para teste RRF")
        );
        ExegeseSubject subjectClt = subjectRepository.save(
                new ExegeseSubject("clt-rrf", "Direito Trabalhista", "Assunto Trabalhista")
        );

        ExegeseDocument docIrpf = new ExegeseDocument(
                "Manual IRPF 2026",
                "manual_irpf_2026.pdf",
                "storage/irpf.pdf",
                "hash-irpf-rrf-001",
                1024L,
                "PDF"
        );
        docIrpf.addSubject(subjectIrpf);
        docIrpf = documentRepository.save(docIrpf);

        ExegeseDocument docClt = new ExegeseDocument(
                "Consolidação das Leis do Trabalho",
                "clt.pdf",
                "storage/clt.pdf",
                "hash-clt-rrf-002",
                2048L,
                "PDF"
        );
        docClt.addSubject(subjectClt);
        docClt = documentRepository.save(docClt);

        // Chunks for IRPF
        chunkRepository.save(new ExegeseChunk(
                docIrpf,
                "hash-chunk-medicas",
                1,
                "Pergunta 045 — Despesas Médicas Dedutíveis",
                "São dedutíveis da base de cálculo do IRPF os pagamentos efetuados a médicos, dentistas e hospitais segundo a Lei 9.250/1995.",
                "{\"page\": 45}"
        ));

        chunkRepository.save(new ExegeseChunk(
                docIrpf,
                "hash-chunk-cripto",
                2,
                "Pergunta 089 — Como declarar Criptoativos",
                "Os criptoativos e moedas virtuais devem ser informados na ficha Bens e Direitos sob o código correspondente.",
                "{\"page\": 89}"
        ));

        // Chunk for CLT
        chunkRepository.save(new ExegeseChunk(
                docClt,
                "hash-chunk-clt",
                1,
                "Artigo 477 — Rescisão do Contrato",
                "Na extinção do contrato de trabalho, o empregador deverá proceder à entrega dos documentos ao empregado.",
                "{\"page\": 10}"
        ));

        entityManager.flush();

        // Search in IRPF subject for "Despesas Médicas"
        List<SearchResultChunk> results = hybridSearchService.search(
                "Despesas Médicas Lei 9.250",
                List.of(subjectIrpf.getId()),
                4
        );

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).chunkTitle()).contains("Pergunta 045");
        assertThat(results.get(0).content()).contains("São dedutíveis da base de cálculo");
        assertThat(results.get(0).score()).isGreaterThan(0.0);

        // Verify CLT chunk was excluded because it belongs to subjectClt
        for (SearchResultChunk result : results) {
            assertThat(result.documentId()).isEqualTo(docIrpf.getId());
        }
    }

    @Test
    @DisplayName("Search with exact keyword retrieves specific chunk at top position")
    void testExactKeywordSearch() {
        ExegeseSubject subject = subjectRepository.save(
                new ExegeseSubject("tributario-geral", "Tributário Geral", "Acervo de tributos")
        );

        ExegeseDocument doc = new ExegeseDocument(
                "Regulamento IRPF",
                "regulamento.pdf",
                "storage/regulamento.pdf",
                "hash-doc-exato",
                512L,
                "PDF"
        );
        doc.addSubject(subject);
        doc = documentRepository.save(doc);

        chunkRepository.save(new ExegeseChunk(
                doc,
                "hash-chunk-cripto-exact",
                1,
                "Pergunta 120 — Criptoativos e Bitcoin",
                "Declaração obrigatória de Bitcoin e criptoativos na Receita Federal.",
                "{}"
        ));

        entityManager.flush();

        List<SearchResultChunk> results = hybridSearchService.search(
                "Bitcoin Criptoativos",
                List.of(subject.getId()),
                4
        );

        assertThat(results).isNotEmpty();
        assertThat(results.get(0).chunkTitle()).contains("Criptoativos e Bitcoin");
        assertThat(results.get(0).score()).isGreaterThan(0.0);
    }
}
