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
        // Grounding evidence travels with the result: lexical score here (no vector column outside PostgreSQL)
        assertThat(results.get(0).lexicalScore()).isGreaterThan(0.0);
        assertThat(results.get(0).vectorSimilarity()).isNull();
    }

    @Test
    @DisplayName("Search never returns documents linked only to inactive subjects (all subjects are public)")
    void testInactiveSubjectsExcludedFromSearch() {
        ExegeseSubject active = subjectRepository.save(
                new ExegeseSubject("visivel-ativo", "Assunto Ativo", "Ativo")
        );
        ExegeseSubject inactive = new ExegeseSubject("oculto-inativo", "Assunto Inativo", "Inativo");
        inactive.setActive(false);
        inactive = subjectRepository.save(inactive);

        ExegeseDocument onlyActive = saveDocumentWithChunk("Doc Ativo", "hash-vis-ativo", List.of(active));
        ExegeseDocument onlyInactive = saveDocumentWithChunk("Doc Inativo", "hash-vis-inativo", List.of(inactive));
        ExegeseDocument withoutSubject = saveDocumentWithChunk("Doc Sem Assunto", "hash-vis-nenhum", List.of());
        ExegeseDocument mixed = saveDocumentWithChunk("Doc Misto", "hash-vis-misto", List.of(active, inactive));
        entityManager.flush();

        // No filter: documents with an active subject or without any subject
        assertThat(documentIds(hybridSearchService.search("Zirconiofilia", List.of(), 10)))
                .contains(onlyActive.getId(), withoutSubject.getId(), mixed.getId())
                .doesNotContain(onlyInactive.getId());
        assertThat(documentIds(hybridSearchService.search("Zirconiofilia", null, 10)))
                .doesNotContain(onlyInactive.getId());

        // Active subject filter: only documents tagged with it
        assertThat(documentIds(hybridSearchService.search("Zirconiofilia", List.of(active.getId()), 10)))
                .containsExactlyInAnyOrder(onlyActive.getId(), mixed.getId());

        // Inactive (or unknown) subject ids are ignored: they never expose the hidden document
        assertThat(documentIds(hybridSearchService.search("Zirconiofilia", List.of(inactive.getId()), 10)))
                .contains(onlyActive.getId(), withoutSubject.getId(), mixed.getId())
                .doesNotContain(onlyInactive.getId());
        assertThat(documentIds(hybridSearchService.search("Zirconiofilia",
                List.of(inactive.getId(), java.util.UUID.randomUUID(), active.getId()), 10)))
                .containsExactlyInAnyOrder(onlyActive.getId(), mixed.getId());
    }

    private ExegeseDocument saveDocumentWithChunk(String title, String hash, List<ExegeseSubject> subjects) {
        ExegeseDocument doc = new ExegeseDocument(title, hash + ".pdf", "storage/" + hash + ".pdf", hash, 256L, "PDF");
        subjects.forEach(doc::addSubject);
        doc = documentRepository.save(doc);
        chunkRepository.save(new ExegeseChunk(
                doc,
                hash + "-chunk",
                1,
                "Zirconiofilia — " + title,
                "Texto de teste sobre zirconiofilia para validar a visibilidade por assunto.",
                "{}"
        ));
        return doc;
    }

    private static List<java.util.UUID> documentIds(List<SearchResultChunk> results) {
        return results.stream().map(SearchResultChunk::documentId).distinct().toList();
    }
}
